import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter_timezone/flutter_timezone.dart';
import 'package:timezone/data/latest_all.dart' as tz;
import 'package:timezone/timezone.dart' as tz;

import '../models/app_settings.dart';
import '../models/course.dart';

/// 上课提醒服务
class NotificationService {
  final FlutterLocalNotificationsPlugin _plugin =
      FlutterLocalNotificationsPlugin();
  bool _ready = false;

  Future<void> init() async {
    if (_ready) return;
    tz.initializeTimeZones();
    try {
      final name = await FlutterTimezone.getLocalTimezone();
      tz.setLocalLocation(tz.getLocation(name));
    } catch (_) {
      tz.setLocalLocation(tz.getLocation('Asia/Shanghai'));
    }

    const android = AndroidInitializationSettings('@mipmap/ic_launcher');
    const settings = InitializationSettings(android: android);
    await _plugin.initialize(settings);
    _ready = true;
  }

  Future<bool> requestPermission() async {
    final android = _plugin.resolvePlatformSpecificImplementation<
        AndroidFlutterLocalNotificationsPlugin>();
    return await android?.requestNotificationsPermission() ?? false;
  }

  /// 为指定周的课程安排提醒
  Future<void> scheduleWeekReminders(
      List<Course> courses, AppSettings settings, int weekNumber) async {
    if (!settings.reminderEnabled) return;
    await init();
    await _plugin.cancelAll();

    final now = tz.TZDateTime.now(tz.local);
    final thisMonday = _mondayOf(now);

    var id = 0;
    for (final c in courses) {
      if (!c.activeInWeek(weekNumber)) continue;
      final startMin = c.startMinutes();
      if (startMin == 0) continue;

      final classDay = thisMonday.add(Duration(days: c.weekday - 1));
      var trigger = tz.TZDateTime(
              tz.local, classDay.year, classDay.month, classDay.day,
              startMin ~/ 60, startMin % 60)
          .subtract(Duration(minutes: settings.reminderAdvanceMinutes));

      // 已过时间跳过
      if (trigger.isBefore(now)) continue;

      final importance = settings.reminderMethod == 1
          ? Importance.max
          : Importance.high;
      final priority =
          settings.reminderMethod == 1 ? Priority.high : Priority.defaultPriority;

      final details = NotificationDetails(
        android: AndroidNotificationDetails(
          'class_reminder',
          '上课提醒',
          channelDescription: '课程开始前提醒',
          importance: importance,
          priority: priority,
          category: AndroidNotificationCategory.alarm,
          visibility: NotificationVisibility.public,
        ),
      );

      await _plugin.zonedSchedule(
        id++,
        '上课提醒',
        '${c.name} · ${c.location}\n'
        '${c.sectionName} ${c.timeRange}',
        trigger,
        details,
        androidScheduleMode: AndroidScheduleMode.inexactAllowWhileIdle,
        matchDateTimeComponents: DateTimeComponents.dayOfWeekAndTime,
        uiLocalNotificationDateInterpretation:
            UILocalNotificationDateInterpretation.absoluteTime,
      );
    }
    debugPrint('已安排 ${courses.length} 门课的提醒');
  }

  Future<void> cancelAll() async {
    if (!_ready) return;
    await _plugin.cancelAll();
  }

  tz.TZDateTime _mondayOf(tz.TZDateTime d) {
    final offset = d.weekday - DateTime.monday;
    return tz.TZDateTime(tz.local, d.year, d.month, d.day)
        .subtract(Duration(days: offset));
  }
}
