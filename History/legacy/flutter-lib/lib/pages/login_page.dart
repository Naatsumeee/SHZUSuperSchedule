import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../providers/schedule_provider.dart';
import '../theme/miui_colors.dart';
import '../theme/miui_theme.dart';

class LoginPage extends StatefulWidget {
  const LoginPage({super.key});

  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final _userCtrl = TextEditingController();
  final _pwdCtrl = TextEditingController();
  final _smsCtrl = TextEditingController();
  bool _obscure = true;
  bool _loggingIn = false;
  bool _submittingSms = false;

  @override
  void initState() {
    super.initState();
    final p = context.read<ScheduleProvider>();
    _userCtrl.text = p.settings.username;
    _pwdCtrl.text = p.settings.password;
  }

  @override
  void dispose() {
    _userCtrl.dispose();
    _pwdCtrl.dispose();
    _smsCtrl.dispose();
    super.dispose();
  }

  Future<void> _login() async {
    final p = context.read<ScheduleProvider>();
    final user = _userCtrl.text.trim();
    final pwd = _pwdCtrl.text;
    if (user.isEmpty || pwd.isEmpty) {
      _toast('请输入学号和密码');
      return;
    }
    setState(() => _loggingIn = true);
    final ok = await p.login(user, pwd);
    setState(() => _loggingIn = false);
    if (!ok && p.needMfa) {
      _toast('验证码已发送');
    } else if (!ok && p.lastError != null) {
      _toast(p.lastError!);
    }
  }

  Future<void> _submitSms() async {
    final p = context.read<ScheduleProvider>();
    final code = _smsCtrl.text.trim();
    if (code.isEmpty) {
      _toast('请输入验证码');
      return;
    }
    setState(() => _submittingSms = true);
    final ok = await p.login(p.settings.username, p.settings.password,
        smsCode: code);
    setState(() => _submittingSms = false);
    if (!ok && p.lastError != null) {
      _toast(p.lastError!);
    }
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    final p = context.watch<ScheduleProvider>();
    return Scaffold(
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 32),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SizedBox(height: 72),
              // Logo
              Container(
                width: 88,
                height: 88,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: MiuiColors.primary,
                  borderRadius: BorderRadius.circular(24),
                ),
                child: const Text('石大',
                    style: TextStyle(
                        color: Colors.white,
                        fontSize: 28,
                        fontWeight: FontWeight.bold)),
              ),
              const SizedBox(height: 20),
              const Text('石大课表',
                  textAlign: TextAlign.center,
                  style: TextStyle(fontSize: 24, fontWeight: FontWeight.w600)),
              const SizedBox(height: 8),
              Text(p.settings.slogan,
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                      color: MiuiColors.textSecondary, fontSize: 14)),
              const SizedBox(height: 48),

              if (!p.needMfa) ...[
                _buildField(
                  controller: _userCtrl,
                  hint: '请输入学号',
                  icon: Icons.person_outline,
                  obscure: false,
                ),
                const SizedBox(height: 16),
                _buildField(
                  controller: _pwdCtrl,
                  hint: '请输入密码',
                  icon: Icons.lock_outline,
                  obscure: _obscure,
                  suffix: IconButton(
                    icon: Icon(
                        _obscure ? Icons.visibility_off : Icons.visibility,
                        size: 20,
                        color: MiuiColors.textSecondary),
                    onPressed: () => setState(() => _obscure = !_obscure),
                  ),
                ),
                const SizedBox(height: 32),
                _buildButton(
                  text: _loggingIn ? '登录中...' : '登录',
                  loading: _loggingIn,
                  onTap: _login,
                ),
              ] else ...[
                // 短信二次认证
                Container(
                  padding: const EdgeInsets.all(20),
                  decoration: BoxDecoration(
                    color: MiuiColors.card,
                    borderRadius: BorderRadius.circular(MiuiTheme.radius),
                  ),
                  child: Column(
                    children: [
                      const Icon(Icons.sms_outlined,
                          color: MiuiColors.primary, size: 36),
                      const SizedBox(height: 12),
                      Text('验证码已发送至 ${p.mfaPhone}',
                          style: const TextStyle(fontSize: 14)),
                      const SizedBox(height: 20),
                      TextField(
                        controller: _smsCtrl,
                        keyboardType: TextInputType.number,
                        maxLength: 10,
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                            fontSize: 22, letterSpacing: 8),
                        decoration: InputDecoration(
                          hintText: '请输入短信验证码',
                          counterText: '',
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(12),
                          ),
                        ),
                      ),
                      const SizedBox(height: 16),
                      _buildButton(
                        text: _submittingSms ? '验证中...' : '完成登录',
                        loading: _submittingSms,
                        onTap: _submitSms,
                      ),
                      TextButton(
                        onPressed: () async {
                          await p.resendSms();
                          _toast('已重新发送');
                        },
                        child: const Text('重新发送验证码',
                            style: TextStyle(color: MiuiColors.primary)),
                      ),
                      TextButton(
                        onPressed: () {
                          setState(() {
                            p.needMfa = false;
                          });
                        },
                        child: const Text('返回重新输入',
                            style: TextStyle(color: MiuiColors.textSecondary)),
                      ),
                    ],
                  ),
                ),
              ],
              const SizedBox(height: 24),
              if (p.loading)
                const Center(
                    child: CircularProgressIndicator(strokeWidth: 2)),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildField({
    required TextEditingController controller,
    required String hint,
    required IconData icon,
    required bool obscure,
    Widget? suffix,
  }) {
    return TextField(
      controller: controller,
      obscureText: obscure,
      style: const TextStyle(fontSize: 15),
      decoration: InputDecoration(
        hintText: hint,
        prefixIcon: Icon(icon, color: MiuiColors.textSecondary, size: 22),
        suffixIcon: suffix,
        filled: true,
        fillColor: MiuiColors.card,
        contentPadding: const EdgeInsets.symmetric(vertical: 16),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(MiuiTheme.radiusSmall),
          borderSide: BorderSide.none,
        ),
      ),
    );
  }

  Widget _buildButton({
    required String text,
    required bool loading,
    required VoidCallback onTap,
  }) {
    return SizedBox(
      height: 50,
      child: FilledButton(
        onPressed: loading ? null : onTap,
        style: FilledButton.styleFrom(
          backgroundColor: MiuiColors.primary,
          disabledBackgroundColor: MiuiColors.primary.withValues(alpha: 0.6),
          shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(MiuiTheme.radiusSmall)),
        ),
        child: Text(text,
            style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
      ),
    );
  }
}
