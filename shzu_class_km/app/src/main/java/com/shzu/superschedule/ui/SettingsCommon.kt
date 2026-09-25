package com.shzu.superschedule.ui

/**
 * 设置页各页面**共用**的通用控件与工具函数。
 *
 * 这些组件同时被设置主列表（`SettingsPage.kt`）与自定义样式页
 * （`SettingsStylePage.kt`）使用，所以单独成文件。
 *
 * ⚠️ 从 `SettingsPage.kt` **原样搬移**，函数体一行未改；
 * 唯一改动是可见性由 private 提为 internal（跨文件复用所必需）。
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

// ---------------- 通用行组件 ----------------

@Composable
internal fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(text = label, fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        enabled = enabled,
        endActions = {
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        },
        onClick = { if (enabled) onChange(!checked) },
    )
}

/**
 * 点击式下拉菜单。
 *
 * 用 `OverlayDropdownPreference`：它在同一窗口内的 overlay 层渲染，
 * `renderInRootScaffold = false` 时无需额外的 window token，不会闪退
 * （原先的 `WindowDropdownPreference` 会开独立系统窗口，无 token 时直接崩）。
 */
@Composable
internal fun MiuixDropdownRow(
    title: String,
    options: List<String>,
    selected: Int,
    onChange: (Int) -> Unit,
) {
    OverlayDropdownPreference(
        items = options,
        selectedIndex = selected.coerceIn(0, (options.size - 1).coerceAtLeast(0)),
        title = title,
        renderInRootScaffold = false,
        onSelectedIndexChange = onChange,
    )
}

/** 通用确认弹窗 */
@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text(confirmText) }
                }
            }
        }
    }
}


// ---------------- 系统日期选择器 ----------------

/**
 * 调用系统 `DatePickerDialog` 选择开学日期。
 * 校验：日期必须能解析、且**必须是周一**；否则给出警告不写入。
 */
internal fun showSystemDatePicker(
    context: Context,
    initial: String,
    onResult: (date: String?, warning: String?) -> Unit,
) {
    val base = runCatching { LocalDate.parse(initial) }.getOrNull() ?: WeekCalc.today()
    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            val picked = runCatching { LocalDate.of(year, month + 1, dayOfMonth) }.getOrNull()
            when {
                picked == null -> onResult(null, "日期无效，请重新选择")
                picked.dayOfWeek.value != 1 -> onResult(
                    null,
                    "开学日期应为第一周的周一，你选的 ${picked.monthValue}月${picked.dayOfMonth}日 是" +
                        "周${WeekCalc.weekdayCn(picked)}。已忽略本次选择。",
                )
                else -> onResult(picked.toString(), null)
            }
        },
        base.year,
        base.monthValue - 1,
        base.dayOfMonth,
    ).apply {
        setTitle("选择开学日期（第一周周一）")
    }.show()
}

/** 打开本应用的通知设置页（用户在系统层面关掉通知时用） */
internal fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { toast(context, "无法打开系统通知设置，请手动前往设置里开启") }
}

/** 复制文本到剪贴板 */
internal fun copyToClipboard(context: Context, text: String): Boolean = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("邮箱", text))
    true
}.getOrDefault(false)

// ---------------- 弹窗 ----------------

/** 学期切换：无数据的学期也可点 —— 点击后会自动去教务抓取该学期课表 */
@Composable
internal fun SemesterDialog(
    semesters: List<SemesterEntry>,
    current: String,
    onDismiss: () -> Unit,
    onPick: (SemesterEntry) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text(text = "切换学期", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "选择任意学期，App 会自动去教务抓取该学期课表；" +
                        "标「本地已有」的可直接离线切换",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))

                if (semesters.isEmpty()) {
                    Text(
                        text = "暂无学期数据，请先导入一次课表",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                semesters.forEach { s ->
                    val isCurrent = s.code == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isCurrent) MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)
                                else Color.Transparent,
                            )
                            .clickable { onPick(s) }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = s.name.ifBlank { WeekCalc.semesterNameOf(s.code) },
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = buildString {
                                    append(s.code)
                                    if (s.startDate.isNotBlank()) append("  · 开学 ${s.startDate}")
                                    if (s.finished) append("  · 已结束")
                                },
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        when {
                            isCurrent -> Text(
                                text = "当前",
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.primary,
                            )
                            s.hasData -> Text(
                                text = "本地已有",
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            else -> Text(
                                text = "需抓取",
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.primary.copy(alpha = 0.8f),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
            }
        }
    }
}
