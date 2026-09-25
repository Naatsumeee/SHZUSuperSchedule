package com.shzu.superschedule.ui

/**
 * 设置 → 自定义课表样式（二级页）与配色组件。
 *
 * 原本全部内联在 `SettingsPage.kt`（1755 行）里，2026-09-25 按内聚性拆出 ——
 * 配色相关的一组组件（预设色板 / 自定义合集 / 自定义槽位 / 文字颜色与对比度检查）
 * 自成一体、与设置主列表几乎无耦合，是原文件里最大的一块。
 *
 * ⚠️ 从 `SettingsPage.kt` **原样搬移**，函数体一行未改；
 * 唯一改动是把跨文件复用的 `SliderRow` / `SwitchRow` / `MiuixDropdownRow` /
 * `ConfirmDialog` / `copyToClipboard` 的可见性由 private 提为 internal
 * （它们现在定义在 `SettingsCommon.kt`）。
 */

import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.shzu.superschedule.BuildConfig
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.shzu.superschedule.data.AppLog
import com.shzu.superschedule.data.Notifier
import com.shzu.superschedule.data.ScheduleStore
import com.shzu.superschedule.model.AppSettings
import com.shzu.superschedule.model.ColorSchemes
import com.shzu.superschedule.model.Course
import com.shzu.superschedule.model.CustomPalette
import com.shzu.superschedule.model.SemesterEntry
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate

// ---------------- 二级页：自定义课表样式 ----------------

