import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// vivo 原子随身听（vivomusicmix）歌词服务。
///
/// 作用：
/// 把整段 LRC 通过 `MediaSession.setExtras()` 以 `lrc_change` 事件发送给
/// vivo 原子随身听组件（车机通知区 / 桌面小组件的滚动歌词）。
///
/// 实现细节：
/// - audio_service 0.18.x 内部持有的 `MediaSessionCompat` 未通过 Dart 暴露，
///   故由原生侧（MainActivity 的 `kgka_music_hl/car_lyrics` 通道）反射
///   `com.ryanheise.audioservice.AudioService.instance.mediaSession` 后调用
///   `setExtras(Bundle)` 完成。
/// - vivo 官方协议键含拼写错误（`meida` / `meidia_id`），原生侧照抄。
/// - 节流（约 25 秒）由调用方 [PlayerController] 控制，本服务只负责透传。
///
/// 仅在 Android 平台生效，非 Android 平台所有方法均为安全的 no-op。
class CarLyricsService {
  static const _channel = MethodChannel('kgka_music_hl/car_lyrics');

  static bool get isSupportedPlatform {
    return !kIsWeb && defaultTargetPlatform == TargetPlatform.android;
  }

  /// 发送 `lrc_change` 事件：把整段 [wholeLrc] 推给原子随身听。
  ///
  /// [mediaId] 必须等于当前曲目的公开 MEDIA_ID（即 MediaItem.id），
  /// 否则原子随身听会因 id 不匹配而隐藏歌词。
  Future<void> pushAtomicLyrics({
    required String wholeLrc,
    required String mediaId,
  }) async {
    if (!isSupportedPlatform) return;
    if (wholeLrc.isEmpty) return;
    try {
      await _channel.invokeMethod<void>('pushAtomicLyrics', {
        'lyric': wholeLrc,
        'mediaId': mediaId,
      });
    } on MissingPluginException {
      // ignore
    } catch (_) {
      // ignore
    }
  }
}
