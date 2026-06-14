import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../providers/vpn_provider.dart';

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            // Title bar
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 20, 20, 0),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text(
                    'Proxy Client',
                    style: TextStyle(
                      fontSize: 24,
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                    ),
                  ),
                  Consumer<VpnProvider>(
                    builder: (context, vpn, child) {
                      return Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 12,
                          vertical: 6,
                        ),
                        decoration: BoxDecoration(
                          color: const Color(0xFF161B22),
                          borderRadius: BorderRadius.circular(20),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Container(
                              width: 8,
                              height: 8,
                              decoration: BoxDecoration(
                                color: vpn.isConnected
                                    ? const Color(0xFF3FB950)
                                    : vpn.isConnecting
                                        ? const Color(0xFFD29922)
                                        : const Color(0xFFF85149),
                                shape: BoxShape.circle,
                              ),
                            ),
                            const SizedBox(width: 6),
                            Text(
                              vpn.isConnected
                                  ? 'Connected'
                                  : vpn.isConnecting
                                      ? 'Connecting'
                                      : 'Disconnected',
                              style: const TextStyle(
                                fontSize: 12,
                                color: Colors.white,
                                fontWeight: FontWeight.w500,
                              ),
                            ),
                          ],
                        ),
                      );
                    },
                  ),
                ],
              ),
            ),

            const Spacer(),

            // Central connection button
            Consumer<VpnProvider>(
              builder: (context, vpn, child) {
                return GestureDetector(
                  onTap: () => vpn.toggleConnection(),
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 300),
                    curve: Curves.easeInOut,
                    width: 180,
                    height: 180,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      gradient: LinearGradient(
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                        colors: vpn.isConnected
                            ? [
                                const Color(0xFF238636),
                                const Color(0xFF2EA043),
                              ]
                            : vpn.isConnecting
                                ? [
                                    const Color(0xFF9E6A03),
                                    const Color(0xFFD29922),
                                  ]
                                : [
                                    const Color(0xFF30363D),
                                    const Color(0xFF484F58),
                                  ],
                      ),
                      boxShadow: [
                        BoxShadow(
                          color: (vpn.isConnected
                                  ? const Color(0xFF238636)
                                  : vpn.isConnecting
                                      ? const Color(0xFFD29922)
                                      : const Color(0xFF484F58))
                              .withOpacity(0.4),
                          blurRadius: 30,
                          spreadRadius: 2,
                        ),
                      ],
                    ),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(
                          vpn.isConnected
                              ? Icons.power_settings_new
                              : vpn.isConnecting
                                  ? Icons.hourglass_top
                                  : Icons.power_settings_new,
                          size: 60,
                          color: Colors.white.withOpacity(0.9),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          vpn.isConnected
                              ? 'CONNECTED'
                              : vpn.isConnecting
                                  ? 'CONNECTING'
                                  : 'TAP TO CONNECT',
                          style: TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                            color: Colors.white.withOpacity(0.8),
                            letterSpacing: 1.2,
                          ),
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),

            const Spacer(),

            // Traffic stats
            Consumer<VpnProvider>(
              builder: (context, vpn, child) {
                return Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 40),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      _buildTrafficItem(
                        icon: Icons.arrow_upward,
                        label: 'Upload',
                        value: _formatBytes(vpn.uploadBytes),
                        speed: vpn.isConnected
                            ? '${_formatSpeed(vpn.uploadSpeed)}/s'
                            : null,
                      ),
                      _buildTrafficItem(
                        icon: Icons.arrow_downward,
                        label: 'Download',
                        value: _formatBytes(vpn.downloadBytes),
                        speed: vpn.isConnected
                            ? '${_formatSpeed(vpn.downloadSpeed)}/s'
                            : null,
                      ),
                    ],
                  ),
                );
              },
            ),

            const SizedBox(height: 30),

            // Server info card
            Container(
              margin: const EdgeInsets.symmetric(horizontal: 20),
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: const Color(0xFF161B22),
                borderRadius: BorderRadius.circular(16),
                border: Border.all(
                  color: const Color(0xFF30363D),
                  width: 1,
                ),
              ),
              child: Column(
                children: [
                  _buildInfoRow('Protocol', 'Trojan'),
                  const SizedBox(height: 8),
                  _buildInfoRow('Server', '47.80.241.156'),
                  const SizedBox(height: 8),
                  _buildInfoRow('Port', '8443'),
                  const SizedBox(height: 8),
                  _buildInfoRow('TLS SNI', 'proxy.local'),
                ],
              ),
            ),

            // Error message
            Consumer<VpnProvider>(
              builder: (context, vpn, child) {
                if (vpn.errorMessage.isNotEmpty) {
                  return Padding(
                    padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
                    child: Text(
                      vpn.errorMessage,
                      style: const TextStyle(
                        color: Color(0xFFF85149),
                        fontSize: 13,
                      ),
                      textAlign: TextAlign.center,
                    ),
                  );
                }
                return const SizedBox.shrink();
              },
            ),

            const SizedBox(height: 30),
          ],
        ),
      ),
    );
  }

  Widget _buildTrafficItem({
    required IconData icon,
    required String label,
    required String value,
    String? speed,
  }) {
    return Column(
      children: [
        Icon(icon, size: 20, color: const Color(0xFF58A6FF)),
        const SizedBox(height: 4),
        Text(
          label,
          style: const TextStyle(
            fontSize: 12,
            color: Color(0xFF8B949E),
          ),
        ),
        const SizedBox(height: 2),
        Text(
          value,
          style: const TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w600,
            color: Colors.white,
          ),
        ),
        if (speed != null)
          Text(
            speed,
            style: const TextStyle(
              fontSize: 11,
              color: Color(0xFF58A6FF),
            ),
          ),
      ],
    );
  }

  Widget _buildInfoRow(String label, String value) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(
          label,
          style: const TextStyle(
            fontSize: 13,
            color: Color(0xFF8B949E),
          ),
        ),
        Text(
          value,
          style: const TextStyle(
            fontSize: 13,
            color: Colors.white,
            fontWeight: FontWeight.w500,
          ),
        ),
      ],
    );
  }

  static String _formatBytes(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1048576) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    if (bytes < 1073741824) {
      return '${(bytes / 1048576).toStringAsFixed(1)} MB';
    }
    return '${(bytes / 1073741824).toStringAsFixed(2)} GB';
  }

  static String _formatSpeed(int bytesPerSecond) {
    if (bytesPerSecond < 1024) return '$bytesPerSecond B';
    if (bytesPerSecond < 1048576) {
      return '${(bytesPerSecond / 1024).toStringAsFixed(1)} KB';
    }
    return '${(bytesPerSecond / 1048576).toStringAsFixed(1)} MB';
  }
}
