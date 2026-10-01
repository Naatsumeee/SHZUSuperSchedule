package com.shzu.superschedule.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.shzu.superschedule.model.AppSettings
import com.shzu.superschedule.model.ThemeMode
import com.shzu.superschedule.model.UiStyle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置 → 显示 → **主题**（二级页）。
 *
 * ## 这些选项的来路
 *
 * 布局与选项集参考了 SukiSU-Ultra 管理器的「主题」页（它是 KernelSU 分支，
 * 但管理器 App 的视觉设置做得很完整）。**只借鉴视觉设置的组织方式与思路，
 * 不引入它的任何底层实现**（root / 内核 / 模块那一套与本项目无关）。
 *
 * 具体借鉴与取舍：
 *
 * | 选项 | 做法 |
 * |------|------|
 * | 深浅模式 | MiuiX 自带 `ColorSchemeMode` 六态，不需要引入 MaterialKolor |
 * | 壁纸取色（Monet） | 同上，MiuiX 内部走 `monetSystemColors()` |
 * | UI Style | 保留持久化位；目前只有一种风格，故置灰说明 |
 * | 底栏模糊 | 复用项目既有的 `BlurBar` 图层方案（**不是** SukiSU 的 miuix-blur） |
 * | 悬浮底栏 | 直接用 MiuiX 自带的 `FloatingNavigationBar` |
 * | 预测性返回 | 用 `PredictiveBackHandler`（**不是** SukiSU 反射改 ApplicationInfo 的做法） |
 * | 液态玻璃 | **本轮未做** —— 见文件末尾说明 |
 */
@Composable
internal fun ThemePage(
    ui: SettingsUiState,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
    bottomInset: Dp = 0.dp,
) {
    val monetAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val predictiveAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(ui.themeScroll)
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp + bottomInset),
    ) {
        SubPageTopBar(title = "主题", onBack = onBack)

        SectionTitle("深浅模式")
        Card {
            MiuixDropdownRow(
                title = "主题",
                options = listOf("跟随系统", "浅色", "深色"),
                selected = settings.themeMode,
                onChange = { onSettingsChange(settings.copy(themeMode = it)) },
            )
            SwitchRow(
                title = "跟随壁纸取色",
                summary = if (monetAvailable) {
                    "从壁纸提取主色，界面配色随壁纸变化（Monet）"
                } else {
                    "需要 Android 12 及以上，当前系统不支持"
                },
                checked = settings.monetEnabled && monetAvailable,
                // 系统不支持时不让点，避免"开了却没反应"的困惑
                enabled = monetAvailable,
                onChange = { onSettingsChange(settings.copy(monetEnabled = it)) },
            )
        }
        Spacer(Modifier.height(4.dp))

        SectionTitle("外观")
        Card {
            MiuixDropdownRow(
                title = "UI 风格",
                options = UiStyle.entries.map { uiStyleLabel(it) },
                selected = settings.uiStyle,
                onChange = { onSettingsChange(settings.copy(uiStyle = it)) },
            )
            SwitchRow(
                title = "底栏背景模糊",
                summary = "磨砂底栏，透过底栏能看到背后内容的模糊效果",
                checked = settings.blurEnabled,
                onChange = { onSettingsChange(settings.copy(blurEnabled = it)) },
            )
            SwitchRow(
                title = "悬浮底栏",
                summary = "胶囊形底栏浮在内容之上，而不是贴底的一条",
                checked = settings.floatingBottomBar,
                onChange = { onSettingsChange(settings.copy(floatingBottomBar = it)) },
            )
        }
        Spacer(Modifier.height(4.dp))

        SectionTitle("交互")
        Card {
            SwitchRow(
                title = "预测性返回手势",
                summary = if (predictiveAvailable) {
                    "返回时上一级界面跟手滑入，可中途松手取消"
                } else {
                    "需要 Android 13 及以上，当前系统不支持"
                },
                checked = settings.predictiveBack && predictiveAvailable,
                enabled = predictiveAvailable,
                onChange = { onSettingsChange(settings.copy(predictiveBack = it)) },
            )
        }
        Spacer(Modifier.height(4.dp))

        SectionTitle("说明")
        Card {
            // 用 Card 承载一段纯文字说明，与设置项视觉上区分开
            Text(
                text = "「跟随壁纸取色」在 Android 12 及以上才生效，" +
                    "低版本会自动使用应用内置配色。\n\n" +
                    "预测性返回可能与你系统的手势导航有细微差异，" +
                    "如果觉得不跟手，把它关掉即可回到标准返回动画。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** UI 风格的显示名（只有一种，保留结构以便将来扩展） */
private fun uiStyleLabel(style: UiStyle): String = when (style) {
    UiStyle.DEFAULT -> "默认（MiuiX）"
}
