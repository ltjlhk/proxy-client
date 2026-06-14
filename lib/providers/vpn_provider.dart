import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../services/vpn_service.dart';

enum VpnStatus {
  disconnected,
  connecting,
  connected,
  disconnecting,
}

class VpnProvider extends ChangeNotifier {
  VpnStatus _status = VpnStatus.disconnected;
  String _errorMessage = '';
  int _uploadBytes = 0;
  int _downloadBytes = 0;
  int _uploadSpeed = 0;
  int _downloadSpeed = 0;

  // Server configuration
  final String serverHost = '47.80.241.156';
  final int serverPort = 8443;
  final String trojanPassword = 'proxy123456';
  final String tlsSni = 'proxy.local';

  VpnStatus get status => _status;
  String get errorMessage => _errorMessage;
  bool get isConnected => _status == VpnStatus.connected;
  bool get isConnecting => _status == VpnStatus.connecting;
  int get uploadBytes => _uploadBytes;
  int get downloadBytes => _downloadBytes;
  int get uploadSpeed => _uploadSpeed;
  int get downloadSpeed => _downloadSpeed;

  static const EventChannel _eventChannel =
      EventChannel('com.proxyclient.proxy_client/vpn');
  StreamSubscription? _eventSubscription;

  VpnProvider() {
    _initEventChannel();
  }

  void _initEventChannel() {
    _eventSubscription = _eventChannel.receiveBroadcastStream().listen(
      (dynamic event) {
        if (event is Map) {
          _handleVpnEvent(event);
        }
      },
      onError: (dynamic error) {
        debugPrint('VPN Event Channel Error: $error');
        _status = VpnStatus.disconnected;
        _errorMessage = error.toString();
        notifyListeners();
      },
    );
  }

  void _handleVpnEvent(Map event) {
    final String type = event['type'] ?? '';
    switch (type) {
      case 'connected':
        _status = VpnStatus.connected;
        _errorMessage = '';
        notifyListeners();
        break;
      case 'disconnected':
        _status = VpnStatus.disconnected;
        _errorMessage = '';
        notifyListeners();
        break;
      case 'error':
        _status = VpnStatus.disconnected;
        _errorMessage = event['message'] ?? 'Unknown error';
        notifyListeners();
        break;
      case 'traffic':
        _uploadBytes = event['upload'] ?? _uploadBytes;
        _downloadBytes = event['download'] ?? _downloadBytes;
        _uploadSpeed = event['uploadSpeed'] ?? 0;
        _downloadSpeed = event['downloadSpeed'] ?? 0;
        notifyListeners();
        break;
    }
  }

  Future<void> connect() async {
    if (_status == VpnStatus.connecting || _status == VpnStatus.connected) return;

    _status = VpnStatus.connecting;
    _errorMessage = '';
    notifyListeners();

    try {
      final success = await VpnNativeService.connect(
        host: serverHost,
        port: serverPort,
        password: trojanPassword,
        sni: tlsSni,
      );
      if (!success) {
        _status = VpnStatus.disconnected;
        _errorMessage = 'Failed to start VPN';
        notifyListeners();
      }
      // Status will be updated via event channel
    } catch (e) {
      _status = VpnStatus.disconnected;
      _errorMessage = e.toString();
      notifyListeners();
    }
  }

  Future<void> disconnect() async {
    if (_status == VpnStatus.disconnected ||
        _status == VpnStatus.disconnecting) return;

    _status = VpnStatus.disconnecting;
    notifyListeners();

    try {
      await VpnNativeService.disconnect();
      _status = VpnStatus.disconnected;
      _uploadBytes = 0;
      _downloadBytes = 0;
      _uploadSpeed = 0;
      _downloadSpeed = 0;
      notifyListeners();
    } catch (e) {
      _status = VpnStatus.disconnected;
      _errorMessage = e.toString();
      notifyListeners();
    }
  }

  void toggleConnection() {
    if (isConnected || isConnecting) {
      disconnect();
    } else {
      connect();
    }
  }

  @override
  void dispose() {
    _eventSubscription?.cancel();
    super.dispose();
  }
}
