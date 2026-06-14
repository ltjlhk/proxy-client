import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../providers/vpn_provider.dart';

class ProxyModeSelector extends StatelessWidget {
  const ProxyModeSelector({super.key});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20),
      child: Container(
        padding: const EdgeInsets.all(4),
        decoration: BoxDecoration(
          color: const Color(0xFF2A2A2A),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Consumer<VpnProvider>(
          builder: (context, vpn, child) {
            return Row(
              children: [
                _buildModeButton(
                  context,
                  mode: ProxyMode.global,
                  label: '全局',
                  icon: Icons.public,
                  currentMode: vpn.proxyMode,
                  onTap: () => vpn.setProxyMode(ProxyMode.global),
                ),
                _buildModeButton(
                  context,
                  mode: ProxyMode.auto,
                  label: '自动',
                  icon: Icons.auto_mode,
                  currentMode: vpn.proxyMode,
                  onTap: () => vpn.setProxyMode(ProxyMode.auto),
                ),
                _buildModeButton(
                  context,
                  mode: ProxyMode.direct,
                  label: '直连',
                  icon: Icons.route,
                  currentMode: vpn.proxyMode,
                  onTap: () => vpn.setProxyMode(ProxyMode.direct),
                ),
              ],
            );
          },
        ),
      ),
    );
  }

  Widget _buildModeButton(
    BuildContext context, {
    required ProxyMode mode,
    required String label,
    required IconData icon,
    required ProxyMode currentMode,
    required VoidCallback onTap,
  }) {
    final isSelected = mode == currentMode;

    return Expanded(
      child: GestureDetector(
        onTap: onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 200),
          padding: const EdgeInsets.symmetric(vertical: 12),
          decoration: BoxDecoration(
            color: isSelected ? const Color(0xFF00D26A) : Colors.transparent,
            borderRadius: BorderRadius.circular(10),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                icon,
                size: 20,
                color: isSelected ? Colors.white : Colors.white.withOpacity(0.5),
              ),
              const SizedBox(height: 4),
              Text(
                label,
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: isSelected ? FontWeight.w600 : FontWeight.normal,
                  color: isSelected ? Colors.white : Colors.white.withOpacity(0.5),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
