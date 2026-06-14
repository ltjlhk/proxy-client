import 'package:flutter/services.dart';

class VpnNativeService {
  static const MethodChannel _methodChannel = MethodChannel(
    'com.proxyclient.proxy_client/vpn_method',
  );

  static Future<bool> connectVpn({
    required String host,
    required int port,
    String mode = 'global',
  }) async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('connectVpn', {
        'host': host,
        'port': port,
        'mode': mode,
      });
      return result ?? false;
    } catch (e) {
      return false;
    }
  }

  static Future<bool> disconnectVpn() async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('disconnectVpn');
      return result ?? false;
    } catch (e) {
      return false;
    }
  }

  static Future<bool> isVpnRunning() async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('isVpnRunning');
      return result ?? false;
    } catch (e) {
      return false;
    }
  }
}
