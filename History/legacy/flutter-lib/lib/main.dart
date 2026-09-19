import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'providers/schedule_provider.dart';
import 'theme/miui_theme.dart';
import 'pages/root_page.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const ShzuClassApp());
}

class ShzuClassApp extends StatelessWidget {
  const ShzuClassApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider(
      create: (_) => ScheduleProvider()..init(),
      child: MaterialApp(
        title: '石大课表',
        debugShowCheckedModeBanner: false,
        theme: MiuiTheme.light(),
        home: const RootPage(),
      ),
    );
  }
}