@Composable
internal fun CustomStylePage(
    ui: SettingsUiState,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
    bottomInset: Dp = 0.dp,
) {
    var showReset by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(ui.customScroll)
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp + bottomInset),
    ) {
        SubPageTopBar(title = "自定义课表样式", onBack = onBack)

        SectionTitle("尺寸与间距")
        Card {
            // 课程高度标尺：决定 10 行等距表格中每一行的高度
            SliderRow(
                title = "课程高度",
                value = settings.rowHeight,
                range = 34f..96f,
                label = "${settings.rowHeight.toInt()} dp",
                onChange = { onSettingsChange(settings.copy(rowHeight = it)) },
            )
            Text(
                text = "以该值为唯一标尺，课表严格划分为 10 行等距表格；" +
                    "一门课占几个课时就渲染几格高（2 课时 = 2 格，3 课时 = 3 格）。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            SliderRow(
                title = "字体大小",
                value = settings.fontSize,
                range = 9f..18f,
                label = settings.fontSize.toInt().toString(),
                onChange = { onSettingsChange(settings.copy(fontSize = it)) },
            )
            SliderRow(
                title = "文字与边界间距",
                value = settings.cellPadding,
                range = 0f..12f,
                label = settings.cellPadding.toInt().toString(),
                onChange = { onSettingsChange(settings.copy(cellPadding = it)) },
            )
            MiuixDropdownRow(
                title = "文字对齐",
                options = listOf("居中", "居左", "两端对齐"),
                selected = settings.alignment,
                onChange = { onSettingsChange(settings.copy(alignment = it)) },
            )
        }
        Spacer(Modifier.height(4.dp))

        SectionTitle("网格与边框")
        Card {
            SwitchRow(
                title = "显示网格线",
                summary = "课表背景按 10 行 × 7 列绘制虚线网格（与星期列严格对齐）",
                checked = settings.showGridLines,
                onChange = { onSettingsChange(settings.copy(showGridLines = it)) },
            )
            SwitchRow(
                title = "显示课程格边框",
                summary = "为每个课程格绘制细边框（独立选项）",
                checked = settings.showCellBorder,
                onChange = { onSettingsChange(settings.copy(showCellBorder = it)) },
            )
        }
        Spacer(Modifier.height(4.dp))

        SectionTitle("文字样式")
        Card {
            SwitchRow(
                title = "加粗课程名字体",
                summary = "开启后课程名使用加粗字重，关闭使用常规字重",
                checked = settings.boldName,
                onChange = { onSettingsChange(settings.copy(boldName = it)) },
            )
            if (settings.boldName) {
                MiuixDropdownRow(
                    title = "加粗字重",
                    options = listOf("Bold（默认）", "Medium"),
                    selected = if (settings.useBoldWeight) 0 else 1,
                    onChange = {
                        onSettingsChange(settings.copy(useBoldWeight = it == 0))
                    },
                )
            }
            SwitchRow(
                title = "隐藏过长课程名",
                summary = "课程名超过 3 行时隐藏后面的内容并显示省略号",
                checked = settings.ellipsizeName,
                onChange = { onSettingsChange(settings.copy(ellipsizeName = it)) },
            )
            SwitchRow(
                title = "隐藏过长教师名",
                summary = "教师名超过 2 行时隐藏后面的内容并显示省略号",
                checked = settings.ellipsizeTeacher,
                onChange = { onSettingsChange(settings.copy(ellipsizeTeacher = it)) },
            )
        }
        Spacer(Modifier.height(4.dp))

        SectionTitle("非本周课程")
        Card {
            SliderRow(
                title = "淡化强度",
                value = settings.otherWeekAlpha.toFloat(),
                range = 5f..60f,
                label = "${settings.otherWeekAlpha}%",
                onChange = {
                    onSettingsChange(settings.copy(otherWeekAlpha = it.toInt()))
                },
            )
            Text(
                text = "非本周课程会向课表底色淡化，但**始终保持不透明**，" +
                    "因此不会漏出底层的网格线与格子边线。数值越小越淡。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(4.dp))

        SectionTitle("配色")
        Card {
            SwitchRow(
                title = "使用预设配色集",
                summary = "关闭后使用你自己的配色合集（最多 3 套）",
                checked = settings.useDefaultColors,
                onChange = { on ->
                    if (!on && settings.customPalettes.isEmpty()) {
                        // 首次选用自定义配色：自动创建一套，颜色复制马卡龙
                        val p = CustomPalette("自定义 1", ColorSchemes.macaron)
                        onSettingsChange(
                            settings.copy(
                                useDefaultColors = false,
                                customPalettes = listOf(p),
                                activeCustomPalette = 0,
                                customColors = p.colors,
                            ),
                        )
                    } else {
                        onSettingsChange(settings.copy(useDefaultColors = on))
                    }
                },
            )
            ColorSchemeList(
                settings = settings,
                expanded = ui.schemeExpanded,
                onToggleExpand = { ui.schemeExpanded = !ui.schemeExpanded },
                onPick = { index ->
                    onSettingsChange(
                        settings.copy(useDefaultColors = true, colorSchemeIndex = index),
                    )
                },
            )
            CustomPaletteSection(
                settings = settings,
                onSettingsChange = onSettingsChange,
            )
            SwitchRow(
                title = "自定义文字颜色",
                summary = "开启后统一使用指定文字颜色，并自动校验对比度",
                checked = settings.useCustomTextColor,
                onChange = { onSettingsChange(settings.copy(useCustomTextColor = it)) },
            )
            if (settings.useCustomTextColor) {
                TextColorRow(
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        SectionTitle("重置")
        Card {
            BasicComponent(
                title = "恢复默认选项",
                summary = "重置全部外观设置到默认值（格子高度 56dp / 字号 12 / 间距 3），不影响课表与自定义配色合集",
                onClick = { showReset = true },
            )
        }
        Spacer(Modifier.height(28.dp))
    }

    if (showReset) {
        ConfirmDialog(
            title = "恢复默认？",
            message = "将重置字体、间距、对齐、网格、配色等全部外观选项到默认值，" +
                "不会删除课程数据，也不会删除你的自定义配色合集。",
            confirmText = "恢复默认",
            onDismiss = { showReset = false },
            onConfirm = {
                onSettingsChange(settings.resetAppearance())
                showReset = false
            },
        )
    }
}


// ---------------- 配色 ----------------

/**
 * 预设配色集列表。
 *
 * 默认**只陈列「马卡龙」**（`DEFAULT_VISIBLE_INDEX`）；
 * 再点一次当前已选中的那一套（或点右上角「展开全部」）才会铺开其余配色集。
 * 这样设置页首屏不会被 6 套色卡塞满。
 */
@Composable
private fun ColorSchemeList(
    settings: AppSettings,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val enabled = settings.useDefaultColors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "配色集", fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(
                text = if (expanded) "收起" else "展开全部（${ColorSchemes.all.size}）",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.clickable { onToggleExpand() },
            )
        }
        Text(
            text = if (enabled) "选中一套整体配色；再点一下已选中的配色即可展开其余配色集"
            else "已关闭（使用自定义配色合集）",
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(10.dp))
        val visible = if (expanded) {
            ColorSchemes.all.indices.toList()
        } else {
            listOf(ColorSchemes.DEFAULT_VISIBLE_INDEX)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            visible.forEach { index ->
                val (name, colors) = ColorSchemes.all[index]
                val selected = enabled && index == settings.colorSchemeIndex
                PaletteCardRow(
                    name = name,
                    colors = colors,
                    selected = selected,
                    enabled = enabled,
                    onClick = {
                        when {
                            // 「二次点击」：已选中的那一套再点一下就展开其余配色集
                            selected && !expanded -> onToggleExpand()
                            else -> onPick(index)
                        }
                    },
                )
            }
        }
    }
}

/** 一行色卡：名称 + 15 个色块 */
@Composable
private fun PaletteCardRow(
    name: String,
    colors: List<Long>,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (selected) Modifier.border(
                    1.dp,
                    MiuixTheme.colorScheme.primary,
                    RoundedCornerShape(8.dp),
                ) else Modifier,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            fontSize = 12.sp,
            modifier = Modifier.size(width = 66.dp, height = 18.dp),
            color = if (selected) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            colors.take(15).forEach { argb ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(CourseColors.argbToColor(argb)),
                )
            }
        }
    }
}

