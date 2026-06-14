class ServerNode {
  final String id;
  String name;
  String address;
  int port;
  String protocol;
  bool isActive;
  int latency;

  ServerNode({
    required this.id,
    required this.name,
    required this.address,
    required this.port,
    this.protocol = 'socks5',
    this.isActive = false,
    this.latency = 0,
  });

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'name': name,
      'address': address,
      'port': port,
      'protocol': protocol,
      'isActive': isActive,
      'latency': latency,
    };
  }

  factory ServerNode.fromJson(Map<String, dynamic> json) {
    return ServerNode(
      id: json['id'],
      name: json['name'],
      address: json['address'],
      port: json['port'],
      protocol: json['protocol'] ?? 'socks5',
      isActive: json['isActive'] ?? false,
      latency: json['latency'] ?? 0,
    );
  }

  ServerNode copyWith({
    String? id,
    String? name,
    String? address,
    int? port,
    String? protocol,
    bool? isActive,
    int? latency,
  }) {
    return ServerNode(
      id: id ?? this.id,
      name: name ?? this.name,
      address: address ?? this.address,
      port: port ?? this.port,
      protocol: protocol ?? this.protocol,
      isActive: isActive ?? this.isActive,
      latency: latency ?? this.latency,
    );
  }
}
