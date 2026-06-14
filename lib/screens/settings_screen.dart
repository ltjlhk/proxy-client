import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../providers/settings_provider.dart';
import '../models/server_node.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('设置'),
      ),
      body: SafeArea(
        child: CustomScrollView(
          slivers: [
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.all(20),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _buildSectionTitle('服务器配置'),
                    const SizedBox(height: 12),
                    _buildServerConfigCard(context),
                    const SizedBox(height: 24),
                    _buildSectionTitle('节点列表'),
                    const SizedBox(height: 12),
                    _buildNodeListCard(context),
                    const SizedBox(height: 24),
                    _buildSectionTitle('关于'),
                    const SizedBox(height: 12),
                    _buildAboutCard(context),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSectionTitle(String title) {
    return Text(
      title,
      style: TextStyle(
        fontSize: 13,
        fontWeight: FontWeight.w600,
        color: Colors.white.withOpacity(0.4),
        letterSpacing: 0.5,
      ),
    );
  }

  Widget _buildServerConfigCard(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFF2A2A2A),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Consumer<SettingsProvider>(
        builder: (context, settings, child) {
          return Column(
            children: [
              _buildConfigRow(
                icon: Icons.dns,
                label: '服务器地址',
                value: settings.serverAddress,
                onTap: () => _showEditDialog(
                  context,
                  title: '服务器地址',
                  initialValue: settings.serverAddress,
                  onSave: (value) => settings.setServerAddress(value),
                ),
              ),
              const Divider(height: 24, color: Color(0xFF3A3A3A)),
              _buildConfigRow(
                icon: Icons.numbers,
                label: '端口',
                value: settings.serverPort.toString(),
                onTap: () => _showEditDialog(
                  context,
                  title: '端口',
                  initialValue: settings.serverPort.toString(),
                  keyboardType: TextInputType.number,
                  onSave: (value) {
                    final port = int.tryParse(value);
                    if (port != null) {
                      settings.setServerPort(port);
                    }
                  },
                ),
              ),
              const Divider(height: 24, color: Color(0xFF3A3A3A)),
              _buildConfigRow(
                icon: Icons.person_outline,
                label: '用户名',
                value: settings.username.isEmpty ? '未设置' : settings.username,
                onTap: () => _showEditDialog(
                  context,
                  title: '用户名',
                  initialValue: settings.username,
                  onSave: (value) => settings.setCredentials(
                    value,
                    settings.password,
                  ),
                ),
              ),
              const Divider(height: 24, color: Color(0xFF3A3A3A)),
              _buildConfigRow(
                icon: Icons.lock_outline,
                label: '密码',
                value: settings.password.isEmpty ? '未设置' : '********',
                onTap: () => _showEditDialog(
                  context,
                  title: '密码',
                  initialValue: settings.password,
                  obscureText: true,
                  onSave: (value) => settings.setCredentials(
                    settings.username,
                    value,
                  ),
                ),
              ),
            ],
          );
        },
      ),
    );
  }

  Widget _buildConfigRow({
    required IconData icon,
    required String label,
    required String value,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Row(
        children: [
          Container(
            width: 36,
            height: 36,
            decoration: BoxDecoration(
              color: const Color(0xFF00D26A).withOpacity(0.1),
              borderRadius: BorderRadius.circular(10),
            ),
            child: Icon(
              icon,
              size: 18,
              color: const Color(0xFF00D26A),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  label,
                  style: TextStyle(
                    fontSize: 12,
                    color: Colors.white.withOpacity(0.5),
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  value,
                  style: const TextStyle(
                    fontSize: 14,
                    color: Colors.white,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ],
            ),
          ),
          Icon(
            Icons.chevron_right,
            size: 20,
            color: Colors.white.withOpacity(0.3),
          ),
        ],
      ),
    );
  }

  Widget _buildNodeListCard(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: const Color(0xFF2A2A2A),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Consumer<SettingsProvider>(
        builder: (context, settings, child) {
          return Column(
            children: [
              ...settings.serverNodes.asMap().entries.map((entry) {
                final index = entry.key;
                final node = entry.value;
                return Column(
                  children: [
                    if (index > 0)
                      const Divider(height: 1, color: Color(0xFF3A3A3A)),
                    _buildNodeItem(context, node, settings),
                  ],
                );
              }),
              const Divider(height: 1, color: Color(0xFF3A3A3A)),
              GestureDetector(
                onTap: () => _showAddNodeDialog(context, settings),
                child: Container(
                  padding: const EdgeInsets.all(16),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(
                        Icons.add_circle_outline,
                        size: 20,
                        color: const Color(0xFF00D26A),
                      ),
                      const SizedBox(width: 8),
                      const Text(
                        '添加节点',
                        style: TextStyle(
                          fontSize: 14,
                          color: Color(0xFF00D26A),
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          );
        },
      ),
    );
  }

  Widget _buildNodeItem(BuildContext context, ServerNode node, SettingsProvider settings) {
    final isSelected = node.isActive;

    return GestureDetector(
      onTap: () => settings.selectNode(node.id),
      child: Container(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                color: isSelected
                    ? const Color(0xFF00D26A).withOpacity(0.15)
                    : const Color(0xFF3A3A3A),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Center(
                child: Icon(
                  Icons.router,
                  size: 20,
                  color: isSelected
                      ? const Color(0xFF00D26A)
                      : Colors.white.withOpacity(0.5),
                ),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Text(
                        node.name,
                        style: TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                          color: isSelected
                              ? const Color(0xFF00D26A)
                              : Colors.white,
                        ),
                      ),
                      if (isSelected) ...[
                        const SizedBox(width: 8),
                        Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 6,
                            vertical: 2,
                          ),
                          decoration: BoxDecoration(
                            color: const Color(0xFF00D26A).withOpacity(0.15),
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: const Text(
                            '当前',
                            style: TextStyle(
                              fontSize: 10,
                              color: Color(0xFF00D26A),
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                      ],
                    ],
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '${node.address}:${node.port}',
                    style: TextStyle(
                      fontSize: 12,
                      color: Colors.white.withOpacity(0.4),
                    ),
                  ),
                ],
              ),
            ),
            Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                if (node.latency > 0)
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 3,
                    ),
                    decoration: BoxDecoration(
                      color: node.latency < 50
                          ? const Color(0xFF00D26A).withOpacity(0.15)
                          : node.latency < 100
                              ? const Color(0xFFFFA500).withOpacity(0.15)
                              : const Color(0xFFFF4444).withOpacity(0.15),
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Text(
                      '${node.latency}ms',
                      style: TextStyle(
                        fontSize: 11,
                        fontWeight: FontWeight.w600,
                        color: node.latency < 50
                            ? const Color(0xFF00D26A)
                            : node.latency < 100
                                ? const Color(0xFFFFA500)
                                : const Color(0xFFFF4444),
                      ),
                    ),
                  )
                else if (node.latency == -1)
                  SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      valueColor: AlwaysStoppedAnimation<Color>(
                        Colors.white.withOpacity(0.5),
                      ),
                    ),
                  ),
                const SizedBox(height: 8),
                Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    GestureDetector(
                      onTap: () => settings.testNodeLatency(node.id),
                      child: Icon(
                        Icons.network_ping,
                        size: 18,
                        color: Colors.white.withOpacity(0.4),
                      ),
                    ),
                    const SizedBox(width: 12),
                    GestureDetector(
                      onTap: () => _showEditNodeDialog(context, settings, node),
                      child: Icon(
                        Icons.edit,
                        size: 18,
                        color: Colors.white.withOpacity(0.4),
                      ),
                    ),
                    const SizedBox(width: 12),
                    GestureDetector(
                      onTap: () => _showDeleteConfirm(context, settings, node),
                      child: Icon(
                        Icons.delete_outline,
                        size: 18,
                        color: const Color(0xFFFF4444).withOpacity(0.7),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildAboutCard(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFF2A2A2A),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        children: [
          _buildAboutRow('版本', '1.0.0'),
          const Divider(height: 24, color: Color(0xFF3A3A3A)),
          _buildAboutRow('协议支持', 'SOCKS5'),
          const Divider(height: 24, color: Color(0xFF3A3A3A)),
          _buildAboutRow('开发者', 'Proxy Client Team'),
        ],
      ),
    );
  }

  Widget _buildAboutRow(String label, String value) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(
          label,
          style: TextStyle(
            fontSize: 14,
            color: Colors.white.withOpacity(0.6),
          ),
        ),
        Text(
          value,
          style: const TextStyle(
            fontSize: 14,
            color: Colors.white,
            fontWeight: FontWeight.w500,
          ),
        ),
      ],
    );
  }

  void _showEditDialog(
    BuildContext context, {
    required String title,
    required String initialValue,
    required Function(String) onSave,
    TextInputType keyboardType = TextInputType.text,
    bool obscureText = false,
  }) {
    final controller = TextEditingController(text: initialValue);

    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: const Color(0xFF2A2A2A),
        title: Text(
          title,
          style: const TextStyle(
            color: Colors.white,
            fontSize: 16,
            fontWeight: FontWeight.w600,
          ),
        ),
        content: TextField(
          controller: controller,
          keyboardType: keyboardType,
          obscureText: obscureText,
          style: const TextStyle(color: Colors.white),
          decoration: InputDecoration(
            filled: true,
            fillColor: const Color(0xFF3A3A3A),
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(10),
              borderSide: BorderSide.none,
            ),
            contentPadding: const EdgeInsets.symmetric(
              horizontal: 16,
              vertical: 14,
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text(
              '取消',
              style: TextStyle(
                color: Colors.white.withOpacity(0.6),
              ),
            ),
          ),
          TextButton(
            onPressed: () {
              onSave(controller.text);
              Navigator.pop(context);
            },
            child: const Text(
              '保存',
              style: TextStyle(
                color: Color(0xFF00D26A),
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
    );
  }

  void _showAddNodeDialog(BuildContext context, SettingsProvider settings) {
    final nameController = TextEditingController();
    final addressController = TextEditingController(text: '47.80.241.156');
    final portController = TextEditingController(text: '7890');

    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: const Color(0xFF2A2A2A),
        title: const Text(
          '添加节点',
          style: TextStyle(
            color: Colors.white,
            fontSize: 16,
            fontWeight: FontWeight.w600,
          ),
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            _buildDialogTextField('节点名称', nameController),
            const SizedBox(height: 12),
            _buildDialogTextField('服务器地址', addressController),
            const SizedBox(height: 12),
            _buildDialogTextField(
              '端口',
              portController,
              keyboardType: TextInputType.number,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text(
              '取消',
              style: TextStyle(
                color: Colors.white.withOpacity(0.6),
              ),
            ),
          ),
          TextButton(
            onPressed: () {
              final port = int.tryParse(portController.text) ?? 7890;
              final node = ServerNode(
                id: DateTime.now().millisecondsSinceEpoch.toString(),
                name: nameController.text.isEmpty
                    ? '新节点'
                    : nameController.text,
                address: addressController.text,
                port: port,
              );
              settings.addNode(node);
              Navigator.pop(context);
            },
            child: const Text(
              '添加',
              style: TextStyle(
                color: Color(0xFF00D26A),
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
    );
  }

  void _showEditNodeDialog(
    BuildContext context,
    SettingsProvider settings,
    ServerNode node,
  ) {
    final nameController = TextEditingController(text: node.name);
    final addressController = TextEditingController(text: node.address);
    final portController = TextEditingController(text: node.port.toString());

    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: const Color(0xFF2A2A2A),
        title: const Text(
          '编辑节点',
          style: TextStyle(
            color: Colors.white,
            fontSize: 16,
            fontWeight: FontWeight.w600,
          ),
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            _buildDialogTextField('节点名称', nameController),
            const SizedBox(height: 12),
            _buildDialogTextField('服务器地址', addressController),
            const SizedBox(height: 12),
            _buildDialogTextField(
              '端口',
              portController,
              keyboardType: TextInputType.number,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text(
              '取消',
              style: TextStyle(
                color: Colors.white.withOpacity(0.6),
              ),
            ),
          ),
          TextButton(
            onPressed: () {
              final port = int.tryParse(portController.text) ?? node.port;
              final updatedNode = node.copyWith(
                name: nameController.text,
                address: addressController.text,
                port: port,
              );
              settings.updateNode(updatedNode);
              Navigator.pop(context);
            },
            child: const Text(
              '保存',
              style: TextStyle(
                color: Color(0xFF00D26A),
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildDialogTextField(
    String label,
    TextEditingController controller, {
    TextInputType keyboardType = TextInputType.text,
  }) {
    return TextField(
      controller: controller,
      keyboardType: keyboardType,
      style: const TextStyle(color: Colors.white),
      decoration: InputDecoration(
        labelText: label,
        labelStyle: TextStyle(
          color: Colors.white.withOpacity(0.5),
          fontSize: 13,
        ),
        filled: true,
        fillColor: const Color(0xFF3A3A3A),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: BorderSide.none,
        ),
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 16,
          vertical: 14,
        ),
      ),
    );
  }

  void _showDeleteConfirm(
    BuildContext context,
    SettingsProvider settings,
    ServerNode node,
  ) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: const Color(0xFF2A2A2A),
        title: const Text(
          '删除节点',
          style: TextStyle(
            color: Colors.white,
            fontSize: 16,
            fontWeight: FontWeight.w600,
          ),
        ),
        content: Text(
          '确定要删除节点 "${node.name}" 吗？',
          style: TextStyle(
            color: Colors.white.withOpacity(0.7),
            fontSize: 14,
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text(
              '取消',
              style: TextStyle(
                color: Colors.white.withOpacity(0.6),
              ),
            ),
          ),
          TextButton(
            onPressed: () {
              settings.removeNode(node.id);
              Navigator.pop(context);
            },
            child: const Text(
              '删除',
              style: TextStyle(
                color: Color(0xFFFF4444),
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
