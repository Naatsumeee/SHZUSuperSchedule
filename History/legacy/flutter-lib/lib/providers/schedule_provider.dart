import 'dart:async';

import 'package:flutter/foundation.dart';

import '../models/app_settings.dart';
import '../models/course.dart';
import '../services/api_client.dart';
import '../services/auth_service.dart';
import '../services/notification_service.dart';
import '../services/schedule_service.dart';
import '../services/storage_service.dart';

/// 应用状态
class ScheduleProvider extends ChangeNotifier {
  final ApiClient client = ApiClient();
  late final AuthService auth = AuthService(client);
  late final ScheduleService schedule = ScheduleService(client);
  final StorageService storage = StorageService();
  final NotificationService notifications = NotificationService();

  AppSettings settings = AppSettings();
  List<Course> courses = [];
  String semester = '';
  String fetchTime = '';

  bool loggedIn = false;
  bool loading = false;
  String? lastError;

  Timer? _autoTimer;

  // 登录流程状态
  bool needMfa = false;
  String mfaPhone = '';
  bool needCaptchaImage = false;

  Future<void> init() async {
    settings = await storage.loadSettings();
    courses = await storage.loadCourses();
    semester = await storage.loadSemester();
    fetchTime = await storage.loadFetchTime();
    loggedIn = courses.isNotEmpty || settings.username.isNotEmpty;
    if (settings.reminderEnabled) {
      await notifications.init();
    }
    if (settings.autoUpdate) {
      _startAutoUpdate();
    }
    notifyListeners();
  }

  /// 当前是第几周（1 起始）
  int currentWeekNumber(DateTime now) {
    if (settings.schoolStartDate.isEmpty) return 1;
    try {
      final parts = settings.schoolStartDate.split('-');
      final start = DateTime(
          int.parse(parts[0]), int.parse(parts[1]), int.parse(parts[2]));
      final diff = now.difference(start).inDays;
      if (diff < 0) return 1;
      return (diff / 7).floor() + 1;
    } catch (_) {
      return 1;
    }
  }

  /// 登录
  Future<bool> login(String username, String password,
      {String? smsCode}) async {
    loading = true;
    lastError = null;
    notifyListeners();
    try {
      if (smsCode == null) {
        final (res, err) = await auth.loginWithPassword(username, password);
        if (res == LoginResult.needMfa) {
          needMfa = true;
          final (ok, phone) = await auth.sendSmsCode(username);
          if (!ok) {
            lastError = phone;
            loading = false;
            needMfa = false;
            notifyListeners();
            return false;
          }
          mfaPhone = phone;
          settings.username = username;
          settings.password = password;
          await storage.saveSettings(settings);
          loading = false;
          notifyListeners();
          return false; // 等待验证码
        } else if (res == LoginResult.failed) {
          lastError = err;
          loading = false;
          notifyListeners();
          return false;
        }
      } else {
        // 提交短信验证码
        final (ok, err) = await auth.submitSmsCode(username, smsCode);
        if (!ok) {
          lastError = err;
          loading = false;
          notifyListeners();
          return false;
        }
        needMfa = false;
      }

      // 进入教务系统并抓课表
      final (entered, enterErr) = await auth.enterJwgl();
      if (!entered) {
        lastError = enterErr;
        loading = false;
        notifyListeners();
        return false;
      }
      await refresh(force: true);

      settings.username = username;
      settings.password = password;
      await storage.saveSettings(settings);

      loggedIn = true;
      loading = false;
      notifyListeners();
      return true;
    } catch (e) {
      lastError = e.toString();
      loading = false;
      notifyListeners();
      return false;
    }
  }

  /// 重新发送短信
  Future<void> resendSms() async {
    final (ok, msg) = await auth.sendSmsCode(settings.username);
    if (ok) {
      mfaPhone = msg;
    } else {
      lastError = msg;
    }
    notifyListeners();
  }

  /// 刷新课表
  Future<bool> refresh({bool force = false}) async {
    loading = true;
    notifyListeners();
    try {
      final html = await schedule.fetchRaw(semester);
      final newSemester = schedule.currentSemester(html);
      final parsed = schedule.parseSchedule(html);
      if (parsed.isEmpty) {
        lastError = '未解析到课程，请确认已选课';
        loading = false;
        notifyListeners();
        return false;
      }
      courses = parsed;
      semester = newSemester.isNotEmpty ? newSemester : semester;
      fetchTime = _nowStr();
      await storage.saveCourses(courses);
      await storage.saveSemester(semester);
      await storage.saveFetchTime(fetchTime);

      // 重新安排提醒
      if (settings.reminderEnabled) {
        final week = currentWeekNumber(DateTime.now());
        await notifications.scheduleWeekReminders(
            courses, settings, week);
      }

      lastError = null;
      loading = false;
      notifyListeners();
      return true;
    } on SessionExpiredException {
      // 登录过期：提示重新登录
      lastError = '登录已过期，请重新登录';
      loggedIn = false;
      loading = false;
      notifyListeners();
      return false;
    } catch (e) {
      lastError = '获取课表失败：$e';
      loading = false;
      notifyListeners();
      return false;
    }
  }

  /// 切换学期
  Future<void> setSemester(String s) async {
    semester = s;
    await storage.saveSemester(s);
    await refresh(force: true);
  }

  /// 更新设置
  Future<void> updateSettings(AppSettings s) async {
    final oldReminder = settings.reminderEnabled;
    settings = s;
    await storage.saveSettings(s);
    if (s.autoUpdate) {
      _startAutoUpdate();
    } else {
      _autoTimer?.cancel();
      _autoTimer = null;
    }
    // 提醒开关变化
    if (s.reminderEnabled && !oldReminder) {
      await notifications.requestPermission();
      final week = currentWeekNumber(DateTime.now());
      await notifications.scheduleWeekReminders(courses, s, week);
    } else if (!s.reminderEnabled) {
      await notifications.cancelAll();
    }
    notifyListeners();
  }

  /// 登出
  Future<void> logout() async {
    _autoTimer?.cancel();
    _autoTimer = null;
    await notifications.cancelAll();
    await storage.clear();
    loggedIn = false;
    courses = [];
    semester = '';
    fetchTime = '';
    settings = AppSettings();
    needMfa = false;
    notifyListeners();
  }

  void _startAutoUpdate() {
    _autoTimer?.cancel();
    final minutes = settings.autoUpdateMinutes > 0 ? settings.autoUpdateMinutes : 60;
    _autoTimer = Timer.periodic(Duration(minutes: minutes), (_) {
      if (loggedIn) refresh();
    });
  }

  String _nowStr() {
    final n = DateTime.now();
    String two(int v) => v.toString().padLeft(2, '0');
    return '${n.year}-${two(n.month)}-${two(n.day)} ${two(n.hour)}:${two(n.minute)}';
  }

  /// 指定日期的课程（按星期过滤 + 周次过滤）
  List<Course> coursesOn(DateTime day) {
    final week = currentWeekNumber(DateTime.now());
    return courses
        .where((c) => c.weekday == day.weekday && c.activeInWeek(week))
        .toList()
      ..sort((a, b) => a.startMinutes().compareTo(b.startMinutes()));
  }

  @override
  void dispose() {
    _autoTimer?.cancel();
    super.dispose();
  }
}