/**
 * 自定义配色合集（最多 3 套）。
 *
 * - 点某一行即选中它（同时把「使用预设配色集」关掉）；
 * - 「新建」复制马卡龙配色，方便改；
 * - 数量 > 1 时可删除。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomPaletteSection(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    val palettes = settings.customPalettes
    val max = CustomPalette.MAX_CUSTOM_PALETTES

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(text = "自定义配色合集", fontSize = 15.sp)
        Text(
            text = "最多 $max 套；新建时默认复制「马卡龙」，可逐格修改",
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(10.dp))

        if (palettes.isEmpty()) {
            Text(
                text = "还没有自定义配色集，关闭上方「使用预设配色集」会自动创建一套。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            palettes.forEachIndexed { index, palette ->
                val selected = !settings.useDefaultColors &&
                    index == settings.activeCustomPalette
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (selected) Modifier.border(
                                1.dp,
                                MiuixTheme.colorScheme.primary,
                                RoundedCornerShape(8.dp),
                            ) else Modifier,
                        )
                        .clickable {
                            onSettingsChange(
                                settings.copy(
                                    useDefaultColors = false,
                                    activeCustomPalette = index,
                                    customColors = palette.colors,
                                ),
                            )
                        }
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = palette.name.ifBlank { "自定义 ${index + 1}" },
                        fontSize = 12.sp,
                        modifier = Modifier.size(width = 66.dp, height = 18.dp),
                        color = if (selected) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurface,
                    )
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        val cols = if (palette.colors.size >= 3) palette.colors
                        else ColorSchemes.macaron
                        cols.take(15).forEach { argb ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(16.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(CourseColors.argbToColor(argb)),
                            )
                        }
                    }
                    if (palettes.size > 1) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "删除",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier
                                .clickable {
                                    val rest = palettes.toMutableList().also { it.removeAt(index) }
                                    val newActive = settings.activeCustomPalette
                                        .coerceAtMost(rest.size - 1).coerceAtLeast(0)
                                    onSettingsChange(
                                        settings.copy(
                                            customPalettes = rest,
                                            activeCustomPalette = newActive,
                                            customColors = rest.getOrNull(newActive)?.colors
                                                ?: emptyList(),
                                            useDefaultColors = if (rest.isEmpty()) true
                                            else settings.useDefaultColors,
                                        ),
                                    )
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }

        if (palettes.size < max) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "＋ 新建自定义配色集（复制马卡龙）",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        val p = CustomPalette("自定义 ${palettes.size + 1}", ColorSchemes.macaron)
                        val newList = palettes + p
                        onSettingsChange(
                            settings.copy(
                                customPalettes = newList,
                                activeCustomPalette = newList.lastIndex,
                                useDefaultColors = false,
                                customColors = p.colors,
                            ),
                        )
                    }
                    .padding(vertical = 6.dp),
            )
        }

        // 选中自定义合集时才显示 15 个槽位的编辑入口
        if (!settings.useDefaultColors && palettes.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            CustomColorSlotsRow(settings = settings, onSettingsChange = onSettingsChange)
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** 15 个颜色槽位的自定义（HSL 色盘逐个替换，作用于当前选中的自定义合集） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomColorSlotsRow(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    var editing by remember { mutableStateOf<Int?>(null) }
    val palettes = settings.customPalettes
    if (palettes.isEmpty()) return
    val index = settings.activeCustomPalette.coerceIn(0, palettes.lastIndex)
    val current = palettes[index]
    val colors = if (current.colors.size >= 3) current.colors else ColorSchemes.macaron
    val slots = remember(colors) {
        List(15) { i ->
            colors.getOrNull(i)?.takeIf { it != 0L }
                ?.let { CourseColors.argbToColor(it) } ?: CourseColors.palette[i]
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "「${current.name.ifBlank { "自定义 ${index + 1}" }}」的 15 个颜色槽位",
            fontSize = 13.sp,
        )
        Text(
            text = "点色块用 HSL 色盘替换该槽位颜色",
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            slots.forEachIndexed { i, color ->
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(color)
                        .clickable { editing = i },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }

    val editIndex = editing
    if (editIndex != null) {
        HslColorPickerDialog(
            initial = slots[editIndex],
            title = "自定义颜色 ${editIndex + 1}",
            onDismiss = { editing = null },
            onConfirm = { picked ->
                val list = slots.mapIndexed { i, c ->
                    if (i == editIndex) CourseColors.colorToArgb(picked)
                    else CourseColors.colorToArgb(c)
                }
                val newPalettes = palettes.toMutableList().also {
                    it[index] = current.copy(colors = list)
                }
                onSettingsChange(
                    settings.copy(customPalettes = newPalettes, customColors = list),
                )
                editing = null
            },
        )
    }
}

/**
 * 自定义文字颜色 + **对比度校验**。
 *
 * 选完颜色后立刻检查它与当前色板 15 个底色的对比度；
 * 只要有格子低于 3:1，就弹窗提醒，并让用户二选一：
 * 「对看不清的格子自动使用自适应文字色」或「保持当前设置」。
 */
