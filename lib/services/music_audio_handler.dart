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
    _queueIndex = queueIndex < 0 ? 0 : queueIndex;
    queue.add(queueSongs.map(_mediaItemFor).toList(growable: false));
    if (currentSong != null) {
      mediaItem.add(_mediaItemFor(currentSong));
    }
  }

  @override
  Future<void> play() async {
    await audioPlayer.play();
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
        // 车载歌词字段：通过 MediaItem.extras 让 audio_service 写入 metadata
        'ucar.media.metadata.LYRICS_LINE': '',
        'ucar.media.metadata.LYRICS_WHOLE': '',
        'ucar.media.metadata.LYRICS_STATUS': 2, // loading
      },
    );
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
    final songTitle = song?.title ?? current.title;
    final songArtist = song?.artist ?? current.artist;
    final baseExtras = Map<String, dynamic>.from(current.extras ?? {});
    baseExtras['hash'] = song?.hash ?? baseExtras['hash'] ?? '';
    baseExtras['songId'] = song?.id ?? baseExtras['songId'] ?? '';

    // 歌词状态：
    //   loading -> 不设置歌词字段，避免显示 "-1"；等就绪后推送
    //   hasLyrics -> 整首LRC + 当前行
    //   无歌词 -> LYRICS_WHOLE=-1, STATUS=1
    if (!loading) {
      baseExtras['ucar.media.metadata.LYRICS_LINE'] = line;
      baseExtras['ucar.media.metadata.LYRICS_WHOLE'] =
          (hasLyrics && wholeLrc.isNotEmpty) ? wholeLrc : '-1';
      baseExtras['ucar.media.metadata.LYRICS_STATUS'] = hasLyrics ? 0 : 1;
    } else {
      // 加载中：不写 "-1"，用 loading 状态
      baseExtras['ucar.media.metadata.LYRICS_LINE'] = '';
      baseExtras['ucar.media.metadata.LYRICS_WHOLE'] = '';
      baseExtras['ucar.media.metadata.LYRICS_STATUS'] = 2; // loading
    }

    // 歌曲栏读取来源：TITLE 必须保持歌名，否则歌曲栏/歌词栏会显示相同内容，
    // 车机会判定为"纯歌词流"退化为单行模式。歌词只走 LYRICS_* 字段。
    baseExtras['UCAR_TITLE'] = song?.title ?? current.title;
    baseExtras['UCAR_ARTIST'] = song?.artist ?? current.artist;

    mediaItem.add(MediaItem(
      id: current.id,
      album: current.album,
      // TITLE 保持歌名（歌曲栏正常显示），歌词走 LYRICS_LINE/WHOLE
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
