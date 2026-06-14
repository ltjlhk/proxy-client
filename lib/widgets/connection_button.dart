import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../providers/vpn_provider.dart';

class ConnectionButton extends StatefulWidget {
  const ConnectionButton({super.key});

  @override
  State<ConnectionButton> createState() => _ConnectionButtonState();
}

class _ConnectionButtonState extends State<ConnectionButton>
    with SingleTickerProviderStateMixin {
  late AnimationController _pulseController;
  late Animation<double> _pulseAnimation;

  @override
  void initState() {
    super.initState();
    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1500),
    );
    _pulseAnimation = Tween<double>(begin: 1.0, end: 1.15).animate(
      CurvedAnimation(
        parent: _pulseController,
        curve: Curves.easeInOut,
      ),
    );
    _pulseController.repeat(reverse: true);
  }

  @override
  void dispose() {
    _pulseController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Consumer<VpnProvider>(
      builder: (context, vpn, child) {
        if (!vpn.isConnected) {
          _pulseController.stop();
          _pulseController.reset();
        } else {
          _pulseController.repeat(reverse: true);
        }

        return GestureDetector(
          onTap: vpn.toggleConnection,
          child: Center(
            child: Column(
              children: [
                AnimatedBuilder(
                  animation: _pulseAnimation,
                  builder: (context, child) {
                    return Transform.scale(
                      scale: vpn.isConnected ? _pulseAnimation.value : 1.0,
                      child: Container(
                        width: 180,
                        height: 180,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          gradient: RadialGradient(
                            colors: vpn.isConnected
                                ? [
                                    const Color(0xFF00D26A).withOpacity(0.3),
                                    const Color(0xFF00D26A).withOpacity(0.1),
                                    Colors.transparent,
                                  ]
                                : [
                                    const Color(0xFF666666).withOpacity(0.2),
                                    Colors.transparent,
                                  ],
                            stops: const [0.0, 0.6, 1.0],
                          ),
                        ),
                        child: Center(
                          child: Container(
                            width: 140,
                            height: 140,
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              gradient: LinearGradient(
                                begin: Alignment.topLeft,
                                end: Alignment.bottomRight,
                                colors: vpn.isConnected
                                    ? [
                                        const Color(0xFF00D26A),
                                        const Color(0xFF00B894),
                                      ]
                                    : [
                                        const Color(0xFF3A3A3A),
                                        const Color(0xFF2A2A2A),
                                      ],
                              ),
                              boxShadow: vpn.isConnected
                                  ? [
                                      BoxShadow(
                                        color: const Color(0xFF00D26A)
                                            .withOpacity(0.4),
                                        blurRadius: 30,
                                        spreadRadius: 5,
                                      ),
                                    ]
                                  : [
                                      BoxShadow(
                                        color: Colors.black.withOpacity(0.3),
                                        blurRadius: 20,
                                        spreadRadius: 2,
                                      ),
                                    ],
                            ),
                            child: Center(
                              child: Column(
                                mainAxisAlignment: MainAxisAlignment.center,
                                children: [
                                  Icon(
                                    vpn.isConnected
                                        ? Icons.power_settings_new
                                        : Icons.power_settings_new_outlined,
                                    size: 48,
                                    color: vpn.isConnected
                                        ? Colors.white
                                        : Colors.white.withOpacity(0.5),
                                  ),
                                  const SizedBox(height: 8),
                                  Text(
                                    vpn.isConnecting
                                        ? '连接中...'
                                        : vpn.isConnected
                                            ? '断开'
                                            : '连接',
                                    style: TextStyle(
                                      fontSize: 16,
                                      fontWeight: FontWeight.w600,
                                      color: vpn.isConnected
                                          ? Colors.white
                                          : Colors.white.withOpacity(0.5),
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 20),
                Text(
                  vpn.isConnected
                      ? '已连接 - 安全浏览中'
                      : vpn.isConnecting
                          ? '正在建立连接...'
                          : '点击连接',
                  style: TextStyle(
                    fontSize: 15,
                    color: vpn.isConnected
                        ? const Color(0xFF00D26A)
                        : Colors.white.withOpacity(0.4),
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}
