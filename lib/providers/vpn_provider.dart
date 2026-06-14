import 'dart:async';
import 'dart:math';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../models/traffic_stats.dart';

enum VpnStatus {
  disconnected,
  connecting,
  connected,
  disconnecting,
}

enum ProxyMode {
  global,
  auto,
  direct,
}

class VpnProvider extends ChangeNotifier {
  VpnStatus _status = VpnStatus.disconnected;
  ProxyMode _proxyMode = ProxyMode.global;
  TrafficStats _trafficStats = TrafficStats();
  Timer? _trafficTimer;
  Timer? _connectionTimer;

  VpnStatus get status => _status;
  ProxyMode get proxyMode => _proxyMode;
  TrafficStats get trafficStats => _trafficStats;
  bool get isConnected => _status == VpnStatus.connected;
  bool get isConnecting => _status == VpnStatus.connecting;

  static const EventChannel _eventChannel = EventChannel('com.proxyclient.proxy_client/vpn');
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
      },
    );
  }

  void _handleVpnEvent(Map event) {
    final String type = event['type'] ?? '';
    switch (type) {
      case 'connected':
        _status = VpnStatus.connected;
        _startTrafficSimulation();
        notifyListeners();
        break;
      case 'disconnected':
        _status = VpnStatus.disconnected;
        _stopTrafficSimulation();
        notifyListeners();
        break;
      case 'error':
        _status = VpnStatus.disconnected;
        _stopTrafficSimulation();
        notifyListeners();
        break;
      case 'traffic':
        _trafficStats = TrafficStats(
          uploadBytes: event['upload'] ?? _trafficStats.uploadBytes,
          downloadBytes: event['download'] ?? _trafficStats.downloadBytes,
          uploadSpeed: event['uploadSpeed'] ?? 0,
          downloadSpeed: event['downloadSpeed'] ?? 0,
        );
        notifyListeners();
        break;
    }
  }

  Future<void> connect() async {
    if (_status == VpnStatus.connecting || _status == VpnStatus.connected) return;

    _status = VpnStatus.connecting;
    notifyListeners();

    try {
      await Future.delayed(const Duration(seconds: 2));
      _status = VpnStatus.connected;
      _startTrafficSimulation();
      notifyListeners();
    } catch (e) {
      _status = VpnStatus.disconnected;
      notifyListeners();
    }
  }

  Future<void> disconnect() async {
    if (_status == VpnStatus.disconnected || _status == VpnStatus.disconnecting) return;

    _status = VpnStatus.disconnecting;
    notifyListeners();

    try {
      await Future.delayed(const Duration(milliseconds: 500));
      _status = VpnStatus.disconnected;
      _stopTrafficSimulation();
      notifyListeners();
    } catch (e) {
      _status = VpnStatus.disconnected;
      _stopTrafficSimulation();
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

  void setProxyMode(ProxyMode mode) {
    _proxyMode = mode;
    notifyListeners();
  }

  void _startTrafficSimulation() {
    _trafficTimer?.cancel();
    _trafficTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      final random = Random();
      final uploadSpeed = random.nextInt(50000) + 1000;
      final downloadSpeed = random.nextInt(200000) + 5000;

      _trafficStats = TrafficStats(
        uploadBytes: _trafficStats.uploadBytes + uploadSpeed,
        downloadBytes: _trafficStats.downloadBytes + downloadSpeed,
        uploadSpeed: uploadSpeed,
        downloadSpeed: downloadSpeed,
      );
      notifyListeners();
    });
  }

  void _stopTrafficSimulation() {
    _trafficTimer?.cancel();
    _trafficTimer = null;
  }

  void resetTrafficStats() {
    _trafficStats = TrafficStats();
    notifyListeners();
  }

  @override
  void dispose() {
    _trafficTimer?.cancel();
    _connectionTimer?.cancel();
    _eventSubscription?.cancel();
    super.dispose();
  }
}
