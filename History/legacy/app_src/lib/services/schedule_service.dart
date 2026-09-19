import 'package:html/parser.dart' as html_parser;

import '../models/course.dart';
import 'api_client.dart';

/// 会话过期/未登录异常
class SessionExpiredException implements Exception {
  @override
  String toString() => '登录已过期，请重新登录';
}

/// 课表抓取与解析服务（强智教务系统）
class ScheduleService {
  static const kbcxUrl = 'https://jwgl.shzu.edu.cn/jsxsd/xskb/xskb_list.do';

  final ApiClient client;

  ScheduleService(this.client);

  /// 可选学期列表
  Future<List<String>> fetchSemesters() async {
    final html = await fetchRaw('');
    final doc = html_parser.parse(html);
    final sel = doc.querySelector('select#xnxq01id');
    if (sel == null) return [];
    return sel
        .querySelectorAll('option')
        .map((o) => o.attributes['value'] ?? '')
        .where((v) => v.isNotEmpty)
        .toList();
  }

  /// 抓取课表原始 HTML
  Future<String> fetchRaw(String semester) async {
    final r = await client.dio.get(kbcxUrl, queryParameters: {
      if (semester.isNotEmpty) 'xnxq01id': semester,
    });
    // 会话失效（未登录或登录过期）
    if (r.statusCode == 401 || r.statusCode == 302) {
      throw SessionExpiredException();
    }
    return r.data.toString();
  }

  /// 当前显示的学期
  String currentSemester(String html) {
    final doc = html_parser.parse(html);
    final sel = doc.querySelector('select#xnxq01id');
    if (sel == null) return '';
    final opt = sel.querySelector('option[selected]');
    return opt?.attributes['value'] ?? '';
  }

  /// 解析课表
  List<Course> parseSchedule(String html) {
    final doc = html_parser.parse(html);
    final table = doc.querySelector('table#timetable');
    if (table == null) return [];

    final courses = <Course>[];
    const weekdays = ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期日'];

    for (final tr in table.querySelectorAll('tr')) {
      final th = tr.querySelector('th');
      if (th == null) continue;
      final head = th.text.replaceAll(RegExp(r'\s+'), ' ');
      final sectionName =
          head.split('(').first.replaceAll(RegExp(r'\s+'), '');
      final mSec = RegExp(r'\((\d+(?:,\d+)*)小节\)').firstMatch(head);
      final mTime = RegExp(r'(\d{1,2}:\d{2}-\d{1,2}:\d{2})').firstMatch(head);
      final sections = mSec?.group(1) ?? '';
      final timeRange = mTime?.group(1) ?? '';

      final tds = tr.querySelectorAll('td');
      for (var day = 1; day <= 7 && day <= tds.length; day++) {
        final td = tds[day - 1];
        final divs = td.querySelectorAll('div.kbcontent');
        for (final div in divs) {
          final inner = div.innerHtml;
          // 同格多门课用长横线分隔
          final blocks = inner.split(RegExp(r'-{5,}'));
          for (final block in blocks) {
            final course = _parseBlock(
                block, weekdays[day - 1], day, sectionName, sections, timeRange);
            if (course != null) courses.add(course);
          }
        }
      }
    }
    return courses;
  }

  Course? _parseBlock(String blockHtml, String weekday, int day,
      String sectionName, String sections, String timeRange) {
    final doc = html_parser.parse('<div>$blockHtml</div>');
    final fonts = doc.querySelectorAll('font');

    String? name;
    String teacher = '';
    String location = '';
    String building = '';
    String weekSec = '';
    String clazz = '';
    String remark = '';

    for (final f in fonts) {
      final title = f.attributes['title'] ?? '';
      final nm = f.attributes['name'] ?? '';
      final text = f.text.trim();
      if (title == '教师') {
        teacher = text;
      } else if (title == '教室') {
        location = text;
      } else if (title == '教学楼') {
        building = text.replaceAll('【', '').replaceAll('】', '');
      } else if (title == '周次(节次)') {
        weekSec = text;
      } else if (nm == 'ktmcstr') {
        clazz = text.replaceAll('班级：', '');
      } else if (nm == 'bzstr') {
        remark = text.replaceAll('备注：', '');
      } else if (title.isEmpty && text.isNotEmpty && name == null) {
        name = text;
      }
    }

    if (name == null || name.isEmpty) return null;

    final mWeek = RegExp(r'([0-9,\-]+(?:\(周\)|\(单周\)|\(双周\)))').firstMatch(weekSec);
    final weeks = mWeek?.group(1) ?? weekSec;

    return Course(
      name: name,
      teacher: teacher,
      location: location,
      building: building,
      weekday: day,
      sectionName: sectionName,
      sections: '$sections小节',
      timeRange: timeRange,
      weeks: weeks,
      clazz: clazz,
      remark: remark,
    );
  }
}