@Composable
private fun TextColorRow(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var warningFor by remember { mutableStateOf<Color?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "文字颜色", fontSize = 15.sp)
            Text(
                text = if (settings.autoTextColorFallback)
                    "对比度不足的格子会自动改用深/浅自适应色"
                else "所有格子统一使用该颜色",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CourseColors.argbToColor(settings.customTextColor))
                .border(
                    0.5.dp,
                    MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f),
                    RoundedCornerShape(8.dp),
                )
                .clickable { editing = true },
        )
    }

    if (editing) {
        HslColorPickerDialog(
            initial = CourseColors.argbToColor(settings.customTextColor),
            title = "选择文字颜色",
            onDismiss = { editing = false },
            onConfirm = { picked ->
                val updated = settings.copy(customTextColor = CourseColors.colorToArgb(picked))
                onSettingsChange(updated)
                editing = false
                if (CourseColors.lowContrastSlots(updated, picked).isNotEmpty()) {
                    warningFor = picked
                }
            },
        )
    }

    val pending = warningFor
    if (pending != null) {
        TextColorContrastDialog(
            textColor = pending,
            settings = settings,
            onAdaptive = {
                onSettingsChange(settings.copy(autoTextColorFallback = true))
                warningFor = null
            },
            onKeep = {
                onSettingsChange(settings.copy(autoTextColorFallback = false))
                warningFor = null
            },
        )
    }
}

/** 文字颜色对比度提醒弹窗 */
@Composable
private fun TextColorContrastDialog(
    textColor: Color,
    settings: AppSettings,
    onAdaptive: () -> Unit,
    onKeep: () -> Unit,
) {
    val badSlots = remember(textColor, settings) {
        CourseColors.lowContrastSlots(settings, textColor)
    }
    Dialog(onDismissRequest = onKeep) {
        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text("文字可能看不清", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "所选文字颜色与 ${badSlots.size} 个格子底色的对比度低于 3:1，" +
                        "这些格子上的字会难以辨认。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )

                // 预览：把有问题的底色连同文字一起画出来
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val palette = CourseColors.activePalette(settings)
                    badSlots.take(6).forEach { idx ->
                        val bg = palette.getOrNull(idx) ?: return@forEach
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(bg),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "课程",
                                fontSize = 11.sp,
                                color = textColor,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Button(onClick = onAdaptive, modifier = Modifier.fillMaxWidth()) {
                    Text("对看不清的格子使用自适应文字色（推荐）")
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onKeep, modifier = Modifier.fillMaxWidth()) {
                    Text("保持当前设置")
                }
            }
        }
    }
}

