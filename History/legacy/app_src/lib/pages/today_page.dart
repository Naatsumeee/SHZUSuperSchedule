import 'dart:async';

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:provider/provider.dart';

import '../models/course.dart';
import '../providers/schedule_provider.dart';
import '../theme/miui_colors.dart';
import '../theme/miui_theme.dart';
import '../widgets/course_card.dart';

/// 当日课程页
class TodayPage extends StatefulWidget {
  const TodayPage({super.key});

  @override
  State<TodayPage> createState() => _TodayPageState();
}

class _TodayPageState extends State<TodayPage> {
  bool _showTomorrow = false;
  Timer? _timer;

  static const _weekCn = ['一', '二', '三', '四', '五', '六', '日'];

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(minutes: 1), (_) => _autoSwitch());
    WidgetsBinding.instance.addPostFrameCallback((_) => _autoSwitch());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  /// 当日课上完后自动切换到明日
  void _autoSwitch() {
    if (!mounted) return;
    final p = context.read<ScheduleProvider>();
    final now = DateTime.now();
    if (!_showTomorrow && _isDayDone(p.coursesOn(now), now)) {
      setState(() => _showTomorrow = true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = context.watch<ScheduleProvider>();
    final now = DateTime.now();

    final displayDay =
        _showTomorrow ? now.add(const Duration(days: 1)) : now;
    final courses = p.coursesOn(displayDay);

    return RefreshIndicator(
      onRefresh: () => p.refresh(force: true),
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          // 日期头部
          Container(
            padding: const EdgeInsets.symmetric(vertical: 20, horizontal: 20),
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                colors: [Color(0xFFFF8A3D), MiuiColors.primary],
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
              ),
              borderRadius: BorderRadius.circular(MiuiTheme.radiusLarge),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  DateFormat('M月d日').format(displayDay),
                  style: const TextStyle(
                      color: Colors.white,
                      fontSize: 30,
                      fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 4),
                Text(
                  '星期${_weekCn[displayDay.weekday - 1]}'
                  '${_showTomorrow ? '（明日课程）' : ''}',
                  style: const TextStyle(color: Colors.white70, fontSize: 16),
                ),
                const SizedBox(height: 12),
                Text(
                  p.settings.slogan,
                  style: const TextStyle(
                      color: Colors.white, fontSize: 13, height: 1.4),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),

          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4),
            child: Text(
              courses.isEmpty ? '今天没有课程安排' : '共 ${courses.length} 节课',
              style:
                  const TextStyle(color: MiuiColors.textSecondary, fontSize: 13),
            ),
          ),
          const SizedBox(height: 8),

          if (courses.isEmpty)
            _buildEmpty()
          else
            ...courses.map((c) => CourseCard(course: c, now: now)),

          const SizedBox(height: 8),
          if (!_showTomorrow)
            Center(
              child: TextButton.icon(
                onPressed: () => setState(() => _showTomorrow = true),
                icon: const Icon(Icons.skip_next, size: 18),
                label: const Text('查看明日课程'),
              ),
            ),
          if (_showTomorrow)
            Center(
              child: TextButton.icon(
                onPressed: () => setState(() => _showTomorrow = false),
                icon: const Icon(Icons.skip_previous, size: 18),
                label: const Text('返回今日课程'),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildEmpty() {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 60),
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: MiuiColors.card,
        borderRadius: BorderRadius.circular(MiuiTheme.radius),
      ),
      child: const Column(
        children: [
          Icon(Icons.beach_access, size: 48, color: MiuiColors.textSecondary),
          SizedBox(height: 12),
          Text('今天没有课，好好休息吧',
              style: TextStyle(color: MiuiColors.textSecondary)),
        ],
      ),
    );
  }

  /// 当天有课且当前时间已过最后一节课结束时间
  bool _isDayDone(List<Course> courses, DateTime now) {
    if (courses.isEmpty) return false;
    final nowMin = now.hour * 60 + now.minute;
    int lastEnd = 0;
    for (final c in courses) {
      final m = RegExp(r'(\d{1,2}):(\d{2})-(\d{1,2}):(\d{2})')
          .firstMatch(c.timeRange);
      if (m != null) {
        final end = int.parse(m.group(3)!) * 60 + int.parse(m.group(4)!);
        if (end > lastEnd) lastEnd = end;
      }
    }
    return nowMin > lastEnd;
  }
}
