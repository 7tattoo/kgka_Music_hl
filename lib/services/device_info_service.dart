import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// 设备类型检测服务。
///
/// 通过 MethodChannel 调用原生层判断是否为 Android Automotive 车机。
/// 非 Android 平台恒返回 false。
class DeviceInfoService {
  const DeviceInfoService();

  static const MethodChannel _channel = MethodChannel('kgka_music_hl/device');

  static bool get isSupportedPlatform {
    return !kIsWeb && defaultTargetPlatform == TargetPlatform.android;
  }

  /// 是否为 Android Automotive 车机（含投屏 UI 模式）。
  Future<bool> isAutomotive() async {
    if (!isSupportedPlatform) return false;
    try {
      return await _channel.invokeMethod<bool>('isAutomotive') ?? false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  /// 是否为车载投屏环境（外接显示 / vivo 车联等）。
  /// 比 isAutomotive 更宽泛，用于播放页自动启用分栏布局。
  Future<bool> isCarProjection() async {
    if (!isSupportedPlatform) return false;
    try {
      return await _channel.invokeMethod<bool>('isCarProjection') ?? false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

}
