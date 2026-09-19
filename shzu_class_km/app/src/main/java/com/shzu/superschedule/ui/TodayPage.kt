package com.shzu.superschedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shzu.superschedule.model.AppSettings
import com.shzu.superschedule.model.Course
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 今日课程。
 *
 * 节数按**科目（课程名）**统计，而不是按课时：
 * 一门课最少占 2 课时，因此一天最多 5 门课（10 课时）。
 */
@Composable
fun TodayPage(
    courses: List<Course>,
    settings: AppSettings,
    /** 底部导航栏高度：内容延伸到栏后，需预留这么多空白，最后一项才不被挡住 */
    bottomInset: Dp = 0.dp,
    onCourseClick: (Course) -> Unit = {},
) {
    val today = WeekCalc.today()
    val week = WeekCalc.currentWeek(settings.schoolStartDate, today)

    // 今日的课：**只显示本周实际开课**的课程，非本周课程一律不进入当日课表
    val list = remember(courses, today, week) {
        courses
            .filter { it.weekday == today.dayOfWeek.value && it.activeInWeek(week) }
            .sortedBy { if (it.startSection > 0) it.startSection else 99 }
    }
    // 存在冲突的课程（同一天、区间重叠）
    val conflictKeys = remember(list) { conflictKeysOf(list) }

    val future = list.filter { it.endMinutes() == 0 || it.endMinutes() >= nowMinutes() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 10.dp,
            end = 10.dp,
            top = 4.dp,
            bottom = 12.dp + bottomInset,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 大标题（与「本周课表」完全同款：字号、留白一致）
        // 外层 LazyColumn 已有 10dp 内边距，这里退掉 10dp，让标题左边缘落在 16dp
        item { PageHeader(title = "今日课程", horizontalPadding = 6.dp) }

        item {
            DayHeader(
                today = today,
                week = week,
                total = list.size,
                subjects = list.map { it.name }.distinct().size,
                remain = future.size,
            )
        }

        if (list.isEmpty()) {
            item {
                Card {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "今天没有课",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        } else {
            items(list, key = { it.key() }) { course ->
                TodayCourseRow(
                    course = course,
                    week = week,
                    settings = settings,
                    conflict = conflictKeys.contains(course.key()),
                    onClick = { onCourseClick(course) },
                )
            }
        }
    }
}

/** 同一天里区间重叠的课程 key 集合 */
private fun conflictKeysOf(list: List<Course>): Set<String> {
    val result = mutableSetOf<String>()
    for (i in list.indices) {
        val a = list[i]
        val sa = spanOfCourse(a) ?: continue
        for (j in i + 1 until list.size) {
            val b = list[j]
            val sb = spanOfCourse(b) ?: continue
            if (sa.first <= sb.last && sb.first <= sa.last) {
                result.add(a.key())
                result.add(b.key())
            }
        }
    }
    return result
}

private fun spanOfCourse(c: Course): IntRange? {
    val s = if (c.startSection > 0) c.startSection else return null
    // 用 effectiveSectionCount()，与周视图的冲突判定保持同一口径
    val n = c.effectiveSectionCount().coerceAtLeast(1)
    return s until (s + n)
}

/** 日期条：比原来更高一点，但不超过一节课格的高度 */
@Composable
private fun DayHeader(
    today: java.time.LocalDate,
    week: Int,
    total: Int,
    subjects: Int,
    remain: Int,
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp, max = 94.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 长条状日期
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "${today.monthValue}/${today.dayOfMonth}",
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "周${WeekCalc.weekdayCn(today)}",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "第 $week 周",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (subjects == 0) "无课" else "$subjects 门课",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (total == 0) "" else "$remain/$total 节待上",
                    fontSize = 10.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 今日课程行（比周视图更宽松的样式） */
@Composable
private fun TodayCourseRow(
    course: Course,
    week: Int,
    settings: AppSettings,
    conflict: Boolean,
    onClick: () -> Unit,
) {
    val thisWeek = course.activeInWeek(week)
    val barColor = CourseColors.resolve(settings, course.name, thisWeek)
    // 字重：默认 Bold，可切换为 Medium
    val nameWeight = if (settings.boldName) {
        if (settings.useBoldWeight) FontWeight.Bold else FontWeight.Medium
    } else {
        FontWeight.Normal
    }

    Card(modifier = Modifier.clickable(onClick = onClick)) {
        Row(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor),
            )
            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = buildString {
                            if (!thisWeek && settings.markOtherWeeks) append("[非本周]")
                            append(course.name)
                        },
                        fontSize = (settings.fontSize + 4f).sp,
                        fontWeight = nameWeight,
                        color = if (thisWeek) MiuixTheme.colorScheme.onSurface
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = if (settings.ellipsizeName) 1 else 3,
                        overflow = if (settings.ellipsizeName) TextOverflow.Ellipsis
                        else TextOverflow.Clip,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (conflict) {
                        Spacer(Modifier.width(6.dp))
                        ConflictMark()
                    }
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = course.sectionText().ifBlank { course.sectionName },
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (course.timeRange.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = course.timeRange,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "@" + course.location.ifBlank { "地点待定" },
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (settings.showTeacher && course.teacher.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            // 多教师课程按「1 位 / 全部」设定取值，与周视图一致
                            text = course.displayTeacher(),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = if (settings.ellipsizeTeacher) 1 else 2,
                            overflow = if (settings.ellipsizeTeacher) TextOverflow.Ellipsis
                            else TextOverflow.Clip,
                        )
                    }
                }
            }

            if (thisWeek && course.endMinutes() > 0 && course.endMinutes() < nowMinutes()) {
                Text(
                    text = "已结束",
                    fontSize = 10.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 冲突标记：与周视图同一套精致图标 */
@Composable
private fun ConflictMark() {
    Box(
        modifier = Modifier
            .height(18.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFFF7043))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.PriorityHigh,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.width(10.dp),
            )
            Spacer(Modifier.width(2.dp))
            Text("冲突", fontSize = 9.sp, color = Color.White)
        }
    }
}

private fun nowMinutes(): Int {
    val n = java.time.LocalTime.now()
    return n.hour * 60 + n.minute
}
