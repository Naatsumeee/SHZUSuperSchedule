import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/app_settings.dart';
import '../providers/schedule_provider.dart';
import '../theme/miui_colors.dart';
import '../theme/miui_theme.dart';

class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  final _sloganCtrl = TextEditingController();
  final _startDateCtrl = TextEditingController();

  @override
  void initState() {
    super.initState();
    final s = context.read<ScheduleProvider>().settings;
    _sloganCtrl.text = s.slogan;
    _startDateCtrl.text = s.schoolStartDate;
  }

  @override
  void dispose() {
    _sloganCtrl.dispose();
    _startDateCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final p = context.watch<ScheduleProvider>();
    final s = p.settings;

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        _section('登录教务'),
        _card([
          _tile(
            icon: Icons.account_circle_outlined,
            title: '学号',
            trailing: Text(s.username.isEmpty ? '未登录' : s.username,
                style: const TextStyle(color: MiuiColors.textSecondary)),
          ),
          _divider(),
          _tile(
            icon: Icons.refresh,
            title: '刷新课表',
            trailing: p.loading
                ? const SizedBox(
                    width: 18, height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.chevron_right, color: MiuiColors.textSecondary),
            onTap: () => p.refresh(force: true),
          ),
          _divider(),
          _tile(
            icon: Icons.exit_to_app,
            title: '退出登录',
            titleColor: Colors.redAccent,
            onTap: () => _confirmLogout(p),
          ),
        ]),
        const SizedBox(height: 16),

        _section('自定义'),
        _card([
          _tile(
            icon: Icons.format_quote,
            title: '自定义标语',
            onTap: () => _editSlogan(s),
          ),
          _divider(),
          _sliderTile(
            title: '字体大小',
            value: s.fontSize,
            min: 9, max: 20,
            label: '${s.fontSize.round()}',
            onChanged: (v) => _update(s, s.copy()..fontSize = v),
          ),
          _divider(),
          _alignTile(s),
          _divider(),
          _sliderTile(
            title: '课程格高度',
            value: s.gridCellHeight,
            min: 48, max: 120,
            label: '${s.gridCellHeight.round()}',
            onChanged: (v) => _update(s, s.copy()..gridCellHeight = v),
          ),
          _divider(),
          _sliderTile(
            title: '课程格间隔',
            value: s.gridSpacing,
            min: 0, max: 16,
            label: '${s.gridSpacing.round()}',
            onChanged: (v) => _update(s, s.copy()..gridSpacing = v),
          ),
          _divider(),
          _tile(
            icon: Icons.switch_access_shortcut,
            title: '使用默认配色',
            trailing: Switch(
              value: s.useDefaultColors,
              onChanged: (v) => _update(s, s.copy()..useDefaultColors = v),
            ),
          ),
          if (!s.useDefaultColors) _colorGrid(s),
          _divider(),
          _tile(
            icon: Icons.visibility_outlined,
            title: '显示非本周课程',
            subtitle: '非本周课程用更低饱和度颜色显示',
            trailing: Switch(
              value: s.showOtherWeeks,
              onChanged: (v) => _update(s, s.copy()..showOtherWeeks = v),
            ),
          ),
        ]),
        const SizedBox(height: 16),

        _section('软件配置'),
        _card([
          _tile(
            icon: Icons.notifications_outlined,
            title: '上课提醒',
            subtitle: '课程开始前通知你',
            trailing: Switch(
              value: s.reminderEnabled,
              onChanged: (v) => _update(s, s.copy()..reminderEnabled = v),
            ),
          ),
          if (s.reminderEnabled) ...[
            _divider(),
            _sliderTile(
              title: '提前提醒',
              value: s.reminderAdvanceMinutes.toDouble(),
              min: 1, max: 60,
              label: '${s.reminderAdvanceMinutes} 分钟',
              onChanged: (v) => _update(
                  s, s.copy()..reminderAdvanceMinutes = v.round()),
            ),
            _divider(),
            _methodTile(s),
          ],
          _divider(),
          _tile(
            icon: Icons.sync,
            title: '自动更新',
            subtitle: '定时从教务系统刷新课表',
            trailing: Switch(
              value: s.autoUpdate,
              onChanged: (v) => _update(s, s.copy()..autoUpdate = v),
            ),
          ),
          if (s.autoUpdate) ...[
            _divider(),
            _sliderTile(
              title: '更新间隔',
              value: s.autoUpdateMinutes.toDouble(),
              min: 15, max: 720,
              label: '${s.autoUpdateMinutes} 分钟',
              onChanged: (v) =>
                  _update(s, s.copy()..autoUpdateMinutes = v.round()),
            ),
          ],
          _divider(),
          _tile(
            icon: Icons.event_available,
            title: '开学日期',
            subtitle: '第一周周一，用于计算当前教学周',
            trailing: Text(s.schoolStartDate.isEmpty ? '未设置' : s.schoolStartDate,
                style: const TextStyle(color: MiuiColors.textSecondary)),
            onTap: () => _editStartDate(s),
          ),
        ]),
        const SizedBox(height: 16),

        _section('信息'),
        _card([
          _tile(
            icon: Icons.schedule,
            title: '数据更新时间',
            trailing: Text(p.fetchTime.isEmpty ? '暂无' : p.fetchTime,
                style: const TextStyle(color: MiuiColors.textSecondary)),
          ),
          _divider(),
          _tile(
            icon: Icons.info_outline,
            title: '版本',
            trailing: const Text('v1.0.0',
                style: TextStyle(color: MiuiColors.textSecondary)),
          ),
          _divider(),
          _tile(
            icon: Icons.school_outlined,
            title: '关于',
            subtitle: '石大课表 · 石河子大学课表应用\n数据来源于学校教务系统，仅供个人使用',
            onTap: () => _showAbout(),
          ),
        ]),
        const SizedBox(height: 24),
      ],
    );
  }

  // ---------- UI 辅助 ----------

  Widget _section(String title) => Padding(
        padding: const EdgeInsets.only(left: 4, bottom: 8),
        child: Text(title,
            style: const TextStyle(
                fontSize: 13,
                color: MiuiColors.textSecondary,
                fontWeight: FontWeight.w600)),
      );

  Widget _card(List<Widget> children) => Container(
        decoration: BoxDecoration(
          color: MiuiColors.card,
          borderRadius: BorderRadius.circular(MiuiTheme.radius),
        ),
        child: Column(children: children),
      );

  Widget _tile({
    required IconData icon,
    required String title,
    String? subtitle,
    Widget? trailing,
    VoidCallback? onTap,
    Color? titleColor,
  }) {
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        child: Row(
          children: [
            Icon(icon, size: 22, color: MiuiColors.textSecondary),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title,
                      style: TextStyle(
                          fontSize: 15, color: titleColor ?? MiuiColors.textPrimary)),
                  if (subtitle != null)
                    Text(subtitle,
                        style: const TextStyle(
                            fontSize: 12, color: MiuiColors.textSecondary)),
                ],
              ),
            ),
            if (trailing != null) trailing,
          ],
        ),
      ),
    );
  }

  Widget _divider() =>
      const Divider(height: 1, indent: 50, endIndent: 16);

  Widget _sliderTile({
    required String title,
    required double value,
    required double min,
    required double max,
    required String label,
    required ValueChanged<double> onChanged,
  }) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(child: Text(title, style: const TextStyle(fontSize: 15))),
              Text(label,
                  style: const TextStyle(
                      fontSize: 13, color: MiuiColors.primary)),
            ],
          ),
          Slider(
            value: value.clamp(min, max),
            min: min,
            max: max,
            activeColor: MiuiColors.primary,
            onChanged: onChanged,
          ),
        ],
      ),
    );
  }

  Widget _alignTile(AppSettings s) {
    const opts = [
      ('居中', Icons.format_align_center, 0),
      ('居左', Icons.format_align_left, 1),
      ('分散对齐', Icons.format_align_justify, 2),
    ];
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Row(
        children: [
          const Expanded(child: Text('文字对齐', style: TextStyle(fontSize: 15))),
          SegmentedButton<int>(
            segments: [
              for (final o in opts)
                ButtonSegment(value: o.$3, label: Text(o.$1)),
            ],
            selected: {s.alignment},
            onSelectionChanged: (v) =>
                _update(s, s.copy()..alignment = v.first),
          ),
        ],
      ),
    );
  }

  Widget _methodTile(AppSettings s) {
    const opts = ['通知栏', '闹钟式', '仅震动'];
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Row(
        children: [
          const Expanded(child: Text('提醒方式', style: TextStyle(fontSize: 15))),
          DropdownButton<int>(
            value: s.reminderMethod,
            items: [
              for (var i = 0; i < opts.length; i++)
                DropdownMenuItem(value: i, child: Text(opts[i])),
            ],
            onChanged: (v) {
              if (v != null) _update(s, s.copy()..reminderMethod = v);
            },
          ),
        ],
      ),
    );
  }

  Widget _colorGrid(AppSettings s) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Wrap(
        spacing: 10,
        runSpacing: 10,
        children: [
          for (var i = 0; i < MiuiColors.coursePalette.length; i++)
            GestureDetector(
              onTap: () => _pickColor(s, i),
              child: Container(
                width: 34,
                height: 34,
                decoration: BoxDecoration(
                  color: s.customColors.isNotEmpty && i < s.customColors.length
                      ? Color(s.customColors[i])
                      : MiuiColors.coursePalette[i],
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.black12),
                ),
              ),
            ),
        ],
      ),
    );
  }

  // ---------- 交互 ----------

  Future<void> _update(AppSettings old, AppSettings next) async {
    await context.read<ScheduleProvider>().updateSettings(next);
  }

  Future<void> _editSlogan(AppSettings s) async {
    final ctrl = TextEditingController(text: s.slogan);
    final result = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('自定义标语'),
        content: TextField(controller: ctrl, maxLength: 40),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          TextButton(
              onPressed: () => Navigator.pop(ctx, ctrl.text.trim()),
              child: const Text('保存')),
        ],
      ),
    );
    if (result != null && result.isNotEmpty) {
      await _update(s, s.copy()..slogan = result);
    }
  }

  Future<void> _editStartDate(AppSettings s) async {
    final ctrl = TextEditingController(text: s.schoolStartDate);
    final result = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('开学日期（第一周周一）'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
                controller: ctrl,
                keyboardType: TextInputType.datetime,
                decoration: const InputDecoration(hintText: '如 2026-09-07')),
          ],
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          TextButton(
              onPressed: () => Navigator.pop(ctx, ctrl.text.trim()),
              child: const Text('保存')),
        ],
      ),
    );
    if (result != null) {
      await _update(s, s.copy()..schoolStartDate = result);
    }
  }

  Future<void> _pickColor(AppSettings s, int index) async {
    final selected = await showDialog<Color>(
      context: context,
      builder: (ctx) => SimpleDialog(
        title: const Text('选择颜色'),
        children: [
          Wrap(
            spacing: 10,
            runSpacing: 10,
            children: [
              for (final c in MiuiColors.coursePalette)
                GestureDetector(
                  onTap: () => Navigator.pop(ctx, c),
                  child: Container(
                    width: 40,
                    height: 40,
                    decoration: BoxDecoration(
                      color: c,
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(color: Colors.black12),
                    ),
                  ),
                ),
            ],
          ),
        ],
      ),
    );
    if (selected != null) {
      final colors = List<int>.from(s.customColors);
      while (colors.length < MiuiColors.coursePalette.length) {
        colors.add(0);
      }
      colors[index] = selected.toARGB32();
      await _update(s, s.copy()..customColors = colors);
    }
  }

  Future<void> _confirmLogout(ScheduleProvider p) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('退出登录'),
        content: const Text('退出后将清除本地缓存的账号和课表数据'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          TextButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('退出')),
        ],
      ),
    );
    if (ok == true) await p.logout();
  }

  void _showAbout() {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('关于石大课表'),
        content: const Text(
            '石大课表是一款面向石河子大学学生的课表应用。\n\n'
            '功能：登录教务系统抓取个人课表，支持今日/本周视图、上课提醒、界面自定义。\n\n'
            '数据来源：石河子大学教务一体化系统，仅供个人学习使用。'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('知道了')),
        ],
      ),
    );
  }
}
