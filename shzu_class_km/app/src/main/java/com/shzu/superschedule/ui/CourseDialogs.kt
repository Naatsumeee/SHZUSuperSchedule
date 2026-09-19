package com.shzu.superschedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.shzu.superschedule.model.AppSettings
import com.shzu.superschedule.model.ClassTime
import com.shzu.superschedule.model.Course
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 星期中文（长） */
internal val WeekLong = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 课程实际占用的节次文本，如 "第1-4 节"（按真实课时数而不是字段值） */
private fun sectionSpanText(c: Course): String {
    val s = c.startSection
    if (s <= 0) return c.sectionName.ifBlank { c.sections }
    val n = c.effectiveSectionCount()
    return if (n > 1) "第$s-${s + n - 1} 节" else "第$s 节"
}

/** 课程占用的课时数（用于冲突优先级的「占课时少者优先」比较） */
private fun spanCount(c: Course): Int = c.effectiveSectionCount().coerceAtLeast(1)

/**
 * 课程详情弹窗。
 *
 * BETA-v1.3.2 变化：
 * - 「时间冲突」区块**默认展开**，不再需要用户二次点击；
 * - 冲突课程里可以直接指定「优先显示」哪一门（写入 `displayPriority`）；
 * - 多教师课程可指定周视图显示**某一位**教师，或全部列出。
 */
@Composable
fun CourseDetailDialog(
    course: Course,
    week: Int,
    settings: AppSettings,
    /** 同格所有课程（用于冲突展示） */
    conflicts: List<Course> = emptyList(),
    onDelete: (() -> Unit)? = null,
    /** 就地更新课程（教师显示、冲突优先级） */
    onUpdate: ((Course) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    // #3：默认展开，用户进入二级菜单立刻能看到冲突课程
    var showConflicts by remember { mutableStateOf(true) }

    val thisWeek = course.activeInWeek(week)
    val color = CourseColors.resolve(settings, course.name, thisWeek)

    // 冲突课程去重（同名同节次的跨周记录算同一门课）
    val conflictList = remember(conflicts) { conflicts.distinctBy { it.key() } }
    // 自动裁决 + 手动优先级，算出当前「优先显示」的那门课
    val winnerKey = remember(conflictList) {
        conflictList.sortedWith(
            compareByDescending<Course> { it.displayPriority }
                .thenBy { spanCount(it) }
                .thenBy { it.startSection }
                .thenBy { it.name },
        ).firstOrNull()?.key()
    }
    val maxPriority = remember(conflictList) { conflictList.maxOfOrNull { it.displayPriority } ?: 0 }
    val hasManualPriority = remember(conflictList) { conflictList.any { it.displayPriority > 0 } }

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                // 标题行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(color),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = course.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (!thisWeek) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MiuixTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "非本周课程",
                            fontSize = 10.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                DetailLine("周次", course.weekText())
                DetailLine("节次", buildString {
                    append(course.sectionText())
                    if (course.timeRange.isNotBlank()) append("   ${course.timeRange}")
                }.trim())
                DetailLine("上课地点", course.location.ifBlank { "待定" })
                if (settings.showTeacher || course.teacher.isNotBlank()) {
                    DetailLine("教师", course.teacher.ifBlank { "未标注" })
                }
                if (course.clazz.isNotBlank()) DetailLine("班级", course.clazz)
                if (course.remark.isNotBlank()) DetailLine("备注", course.remark)

                // ---------- 多教师：周视图显示哪一位（或全部） ----------
                //
                // BETA-v1.3.2 二次修订：原先只提供「1 位 / 全部」二选一，
                // 但一门课有 3~4 位教师时，用户往往想指定**具体某一位**
                // （例如只想看自己班的负责老师），因此改为逐个列出、单选。
                if (course.hasMultipleTeachers()) {
                    val list = course.teacherList()
                    val picked = course.teacherDisplayIndex.coerceIn(0, list.lastIndex)
                    Spacer(Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MiuixTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = "周视图显示教师",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "该课有 ${list.size} 位授课教师，可指定只显示其中一位，或全部列出",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(4.dp))
                        TeacherOption(
                            label = "全部（${list.size} 位）",
                            selected = course.showAllTeachers,
                            enabled = onUpdate != null,
                        ) { onUpdate?.invoke(course.copy(showAllTeachers = true)) }
                        list.forEachIndexed { index, name ->
                            TeacherOption(
                                label = name,
                                selected = !course.showAllTeachers && picked == index,
                                enabled = onUpdate != null,
                            ) {
                                onUpdate?.invoke(
                                    course.copy(
                                        showAllTeachers = false,
                                        teacherDisplayIndex = index,
                                    ),
                                )
                            }
                        }
                    }
                }

                // ---------- 时间冲突 ----------
                if (conflictList.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    val others = conflictList.filter { it.key() != course.key() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MiuixTheme.colorScheme.surfaceVariant)
                            .clickable { showConflicts = !showConflicts }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "时间冲突",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "该时段共有 ${conflictList.size} 门课重叠",
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Text(
                            text = if (showConflicts) "收起" else "展开",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                    if (showConflicts) {
                        Spacer(Modifier.height(6.dp))
                        conflictList.forEach { c ->
                            val isWinner = c.key() == winnerKey
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = onUpdate != null) {
                                        // 手动指定优先：把它的权重抬到所有人之上
                                        onUpdate?.invoke(
                                            c.copy(displayPriority = maxPriority + 1),
                                        )
                                    }
                                    .padding(vertical = 6.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioDot(selected = isWinner)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = c.name,
                                        fontSize = 12.sp,
                                        fontWeight = if (isWinner) FontWeight.Medium
                                        else FontWeight.Normal,
                                    )
                                    Text(
                                        text = buildString {
                                            append(sectionSpanText(c))
                                            append("  ")
                                            append(c.weekText())
                                            if (c.location.isNotBlank()) append("  @${c.location}")
                                        },
                                        fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                                Text(
                                    text = if (isWinner) "优先显示" else "设为优先",
                                    fontSize = 11.sp,
                                    color = if (isWinner) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                        if (hasManualPriority && onUpdate != null) {
                            Text(
                                text = "恢复自动裁决",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(start = 8.dp, top = 4.dp)
                                    .clickable {
                                        conflictList
                                            .filter { it.displayPriority != 0 }
                                            .forEach { onUpdate.invoke(it.copy(displayPriority = 0)) }
                                    },
                            )
                        }
                        Text(
                            text = "默认规则：占课时少的课程优先 —— 例如 1-2 节有 A、1-4 节有 B 时，" +
                                "1-2 节显示 A，第 3-4 节显示 B。你也可以点上面任意一门课，把它设为优先。",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                        if (others.isEmpty()) Spacer(Modifier.height(2.dp))
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("关闭")
                    }
                    if (onDelete != null) {
                        Button(
                            onClick = { confirmDelete = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("删除本周这节")
                        }
                    }
                }
            }
        }
    }

    // 二次确认
    if (confirmDelete && onDelete != null) {
        Dialog(onDismissRequest = { confirmDelete = false }) {
            Card {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("确认删除？", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "将从「${course.name}」中移除第 $week 周这节课，此操作不可撤销。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { confirmDelete = false },
                            modifier = Modifier.weight(1f),
                        ) { Text("取消") }
                        Button(
                            onClick = {
                                confirmDelete = false
                                onDelete()
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("确认删除") }
                    }
                }
            }
        }
    }
}

/** 单选圆点 */
@Composable
private fun RadioDot(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(16.dp)
            .clip(RoundedCornerShape(50))
            .border(
                1.5.dp,
                if (selected) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                RoundedCornerShape(50),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MiuixTheme.colorScheme.primary),
            )
        }
    }
}

