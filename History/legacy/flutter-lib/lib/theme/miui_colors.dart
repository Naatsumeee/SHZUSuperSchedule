import 'package:flutter/material.dart';

/// MIUI 风格配色系统（低饱和度、柔和、可区分）
class MiuiColors {
  MiuiColors._();

  // 基础色
  static const Color primary = Color(0xFFFF6700); // 小米橙
  static const Color background = Color(0xFFF5F5F5); // 浅灰背景
  static const Color card = Colors.white;
  static const Color textPrimary = Color(0xFF1A1A1A);
  static const Color textSecondary = Color(0xFF8A8A8A);
  static const Color divider = Color(0xFFEEEEEE);

  /// 默认课程色板：低饱和度、色相均匀分布、彼此明显可区分
  static const List<Color> coursePalette = [
    Color(0xFFD98880), // 柔红
    Color(0xFFE59866), // 杏橙
    Color(0xFFF0C36C), // 暖黄
    Color(0xFF9ACD8E), // 草绿
    Color(0xFF76C7A6), // 青绿
    Color(0xFF76C7C0), // 浅青
    Color(0xFF85C1E9), // 天蓝
    Color(0xFF7FA3E0), // 淡蓝紫
    Color(0xFFAF7AC5), // 柔紫
    Color(0xFFE598B8), // 柔粉
    Color(0xFFF0A3A3), // 珊瑚
    Color(0xFFB8A6D9), // 淡紫
    Color(0xFF9FB6C9), // 蓝灰
    Color(0xFFA9CCB4), // 灰绿
    Color(0xFFD9C9A3), // 卡其
  ];

  /// 非本周课程使用的更低饱和度（偏灰）颜色
  static const List<Color> pastelPalette = [
    Color(0xFFE8D5D5),
    Color(0xFFE9DCCE),
    Color(0xFFE9E0C8),
    Color(0xFFD5E3D0),
    Color(0xFFCDE2DA),
    Color(0xFFCFE0E6),
    Color(0xFFD2DEEE),
    Color(0xFFD6D8EC),
    Color(0xFFDCCFEC),
    Color(0xFFEAD3E0),
    Color(0xFFEAD8D3),
    Color(0xFFE0D9EE),
    Color(0xFFD4DDE6),
    Color(0xFFD5E3D9),
    Color(0xFFE7E1D0),
  ];

  /// 按课程名稳定地映射到一个颜色索引
  static int colorIndex(String name) {
    int h = 0;
    for (final c in name.codeUnits) {
      h = (h * 31 + c) & 0x7fffffff;
    }
    return h % coursePalette.length;
  }

  /// 取课程颜色
  static Color courseColor(String name) => coursePalette[colorIndex(name)];

  /// 取非本周课程颜色
  static Color pastelColor(String name) => pastelPalette[colorIndex(name)];
}
