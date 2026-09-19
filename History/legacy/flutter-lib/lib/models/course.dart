/// 课程模型
class Course {
  final String name; // 课程名称
  final String teacher; // 教师
  final String location; // 地点
  final String building; // 教学楼
  final int weekday; // 星期几 1-7
  final String sectionName; // 节次名，如"第一二节"
  final String sections; // 小节，如"01,02小节"
  final String timeRange; // 时间段，如"10:00-11:40"
  final String weeks; // 周次，如"1-12(周)"
  final String clazz; // 班级
  final String remark; // 备注

  const Course({
    required this.name,
    required this.teacher,
    required this.location,
    required this.building,
    required this.weekday,
    required this.sectionName,
    required this.sections,
    required this.timeRange,
    required this.weeks,
    required this.clazz,
    required this.remark,
  });

  factory Course.fromJson(Map<String, dynamic> j) => Course(
        name: j['name'] ?? '',
        teacher: j['teacher'] ?? '',
        location: j['location'] ?? '',
        building: j['building'] ?? '',
        weekday: j['weekday'] ?? 1,
        sectionName: j['sectionName'] ?? '',
        sections: j['sections'] ?? '',
        timeRange: j['timeRange'] ?? '',
        weeks: j['weeks'] ?? '',
        clazz: j['clazz'] ?? '',
        remark: j['remark'] ?? '',
      );

  Map<String, dynamic> toJson() => {
        'name': name,
        'teacher': teacher,
        'location': location,
        'building': building,
        'weekday': weekday,
        'sectionName': sectionName,
        'sections': sections,
        'timeRange': timeRange,
        'weeks': weeks,
        'clazz': clazz,
        'remark': remark,
      };

  /// 周次是否为单/双周类型：返回集合，空表示全周
  List<int> parseWeeks() {
    // 形如 "1-12(周)" "1-16(单周)" "2-16(双周)" "1,3,5(周)"
    final clean = weeks.replaceAll('(周)', '').replaceAll('(单周)', '').replaceAll('(双周)', '');
    final odd = weeks.contains('单周');
    final even = weeks.contains('双周');
    final result = <int>[];
    final parts = clean.split(',');
    for (var p in parts) {
      p = p.trim();
      if (p.contains('-')) {
        final se = p.split('-');
        final s = int.tryParse(se[0]) ?? 1;
        final e = int.tryParse(se[1]) ?? 1;
        for (var w = s; w <= e; w++) {
          result.add(w);
        }
      } else {
        final v = int.tryParse(p);
        if (v != null) result.add(v);
      }
    }
    if (odd) return result.where((w) => w.isOdd).toList();
    if (even) return result.where((w) => w.isEven).toList();
    return result;
  }

  /// 判断某周是否上课
  bool activeInWeek(int week) {
    final ws = parseWeeks();
    if (ws.isEmpty) return true;
    return ws.contains(week);
  }

  /// 解析起始时间（分钟，从 00:00 起），用于提醒排序
  int startMinutes() {
    final m = RegExp(r'(\d{1,2}):(\d{2})').firstMatch(timeRange);
    if (m == null) return 0;
    return (int.parse(m.group(1)!) * 60) + int.parse(m.group(2)!);
  }
}
