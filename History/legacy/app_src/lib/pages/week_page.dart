import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/course.dart';
import '../providers/schedule_provider.dart';
import '../theme/miui_colors.dart';

/// 本周课表页：左右滑动切换教学周，网格可高度定制
class WeekPage extends StatefulWidget {
  const WeekPage({super.key});

  @override
  State<WeekPage> createState() => _WeekPageState();
}

class _WeekPageState extends State<WeekPage> {
  static const _weekCn = ['一', '二', '三', '四', '五', '六', '日'];
  late PageController _controller;

  @override
  void initState() {
    super.initState();
    _controller = PageController(initialPage: _baseIndex);
  }

  static const _baseIndex = 500;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final p = context.watch<ScheduleProvider>();
    final currentWeek = p.currentWeekNumber(DateTime.now());
    final sections = _extractSections(p.courses);

    return Column(
      children: [
        // 周选择栏
        _buildWeekBar(context, p, currentWeek),
        const Divider(),
        // 星期表头
        _buildWeekdayHeader(context),
        // 课表网格（PageView 横向滑动）
        Expanded(
          child: PageView.builder(
            controller: _controller,
            itemCount: _baseIndex * 2 + 1,
            onPageChanged: (i) => setState(() {}),
            itemBuilder: (context, index) {
              final week = currentWeek + (index - _baseIndex);
              return _buildGrid(context, p, sections, week);
            },
          ),
        ),
      ],
    );
  }

  Widget _buildWeekBar(
      BuildContext context, ScheduleProvider p, int currentWeek) {
    final week = currentWeek + (_controller.hasClients
        ? _controller.page!.round() - _baseIndex
        : 0);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Row(
        children: [
          IconButton(
            icon: const Icon(Icons.chevron_left),
            onPressed: () => _controller.previousPage(
                duration: const Duration(milliseconds: 250),
                curve: Curves.easeOut),
          ),
          Expanded(
            child: Column(
              children: [
                Text(
                  week == currentWeek ? '第 $week 周（本周）' : '第 $week 周',
                  style: const TextStyle(
                      fontSize: 16, fontWeight: FontWeight.w600),
                ),
                if (p.semester.isNotEmpty)
                  Text(p.semester,
                      style: const TextStyle(
                          fontSize: 12, color: MiuiColors.textSecondary)),
              ],
            ),
          ),
          IconButton(
            icon: const Icon(Icons.chevron_right),
            onPressed: () => _controller.nextPage(
                duration: const Duration(milliseconds: 250),
                curve: Curves.easeOut),
          ),
        ],
      ),
    );
  }

  Widget _buildWeekdayHeader(BuildContext context) {
    return Container(
      color: MiuiColors.card,
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          const SizedBox(width: 52),
          for (var i = 0; i < 7; i++)
            Expanded(
              child: Center(
                child: Text(
                  '周${_weekCn[i]}',
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: i >= 5 ? MiuiColors.primary : MiuiColors.textPrimary,
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildGrid(BuildContext context, ScheduleProvider p,
      List<_Section> sections, int week) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(8, 8, 8, 16),
      child: Column(
        children: [
          for (final sec in sections)
            _buildRow(context, p, sec, week),
        ],
      ),
    );
  }

  Widget _buildRow(
      BuildContext context, ScheduleProvider p, _Section sec, int week) {
    return Padding(
      padding: EdgeInsets.symmetric(vertical: p.settings.gridSpacing / 2),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // 节次标签
          SizedBox(
            width: 52,
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                FittedBox(
                  fit: BoxFit.scaleDown,
                  child: Text(sec.shortName,
                      style: const TextStyle(
                          fontSize: 11, color: MiuiColors.textSecondary)),
                ),
                FittedBox(
                  fit: BoxFit.scaleDown,
                  child: Text(sec.timeRange,
                      style: const TextStyle(
                          fontSize: 9, color: MiuiColors.textSecondary)),
                ),
              ],
            ),
          ),
          const SizedBox(width: 4),
          // 7 天
          for (var day = 1; day <= 7; day++) ...[
            Expanded(
              child: _buildCell(context, p, sec, day, week),
            ),
            if (day != 7) SizedBox(width: p.settings.gridSpacing),
          ],
        ],
      ),
    );
  }

  Widget _buildCell(BuildContext context, ScheduleProvider p, _Section sec,
      int day, int week) {
    final courses = p.courses
        .where((c) => c.weekday == day && c.startMinutes() == sec.startMinutes)
        .toList();

    // 本周无课
    if (courses.isEmpty) {
      return Container(
        height: p.settings.gridCellHeight,
        decoration: BoxDecoration(
          color: MiuiColors.card,
          borderRadius: BorderRadius.circular(6),
        ),
      );
    }

    final active = courses.any((c) => c.activeInWeek(week));
    final showDimmed = !active && p.settings.showOtherWeeks;
    if (!active && !showDimmed) {
      return Container(
        height: p.settings.gridCellHeight,
        decoration: BoxDecoration(
          color: MiuiColors.card,
          borderRadius: BorderRadius.circular(6),
        ),
      );
    }

    // 取该格显示的主课程（取第一门 active，否则第一门）
    final c = courses.firstWhere((e) => e.activeInWeek(week),
        orElse: () => courses.first);
    final isThisWeek = c.activeInWeek(week);
    final color = p.settings.useDefaultColors
        ? (isThisWeek
            ? MiuiColors.courseColor(c.name)
            : MiuiColors.pastelColor(c.name))
        : _customColor(p, c, isThisWeek);

    final align = _crossAlign(p.settings.alignment);
    final mainAlign = _mainAlign(p.settings.alignment);

    return Container(
      height: p.settings.gridCellHeight,
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Column(
        mainAxisAlignment: mainAlign,
        crossAxisAlignment: align,
        children: [
          Text(
            c.name,
            textAlign: _textAlign(p.settings.alignment),
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontSize: p.settings.fontSize,
              fontWeight: FontWeight.w600,
              color: Colors.black87,
              height: 1.15,
            ),
          ),
          if (c.location.isNotEmpty)
            Text(
              c.location,
              textAlign: _textAlign(p.settings.alignment),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: (p.settings.fontSize - 2).clamp(8, 12),
                color: Colors.black54,
              ),
            ),
          if (c.teacher.isNotEmpty && p.settings.gridCellHeight > 70)
            Text(
              c.teacher,
              textAlign: _textAlign(p.settings.alignment),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: (p.settings.fontSize - 3).clamp(7, 10),
                color: Colors.black45,
              ),
            ),
        ],
      ),
    );
  }

  Color _customColor(ScheduleProvider p, Course c, bool isThisWeek) {
    final idx = MiuiColors.colorIndex(c.name);
    if (p.settings.customColors.isNotEmpty &&
        idx < p.settings.customColors.length) {
      var color = Color(p.settings.customColors[idx]);
      if (!isThisWeek) {
        color = Color.lerp(color, const Color(0xFFE0E0E0), 0.55)!;
      }
      return color;
    }
    return isThisWeek
        ? MiuiColors.courseColor(c.name)
        : MiuiColors.pastelColor(c.name);
  }

  CrossAxisAlignment _crossAlign(int a) => switch (a) {
        1 => CrossAxisAlignment.start,
        2 => CrossAxisAlignment.stretch,
        _ => CrossAxisAlignment.center,
      };

  MainAxisAlignment _mainAlign(int a) => switch (a) {
        1 => MainAxisAlignment.start,
        2 => MainAxisAlignment.spaceBetween,
        _ => MainAxisAlignment.center,
      };

  TextAlign _textAlign(int a) => switch (a) {
        1 => TextAlign.left,
        2 => TextAlign.justify,
        _ => TextAlign.center,
      };

  List<_Section> _extractSections(List<Course> courses) {
    final map = <int, _Section>{};
    for (final c in courses) {
      final start = c.startMinutes();
      map.putIfAbsent(start,
          () => _Section(c.sectionName, c.timeRange, start));
    }
    final list = map.values.toList()..sort((a, b) => a.startMinutes - b.startMinutes);
    return list;
  }
}

class _Section {
  final String name;
  final String timeRange;
  final int startMinutes;
  _Section(this.name, this.timeRange, this.startMinutes);

  /// 简短的节次标签，如 "一二节"
  String get shortName {
    final cn = ['零', '一', '二', '三', '四', '五', '六', '七', '八', '九', '十'];
    final m = RegExp(r'第(.*?)节').firstMatch(name);
    if (m != null) {
      var s = m.group(1)!;
      if (s.length <= 4) return '$s节';
      var out = '';
      for (final ch in s.runes) {
        final idx = cn.indexOf(String.fromCharCode(ch));
        out += idx >= 0 ? '$idx' : '';
      }
      if (out.isNotEmpty) return '$out节';
    }
    return name;
  }
}
