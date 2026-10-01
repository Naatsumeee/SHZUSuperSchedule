package com.shzu.superschedule.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.shzu.superschedule.model.AppSettings
import com.shzu.superschedule.model.ThemeMode
import com.shzu.superschedule.model.resolveDarkMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 应用主题（MiuiX 风格）。
 *
 * ## 两个正交的设置维度
 *
 * 1. **深浅模式**（`settings.themeMode`）：跟随系统 / 浅色 / 深色；
 * 2. **取色来源**（`settings.monetEnabled`）：是否跟随壁纸（Monet 动态取色）。
 *
 * 组合起来正好落在 MiuiX 的 [ColorSchemeMode] 上 —— **0.9.3 自带
 * `MonetSystem / MonetLight / MonetDark`**，其内部走
 * `monetSystemColors()` → `systemMd3Roles()` 读取系统 MD3 动态色，
 * 所以**不需要额外引入 MaterialKolor 之类的取色库**。
 *
 * ## 为什么不再写死 `ColorSchemeMode.System`
 *
 * 原先这里固定跟随系统、用户无法干预。现在「设置 → 显示 → 主题」可以
 * 切换深浅与壁纸取色，本文件只负责把设置翻译成 MiuiX 的模式。
 *
 * ⚠️ **Monet 需 Android 12（API 31）及以上**：低版本会静默降级成对应的
 * 非 Monet 模式（仍能切深浅，只是取色跟不了壁纸）。判断统一走
 * [resolveColorSchemeMode]，不要在调用点各写一遍 SDK 判断。
 */
@Composable
fun AppTheme(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val dark = settings.resolveDarkMode(isSystemInDarkTheme())
    val mode = resolveColorSchemeMode(settings, dark)

    // ⚠️ `remember` 的 key 必须带上 mode：ThemeController 只在构造时读取模式，
    //    不把它放进 key 的话，用户在设置里切换后主题不会刷新。
    val controller = remember(mode) { ThemeController(colorSchemeMode = mode) }

    MiuixTheme(controller = controller, content = content)
}

/**
 * 把「深浅模式 + 是否跟随壁纸」翻译成 MiuiX 的 [ColorSchemeMode]。
 *
 * | 设置 | 结果 |
 * |------|------|
 * | 跟随系统 / 浅色 / 深色（非 Monet） | `System` / `Light` / `Dark` |
 * | 跟随系统 / 浅色 / 深色（Monet） | `MonetSystem` / `MonetLight` / `MonetDark` |
 *
 * Monet 在 API < 31 上不可用 → **降级为对应的非 Monet 模式**。
 */
internal fun resolveColorSchemeMode(settings: AppSettings, dark: Boolean): ColorSchemeMode {
    val monet = settings.monetEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val themeMode = ThemeMode.of(settings.themeMode)

    if (!monet) {
        return when (themeMode) {
            ThemeMode.SYSTEM -> ColorSchemeMode.System
            ThemeMode.LIGHT -> ColorSchemeMode.Light
            ThemeMode.DARK -> ColorSchemeMode.Dark
        }
    }
    // Monet 且「跟随系统」时交给 MiuiX 自己感知系统明暗；显式指定浅/深则用对应的 Monet 模式。
    return when (themeMode) {
        ThemeMode.SYSTEM -> ColorSchemeMode.MonetSystem
        ThemeMode.LIGHT -> ColorSchemeMode.MonetLight
        ThemeMode.DARK -> ColorSchemeMode.MonetDark
    }
}
