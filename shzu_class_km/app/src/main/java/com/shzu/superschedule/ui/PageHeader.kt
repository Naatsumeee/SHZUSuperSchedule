package com.shzu.superschedule.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.SmallTitle as MiuixSmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 页面顶部大标题（今日课程 / 本周课表 / 设置 通用）。
 *
 * ## 留白与字号的一致性（BETA-v1.3.2 #12）
 *
 * 「今日课程」与「本周课表」必须**完全一致**。但两者的外层容器内边距不同：
 * 今日页是 `LazyColumn(contentPadding = 10.dp)`，而周课表页没有额外内边距。
 * 于是同样的 16dp 内边距叠上去，今日页标题会被推得比周课表页多 10dp。
 * 现在用 [horizontalPadding] 显式抵消，两页标题左边缘严格对齐。
 *
 * [miuixDefault] = true 时不走上面的紧凑化，改用 MiuiX 主题自带的
 * `title2` 字号与规范留白（设置页大标题用这一档）。
 */
@Composable
fun PageHeader(
    title: String,
    subtitle: String = "",
    fontSize: Int = 22,
    horizontalPadding: Dp = 16.dp,
    miuixDefault: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (miuixDefault) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.title2,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 8.dp,
                bottom = 4.dp,
            ),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = title,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * 分块小标题（「教务」「显示」「关于」等解释性文字）。
 *
 * MiuiX 的 `SmallTitle` 自带一段左侧缩进，会让小标题看起来比卡片内容"多空一格"；
 * 这里把 `insideMargin` 归零并显式左对齐，让所有分块小字**居左顶格**。
 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    MiuixSmallTitle(
        text = text,
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
    )
}
