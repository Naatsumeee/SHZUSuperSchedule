import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../models/app_settings.dart';
import '../models/course.dart';

/// 本地存储服务
class StorageService {
  static const _kSettings = 'settings';
  static const _kCourses = 'courses_cache';
  static const _kSemester = 'semester';
  static const _kFetchTime = 'fetch_time';

  Future<AppSettings> loadSettings() async {
    final sp = await SharedPreferences.getInstance();
    final raw = sp.getString(_kSettings);
    if (raw == null) return AppSettings();
    try {
      return AppSettings.fromJson(jsonDecode(raw));
    } catch (_) {
      return AppSettings();
    }
  }

  Future<void> saveSettings(AppSettings s) async {
    final sp = await SharedPreferences.getInstance();
    await sp.setString(_kSettings, jsonEncode(s.toJson()));
  }

  Future<List<Course>> loadCourses() async {
    final sp = await SharedPreferences.getInstance();
    final raw = sp.getString(_kCourses);
    if (raw == null) return [];
    try {
      final list = jsonDecode(raw) as List;
      return list.map((e) => Course.fromJson(e)).toList();
    } catch (_) {
      return [];
    }
  }

  Future<void> saveCourses(List<Course> courses) async {
    final sp = await SharedPreferences.getInstance();
    await sp.setString(_kCourses,
        jsonEncode(courses.map((c) => c.toJson()).toList()));
  }

  Future<String> loadSemester() async {
    final sp = await SharedPreferences.getInstance();
    return sp.getString(_kSemester) ?? '';
  }

  Future<void> saveSemester(String s) async {
    final sp = await SharedPreferences.getInstance();
    await sp.setString(_kSemester, s);
  }

  Future<String> loadFetchTime() async {
    final sp = await SharedPreferences.getInstance();
    return sp.getString(_kFetchTime) ?? '';
  }

  Future<void> saveFetchTime(String t) async {
    final sp = await SharedPreferences.getInstance();
    await sp.setString(_kFetchTime, t);
  }

  Future<void> clear() async {
    final sp = await SharedPreferences.getInstance();
    await sp.remove(_kSettings);
    await sp.remove(_kCourses);
    await sp.remove(_kSemester);
    await sp.remove(_kFetchTime);
  }
}
