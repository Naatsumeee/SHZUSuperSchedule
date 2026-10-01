package com.shzu.superschedule.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 底部导航栏的 tab 定义。
 *
 * 抽成数据是因为「常规底栏」与「悬浮底栏」两套外观共用同一份内容 ——
 * 否则加 tab 时要改两处、极易漏一个（项目里已记过这类串页的教训）。
 */
internal data class BottomTab(
    val icon: ImageVector,
    val label: String,
)

/** 底栏外观，由用户设置解析而来。 */
internal data class BottomBarStyle(
    /** 是否启用背景模糊；关闭则用不透明底色（省电，也规避部分机型的叠层渲染问题） */
    val blur: Boolean,
    /** 是否使用悬浮胶囊底栏 */
    val floating: Boolean,
)

/**
 * 底部导航栏（磨砂 + 可选悬浮）。
 *
 * ## 四种组合
 *
 * | 悬浮 | 模糊 | 呈现 |
 * |---|---|---|
 * | 否 | 是 | 贴底整条 + 真实背景模糊（原有观感） |
 * | 否 | 否 | 贴底整条 + 不透明底色 |
 * | 是 | 是 | 胶囊浮起 + 真实背景模糊 |
 * | 是 | 否 | 胶囊浮起 + 不透明底色 |
 *
 * ## 为什么常规底栏必须显式传透明色（BETA-v1.3.2 二次修复 #4）
 *
 * MiuiX 的 `NavigationBar` 内部**硬编码**了 `.background(color)`，默认取
 * `MiuixTheme.colorScheme.surface` —— 一块完全不透明的底，会把磨砂层整个盖住，
 * 等于白做。所以开启模糊时必须显式传 `Color.Transparent` 并关掉自带分隔线
 * （改由磨砂层顶部的 1px 高光代替），磨砂才透得出来。
 *
 * ## 悬浮样式直接用 MiuiX 自带的 FloatingNavigationBar
 *
 * MiuiX 0.9.3 已内置 `FloatingNavigationBar` / `FloatingNavigationBarItem`
 * （胶囊圆角 + 阴影 + 选中动画），不必自己拼一个 ——
 * 自制的版本在圆角、内边距、按压反馈上都难对齐系统观感。
 *
 * ⚠️ 模糊的层数上限见 `BlurBar.kt` 的 `BAND_COUNT`：调大曾导致 RenderThread
 * 原生崩溃（2026-10-01 真机定位），别动。
 */
@Composable
internal fun AppBottomBar(
    backdrop: BarBackdropState,
    tabs: List<BottomTab>,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    style: BottomBarStyle,
) {
    // ⚠️ 两个 content lambda 的 receiver 不同，别合并：
    //    `NavigationBar` 的 content 是 `RowScope.() -> Unit`，
    //    而 `FloatingNavigationBar` 的是普通 `() -> Unit`。
    if (style.floating) {
        val floatingItems: @Composable () -> Unit = {
            tabs.forEachIndexed { index, tab ->
                FloatingNavigationBarItem(
                    selected = index == selectedTab,
                    onClick = { onTabSelected(index) },
                    icon = tab.icon,
                    label = tab.label,
                )
            }
        }
        if (style.blur) {
            BlurredBarContainer(state = backdrop, modifier = Modifier.fillMaxWidth()) {
                FloatingNavigationBar(
                    color = Color.Transparent,
                    showDivider = false,
                    content = floatingItems,
                )
            }
        } else {
            FloatingNavigationBar(
                color = MiuixTheme.colorScheme.surface,
                content = floatingItems,
            )
        }
    } else {
        val regularItems: @Composable RowScope.() -> Unit = {
            tabs.forEachIndexed { index, tab ->
                NavigationBarItem(
                    selected = index == selectedTab,
                    onClick = { onTabSelected(index) },
                    icon = tab.icon,
                    label = tab.label,
                )
            }
        }
        if (style.blur) {
            BlurredBarContainer(state = backdrop, modifier = Modifier.fillMaxWidth()) {
                NavigationBar(
                    color = Color.Transparent,
                    showDivider = false,
                    content = regularItems,
                )
            }
        } else {
            NavigationBar(
                color = MiuixTheme.colorScheme.surface,
                showDivider = true,
                content = regularItems,
            )
        }
    }
}
