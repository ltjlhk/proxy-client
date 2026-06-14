import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'providers/vpn_provider.dart';
import 'screens/home_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
  runApp(const ProxyClientApp());
}

class ProxyClientApp extends StatelessWidget {
  const ProxyClientApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider(
      create: (_) => VpnProvider(),
      child: MaterialApp(
        title: 'Proxy Client',
        debugShowCheckedModeBanner: false,
        theme: ThemeData.dark().copyWith(
          scaffoldBackgroundColor: const Color(0xFF0D1117),
          primaryColor: const Color(0xFF58A6FF),
          colorScheme: const ColorScheme.dark(
            primary: Color(0xFF58A6FF),
            surface: Color(0xFF161B22),
          ),
        ),
        home: const HomeScreen(),
      ),
    );
  }
}
