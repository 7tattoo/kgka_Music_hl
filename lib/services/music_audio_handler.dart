import 'dart:async';

import 'package:audio_service/audio_service.dart';
import 'package:just_audio/just_audio.dart';

import '../models/music_models.dart';

class MusicAudioHandler extends BaseAudioHandler
    with QueueHandler, SeekHandler {
  MusicAudioHandler() {
    audioPlayer.playbackEventStream
        .map(_playbackStateForEvent)
        .pipe(playbackState);
  }

  final AudioPlayer audioPlayer = AudioPlayer();

  Future<void> Function()? _onNext;
  Future<void> Function()? _onPrevious;
  int _queueIndex = 0;
  Song? _currentSong;

  void attachTransportControls({
    required Future<void> Function() onNext,
    required Future<void> Function() onPrevious,
  }) {
    _onNext = onNext;
    _onPrevious = onPrevious;
  }

  void detachTransportControls() {
    _onNext = null;
    _onPrevious = null;
  }

  Future<void> loadSong({
    required Song song,
    required String url,
    required List<Song> queueSongs,
    required int queueIndex,
  }) async {
    _currentSong = song;
    _queueIndex = queueIndex < 0 ? 0 : queueIndex;
    final currentItem = _mediaItemFor(song);
    final items = queueSongs.map(_mediaItemFor).toList(growable: false);

    if (items.isNotEmpty) {
      queue.add(items);
    }
    mediaItem.add(currentItem);
    if (url.startsWith('http://') || url.startsWith('https://')) {
      await audioPlayer.setUrl(url);
    } else {
      await audioPlayer.setAudioSource(AudioSource.file(url));
    }
  }

  @override
  Future<void> updateQueue(List<MediaItem> newQueue) async {
    queue.add(newQueue);
  }

  Future<void> setSongQueue({
    required List<Song> queueSongs,
    required int queueIndex,
    Song? currentSong,
  }) async {
    if (currentSong != null) _currentSong = currentSong;
    _queueIndex = queueIndex < 0 ? 0 : queueIndex;
    queue.add(queueSongs.map(_mediaItemFor).toList(growable: false));
    if (currentSong != null) {
      mediaItem.add(_mediaItemFor(currentSong));
    }
  }

  /// 更新当前播放歌曲的 MediaSession Metadata，写入歌词字段。
  ///
  /// 大多数车机系统不会直接读 AVRCP 里的歌词，但通过 media_item extras
  /// 写入 `lyric / currentLyric` 字段后，SuperLyric 模块或第三方车载 App 可从
  /// MediaSession 元数据里取出歌词并展示。
  ///
  /// 当 [lyricText] 为 `null` 时表示清空歌词（暂停/切歌前）。
  void updateLyricMetadata({
    String? lyricText,
    String? translationText,
    String? romanizationText,
  }) {
    final song = _currentSong;
    if (song == null) return;
    final current = mediaItem.valueOrNull;
    final Map<String, dynamic> extras = {
      'hash': song.hash,
      'songId': song.id,
      // 能力位常驻（原子随身听歌词区 bit8 + 进度条 bit16）。
      'vivomusicmix.media.metadata.support_event': 31,
      if (lyricText != null) 'lyric': lyricText,
      if (lyricText != null) 'currentLyric': lyricText,
      if (translationText != null) 'translationLyric': translationText,
      if (romanizationText != null) 'romanLyric': romanizationText,
    };
    // 保留已注入的车联投屏（ucar）/ 原子随身听（vivomusicmix）歌词字段，
    // 避免蓝牙歌词路径重建 MediaItem 时把车机歌词覆盖回单行（统一出口协调）。
    final existing = current?.extras;
    if (existing != null) {
      existing.forEach((key, value) {
        if (key.startsWith('ucar.media.metadata.') ||
            key.startsWith('vivomusicmix.media.metadata.')) {
          extras[key] = value;
        }
      });
    }
    final updated = MediaItem(
      id: song.hash.isEmpty ? song.id : song.hash,
      album: song.albumName,
      title: song.title,
      artist: song.artist,
      duration: song.duration,
      artUri: song.coverUrl == null ? null : Uri.tryParse(song.coverUrl!),
      extras: extras,
    );
    mediaItem.add(updated);
  }

  @override
  Future<void> play() async {
    unawaited(audioPlayer.play());
  }

  @override
  Future<void> pause() async {
    await audioPlayer.pause();
  }

  @override
  Future<void> seek(Duration position) async {
    await audioPlayer.seek(position);
  }

  @override
  Future<void> skipToNext() async {
    await _onNext?.call();
  }

  @override
  Future<void> skipToPrevious() async {
    await _onPrevious?.call();
  }

  @override
  Future<void> stop() async {
    await audioPlayer.stop();
  }

  Future<void> close() async {
    await audioPlayer.dispose();
  }

  /// 发布车载歌词（让 audio_service 把歌词写进 metadata / extras）
  /// [line] 当前行歌词；[wholeLrc] 完整LRC；[hasLyrics] 是否有；
  /// [loading] 歌词是否加载中（切歌后）
  void publishCarLyrics({
    required String line,
    required String wholeLrc,
    required bool hasLyrics,
    required bool loading,
    Song? song,
  }) {
    final current = mediaItem.valueOrNull;
    if (current == null) return;
    // 切歌瞬间（loadSong 还没发布新 MediaItem）此处的 current 仍是上一首。
    // 若拿上一首的 id/封面/时长拼一个"标题是新歌"的 MediaItem 推出去，
    // 车机会先收到一张错配卡片（旧封面 + 新歌名），随后才被 loadSong 纠正。
    // 新 MediaItem 本身不含任何歌词字段（见 _mediaItemFor），无需在此清理，
    // 因此 id 不匹配时直接放弃这次推送。
    final expectedId =
        song == null ? current.id : (song.hash.isEmpty ? song.id : song.hash);
    if (expectedId != current.id) return;
    final songTitle = song?.title ?? current.title;
    final songArtist = song?.artist ?? current.artist;
    final baseExtras = Map<String, dynamic>.from(current.extras ?? {});
    baseExtras['hash'] = song?.hash ?? baseExtras['hash'] ?? '';
    baseExtras['songId'] = song?.id ?? baseExtras['songId'] ?? '';

    // 铁律 1：绝不写 LYRICS_LINE —— 车机一旦读到会切到单行卡片并忽略整段 WHOLE。
    baseExtras.remove('ucar.media.metadata.LYRICS_LINE');

    // 能力位是"本应用支持哪些功能"的声明，与当前有没有歌词无关，必须常驻：
    // 31 = 7 | 8 | 16 —— 缺 8 位原子随身听不显示歌词区，缺 16 位进度条恒 --:--。
    baseExtras['vivomusicmix.media.metadata.support_event'] = 31;

    final bool hasWhole = hasLyrics && !loading && wholeLrc.isNotEmpty;
    if (hasWhole) {
      // 有整段歌词：只写 WHOLE + STATUS=0（有歌词）。
      baseExtras['ucar.media.metadata.LYRICS_WHOLE'] = wholeLrc;
      baseExtras['ucar.media.metadata.LYRICS_STATUS'] = 0;
      baseExtras['ucar.media.metadata.UCAR_TITLE'] = songTitle;
      baseExtras['ucar.media.metadata.UCAR_ARTIST'] = songArtist;
    } else {
      // 铁律 3：无整段歌词 / 加载中，一律移除歌词字段，绝不写负状态。
      // 写 STATUS=1/2 语义是"确认无歌词"，会让车机在异步歌词到达前永久退回单行。
      // 注意：能力位 support_event 不在此移除，它不是歌词字段。
      baseExtras.remove('ucar.media.metadata.LYRICS_WHOLE');
      baseExtras.remove('ucar.media.metadata.LYRICS_STATUS');
      baseExtras.remove('ucar.media.metadata.UCAR_TITLE');
      baseExtras.remove('ucar.media.metadata.UCAR_ARTIST');
    }

    // 铁律 2：内容没变就不重发。
    // 频繁 onMetadataChanged 会让车机把歌词卡片状态重置回单行，
    // 因此周期兜底重发只在 metadata 真被其他路径覆盖过时才真正生效。
    if (!_carExtrasChanged(current.extras, baseExtras) &&
        songTitle == current.title &&
        songArtist == current.artist) {
      return;
    }

    mediaItem.add(MediaItem(
      id: current.id,
      album: current.album,
      title: songTitle,
      artist: songArtist,
      duration: current.duration,
      artUri: current.artUri,
      displayTitle: current.displayTitle,
      displaySubtitle: current.displaySubtitle,
      displayDescription: current.displayDescription,
      extras: baseExtras,
    ));
  }

  /// 只提取车机歌词相关字段（ucar.* / vivomusicmix.*）。
  ///
  /// 其余键（hash / songId / 蓝牙歌词字段）的变化不应触发车机 metadata 重发。
  Map<String, dynamic> _carLyricFields(Map<String, dynamic>? extras) {
    final result = <String, dynamic>{};
    if (extras == null) return result;
    extras.forEach((key, value) {
      if (key.startsWith('ucar.media.metadata.') ||
          key.startsWith('vivomusicmix.media.metadata.')) {
        result[key] = value;
      }
    });
    return result;
  }

  /// 判断车机歌词字段是否真的发生了变化（铁律 2 的判定依据）。
  bool _carExtrasChanged(
    Map<String, dynamic>? oldExtras,
    Map<String, dynamic> newExtras,
  ) {
    final oldCar = _carLyricFields(oldExtras);
    final newCar = _carLyricFields(newExtras);
    if (oldCar.length != newCar.length) return true;
    for (final entry in newCar.entries) {
      if (oldCar[entry.key] != entry.value) return true;
    }
    return false;
  }

  MediaItem _mediaItemFor(Song song) {
    return MediaItem(
      id: song.hash.isEmpty ? song.id : song.hash,
      album: song.albumName,
      title: song.title,
      artist: song.artist,
      duration: song.duration,
      artUri: song.coverUrl == null ? null : Uri.tryParse(song.coverUrl!),
      extras: {
        'hash': song.hash,
        'songId': song.id,
        // 能力位常驻：声明本应用支持歌词区（bit8）与进度条（bit16），
        // 与"当前是否有歌词"无关，故每个 MediaItem 都带上。
        'vivomusicmix.media.metadata.support_event': 31,
        // 车载歌词字段（ucar.*）一律留空不写：
        // 铁律 1 禁止 LYRICS_LINE，铁律 3 禁止在歌词就绪前写 LYRICS_STATUS=1/2
        // （负状态语义是"确认无歌词"，车机会永久退回单行卡片）。
        // 歌词就绪后由 publishCarLyrics 统一注入。
      },
    );
  }

  PlaybackState _playbackStateForEvent(PlaybackEvent event) {
    return PlaybackState(
      controls: [
        MediaControl.skipToPrevious,
        if (audioPlayer.playing) MediaControl.pause else MediaControl.play,
        MediaControl.skipToNext,
      ],
      systemActions: const {
        MediaAction.seek,
        MediaAction.seekBackward,
        MediaAction.seekForward,
      },
      androidCompactActionIndices: const [0, 1, 2],
      processingState: const {
        ProcessingState.idle: AudioProcessingState.idle,
        ProcessingState.loading: AudioProcessingState.loading,
        ProcessingState.buffering: AudioProcessingState.buffering,
        ProcessingState.ready: AudioProcessingState.ready,
        ProcessingState.completed: AudioProcessingState.completed,
      }[audioPlayer.processingState]!,
      playing: audioPlayer.playing,
      updatePosition: audioPlayer.position,
      bufferedPosition: audioPlayer.bufferedPosition,
      speed: audioPlayer.speed,
      queueIndex: _queueIndex,
    );
  }
}