/** 单条教师选项（单选圆点 + 姓名），「全部」也是其中一条 */
@Composable
private fun TeacherOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 5.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioDot(selected = selected)
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(60.dp),
        )
        Text(text = value, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

/** 自定义添加课程弹窗 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddCourseDialog(
    weekday: Int,
    startSection: Int,
    onDismiss: () -> Unit,
    onConfirm: (Course) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var day by remember { mutableIntStateOf(weekday.coerceIn(1, 7)) }
    var sec by remember { mutableIntStateOf(startSection.coerceIn(1, ClassTime.TOTAL)) }
    var count by remember { mutableIntStateOf(2) }
    var weeks by remember { mutableStateOf("1-16周") }

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text("添加课程", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))

                // 明确说明添加位置（默认即用户点选的空白处）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "添加位置：第 ${WeekLong[day - 1]} · 第 $sec 节（${ClassTime.startOf(sec)}）\n" +
                            "默认即你点选的空白处，可在下方修改",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(12.dp))

                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "课程名称（必填）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = "教师",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = location,
                    onValueChange = { location = it },
                    label = "上课地点",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = weeks,
                    onValueChange = { weeks = it },
                    label = "周次，如 1-16周 / 3-15周(单周)",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))
                FieldLabel("星期")
                TabRow(
                    tabs = listOf("一", "二", "三", "四", "五", "六", "日"),
                    selectedTabIndex = day - 1,
                    onTabSelected = { day = it + 1 },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))
                FieldLabel("开始节次（第 $sec 节 · ${ClassTime.startOf(sec)}）")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (s in 1..ClassTime.TOTAL) {
                        val sel = s == sec
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (sel) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.surfaceVariant,
                                )
                                .clickable { sec = s },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "$s",
                                fontSize = 12.sp,
                                color = if (sel) MiuixTheme.colorScheme.onPrimary
                                else MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                FieldLabel("连续节数")
                TabRow(
                    tabs = listOf("1 节", "2 节", "3 节", "4 节"),
                    selectedTabIndex = (count - 1).coerceIn(0, 3),
                    onTabSelected = { count = it + 1 },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(
                        onClick = {
                            if (name.isBlank()) return@Button
                            onConfirm(
                                Course(
                                    name = name.trim(),
                                    teacher = teacher.trim(),
                                    location = location.trim(),
                                    weekday = day,
                                    sectionName = "第$sec 节",
                                    sections = "${count}节",
                                    timeRange = ClassTime.rangeOf(sec, count),
                                    weeks = weeks.trim(),
                                    custom = true,
                                    startSection = sec,
                                    sectionCount = count,
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}
