import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// 车载歌词服务（vivo 智能车载投屏适配）
/// 通过 MethodChannel 与原生 Android 端通信，实现双通道歌词传输
class CarLyricsService {
  static const MethodChannel _channel = MethodChannel('com.tencent.wecarflow/car_lyrics');
  
  /// 更新车载歌词
  /// [currentLine] 当前行歌词
  /// [wholeLrc] 完整 LRC 格式歌词
  /// [hasLyrics] 是否有歌词
  /// [title] 歌曲标题（用于恢复车机卡片歌名显示）
  /// [artist] 歌曲艺术家
  static Future<void> updateLyrics({
    required String currentLine,
    required String wholeLrc,
    required bool hasLyrics,
    String title = '',
    String artist = '',
    String mediaId = '',
  }) async {
    try {
      await _channel.invokeMethod('updateLyrics', {
        'currentLine': currentLine,
        'wholeLrc': wholeLrc,
        'hasLyrics': hasLyrics,
        'title': title,
        'artist': artist,
        'mediaId': mediaId,
      });
    } catch (e) {
      if (kDebugMode) {
        print('CarLyricsService error: $e');
      }
    }
  }

  /// 清空车载歌词
  static Future<void> clearLyrics() async {
    try {
      await _channel.invokeMethod('clearLyrics');
    } catch (e) {
      if (kDebugMode) {
        print('CarLyricsService clearLyrics error: $e');
      }
    }
  }
}