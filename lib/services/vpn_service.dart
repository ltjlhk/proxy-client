import 'package:flutter/services.dart';

class VpnNativeService {
  static const MethodChannel _methodChannel = MethodChannel(
    'com.proxyclient.proxy_client/vpn',
  );

  static Future<bool> connect({
    required String host,
    required int port,
    required String password,
    required String sni,
  }) async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('connect', {
        'host': host,
        'port': port,
        'password': password,
        'sni': sni,
      });
      return result ?? false;
    } catch (e) {
      return false;
    }
  }

  static Future<bool> disconnect() async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('disconnect');
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
