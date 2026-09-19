import 'package:flutter/material.dart';
import 'miui_colors.dart';

/// MIUI 风格主题
class MiuiTheme {
  MiuiTheme._();

  static ThemeData light() {
    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.light,
      scaffoldBackgroundColor: MiuiColors.background,
      colorScheme: const ColorScheme.light(
        primary: MiuiColors.primary,
        secondary: MiuiColors.primary,
        surface: MiuiColors.card,
        onSurface: MiuiColors.textPrimary,
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: MiuiColors.card,
        foregroundColor: MiuiColors.textPrimary,
        elevation: 0,
        centerTitle: true,
        titleTextStyle: TextStyle(
          color: MiuiColors.textPrimary,
          fontSize: 17,
          fontWeight: FontWeight.w600,
        ),
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: MiuiColors.card,
        indicatorColor: MiuiColors.primary.withValues(alpha: 0.12),
        labelTextStyle: WidgetStateProperty.resolveWith((states) {
          final selected = states.contains(WidgetState.selected);
          return TextStyle(
            fontSize: 12,
            fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
            color: selected ? MiuiColors.primary : MiuiColors.textSecondary,
          );
        }),
        iconTheme: WidgetStateProperty.resolveWith((states) {
          final selected = states.contains(WidgetState.selected);
          return IconThemeData(
            color: selected ? MiuiColors.primary : MiuiColors.textSecondary,
          );
        }),
      ),
      cardTheme: CardThemeData(
        color: MiuiColors.card,
        elevation: 0,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        margin: EdgeInsets.zero,
      ),
      dividerTheme: const DividerThemeData(
        color: MiuiColors.divider,
        thickness: 0.5,
        space: 0.5,
      ),
      textTheme: const TextTheme(
        titleLarge: TextStyle(color: MiuiColors.textPrimary, fontSize: 20, fontWeight: FontWeight.w600),
        titleMedium: TextStyle(color: MiuiColors.textPrimary, fontSize: 16, fontWeight: FontWeight.w600),
        bodyLarge: TextStyle(color: MiuiColors.textPrimary, fontSize: 15),
        bodyMedium: TextStyle(color: MiuiColors.textSecondary, fontSize: 14),
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: MiuiColors.textPrimary,
        contentTextStyle: const TextStyle(color: Colors.white, fontSize: 14),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    );
  }

  /// 统一圆角
  static const double radius = 16;
  static const double radiusSmall = 12;
  static const double radiusLarge = 20;
}
