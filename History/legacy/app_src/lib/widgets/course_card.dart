import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/course.dart';
import '../providers/schedule_provider.dart';
import '../theme/miui_colors.dart';
import '../theme/miui_theme.dart';

/// 课程卡片
class CourseCard extends StatelessWidget {
  final Course course;
  final DateTime now;

  const CourseCard({super.key, required this.course, required this.now});

  @override
  Widget build(BuildContext context) {
    final p = context.watch<ScheduleProvider>();
    final isThisWeek = course.activeInWeek(p.currentWeekNumber(now));
    final color = p.settings.useDefaultColors
        ? (isThisWeek
            ? MiuiColors.courseColor(course.name)
            : MiuiColors.pastelColor(course.name))
        : _customColor(p, isThisWeek);

    final status = _status(now);

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: MiuiColors.card,
        borderRadius: BorderRadius.circular(MiuiTheme.radius),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // 左侧色条
          Container(
            width: 5,
            decoration: BoxDecoration(
              color: color,
              borderRadius: const BorderRadius.horizontal(
                  left: Radius.circular(MiuiTheme.radius)),
            ),
          ),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(course.name,
                            style: const TextStyle(
                                fontSize: 16, fontWeight: FontWeight.w600)),
                      ),
                      _statusChip(status),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Icon(Icons.schedule,
                          size: 15, color: MiuiColors.textSecondary),
                      const SizedBox(width: 4),
                      Text(
                        '${course.sectionName} ${course.timeRange}',
                        style: const TextStyle(
                            fontSize: 13, color: MiuiColors.textSecondary),
                      ),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Row(
                    children: [
                      Icon(Icons.location_on_outlined,
                          size: 15, color: MiuiColors.textSecondary),
                      const SizedBox(width: 4),
                      Expanded(
                        child: Text(
                          course.location.isEmpty ? '待定' : course.location,
                          style: const TextStyle(
                              fontSize: 13, color: MiuiColors.textSecondary),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Row(
                    children: [
                      Icon(Icons.person_outline,
                          size: 15, color: MiuiColors.textSecondary),
                      const SizedBox(width: 4),
                      Expanded(
                        child: Text(
                          course.teacher.isEmpty ? '未知教师' : course.teacher,
                          style: const TextStyle(
                              fontSize: 13, color: MiuiColors.textSecondary),
                        ),
                      ),
                    ],
                  ),
                  if (course.weeks.isNotEmpty) ...[
                    const SizedBox(height: 4),
                    Row(
                      children: [
                        Icon(Icons.event_note,
                            size: 15, color: MiuiColors.textSecondary),
                        const SizedBox(width: 4),
                        Text('第 ${course.weeks}',
                            style: const TextStyle(
                                fontSize: 12, color: MiuiColors.textSecondary)),
                      ],
                    ),
                  ],
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Color _customColor(ScheduleProvider p, bool isThisWeek) {
    final idx = MiuiColors.colorIndex(course.name);
    if (p.settings.customColors.isNotEmpty &&
        idx < p.settings.customColors.length) {
      var c = Color(p.settings.customColors[idx]);
      if (!isThisWeek) {
        // 非本周降低饱和度（混入灰色）
        c = Color.lerp(c, const Color(0xFFE0E0E0), 0.55)!;
      }
      return c;
    }
    return isThisWeek
        ? MiuiColors.courseColor(course.name)
        : MiuiColors.pastelColor(course.name);
  }

  int _status(DateTime now) {
    final m = RegExp(r'(\d{1,2}):(\d{2})-(\d{1,2}):(\d{2})')
        .firstMatch(course.timeRange);
    if (m == null) return 0;
    final nowMin = now.hour * 60 + now.minute;
    final start = int.parse(m.group(1)!) * 60 + int.parse(m.group(2)!);
    final end = int.parse(m.group(3)!) * 60 + int.parse(m.group(4)!);
    if (nowMin < start) return 0; // 未开始
    if (nowMin <= end) return 1; // 进行中
    return 2; // 已结束
  }

  Widget _statusChip(int status) {
    if (status == 1) {
      return Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
        decoration: BoxDecoration(
          color: const Color(0xFFE8F5E9),
          borderRadius: BorderRadius.circular(10),
        ),
        child: const Text('进行中',
            style: TextStyle(fontSize: 11, color: Color(0xFF43A047))),
      );
    }
    if (status == 2) {
      return Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
        decoration: BoxDecoration(
          color: MiuiColors.divider,
          borderRadius: BorderRadius.circular(10),
        ),
        child: const Text('已结束',
            style: TextStyle(fontSize: 11, color: MiuiColors.textSecondary)),
      );
    }
    return const SizedBox.shrink();
  }
}
