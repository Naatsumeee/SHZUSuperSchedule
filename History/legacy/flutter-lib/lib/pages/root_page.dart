import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../providers/schedule_provider.dart';
import 'home_page.dart';
import 'login_page.dart';

/// 根页面：根据登录状态切换
class RootPage extends StatelessWidget {
  const RootPage({super.key});

  @override
  Widget build(BuildContext context) {
    final p = context.watch<ScheduleProvider>();
    if (p.loggedIn) {
      return const HomePage();
    }
    return const LoginPage();
  }
}
