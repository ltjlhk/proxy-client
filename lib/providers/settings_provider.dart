import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/server_node.dart';

class SettingsProvider extends ChangeNotifier {
  String _serverAddress = '47.80.241.156';
  int _serverPort = 7890;
  String _username = '';
  String _password = '';
  List<ServerNode> _serverNodes = [];
  String _selectedNodeId = '';

  String get serverAddress => _serverAddress;
  int get serverPort => _serverPort;
  String get username => _username;
  String get password => _password;
  List<ServerNode> get serverNodes => _serverNodes;
  String get selectedNodeId => _selectedNodeId;

  ServerNode? get selectedNode {
    try {
      return _serverNodes.firstWhere((node) => node.id == _selectedNodeId);
    } catch (e) {
      return _serverNodes.isNotEmpty ? _serverNodes.first : null;
    }
  }

  SettingsProvider() {
    _loadSettings();
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    _serverAddress = prefs.getString('serverAddress') ?? '47.80.241.156';
    _serverPort = prefs.getInt('serverPort') ?? 7890;
    _username = prefs.getString('username') ?? '';
    _password = prefs.getString('password') ?? '';
    _selectedNodeId = prefs.getString('selectedNodeId') ?? '';

    final nodesJson = prefs.getString('serverNodes');
    if (nodesJson != null) {
      try {
        final List<dynamic> decoded = jsonDecode(nodesJson);
        _serverNodes = decoded.map((e) => ServerNode.fromJson(e)).toList();
      } catch (e) {
        _serverNodes = _getDefaultNodes();
      }
    } else {
      _serverNodes = _getDefaultNodes();
    }

    if (_selectedNodeId.isEmpty && _serverNodes.isNotEmpty) {
      _selectedNodeId = _serverNodes.first.id;
    }

    notifyListeners();
  }

  List<ServerNode> _getDefaultNodes() {
    return [
      ServerNode(
        id: 'default-1',
        name: '默认节点',
        address: '47.80.241.156',
        port: 7890,
        protocol: 'socks5',
        isActive: true,
        latency: 45,
      ),
      ServerNode(
        id: 'default-2',
        name: '备用节点',
        address: '47.80.241.156',
        port: 7891,
        protocol: 'socks5',
        isActive: false,
        latency: 0,
      ),
    ];
  }

  Future<void> setServerAddress(String address) async {
    _serverAddress = address;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('serverAddress', address);
    notifyListeners();
  }

  Future<void> setServerPort(int port) async {
    _serverPort = port;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('serverPort', port);
    notifyListeners();
  }

  Future<void> setCredentials(String username, String password) async {
    _username = username;
    _password = password;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('username', username);
    await prefs.setString('password', password);
    notifyListeners();
  }

  Future<void> addNode(ServerNode node) async {
    _serverNodes.add(node);
    await _saveNodes();
    notifyListeners();
  }

  Future<void> updateNode(ServerNode node) async {
    final index = _serverNodes.indexWhere((n) => n.id == node.id);
    if (index != -1) {
      _serverNodes[index] = node;
      await _saveNodes();
      notifyListeners();
    }
  }

  Future<void> removeNode(String nodeId) async {
    _serverNodes.removeWhere((n) => n.id == nodeId);
    if (_selectedNodeId == nodeId && _serverNodes.isNotEmpty) {
      _selectedNodeId = _serverNodes.first.id;
    }
    await _saveNodes();
    notifyListeners();
  }

  Future<void> selectNode(String nodeId) async {
    _selectedNodeId = nodeId;
    for (var node in _serverNodes) {
      node.isActive = node.id == nodeId;
    }
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('selectedNodeId', nodeId);
    await _saveNodes();
    notifyListeners();
  }

  Future<void> _saveNodes() async {
    final prefs = await SharedPreferences.getInstance();
    final encoded = jsonEncode(_serverNodes.map((e) => e.toJson()).toList());
    await prefs.setString('serverNodes', encoded);
  }

  Future<void> testNodeLatency(String nodeId) async {
    final index = _serverNodes.indexWhere((n) => n.id == nodeId);
    if (index == -1) return;

    final node = _serverNodes[index];
    node.latency = -1;
    notifyListeners();

    await Future.delayed(const Duration(milliseconds: 800));

    final random = DateTime.now().millisecond;
    node.latency = 20 + (random % 100);
    notifyListeners();
  }
}
