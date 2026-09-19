package com.shzu.superschedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shzu.superschedule.model.AppSettings
import com.shzu.superschedule.model.ClassTime
import com.shzu.superschedule.model.Course
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val BASE = 200
private val WEEK_CN = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 节次标签列宽 */
private val SECTION_COL_WIDTH = 34.dp

/**
 * 单元格基准行高（1 课时 = 1 行）。
 *
 * 读取设置里的 `rowHeight`（「课程高度」滑块）。以这个值为**唯一标尺**，
 * 整张课表被严格划分为 **10 行等距** 的固定表格：
 * 第 1-2 行间距 == 第 2-3 行间距 == … == 第 9-10 行间距，绝不会出现间距不等。
 */
private val ROW_HEIGHT_MIN = 34.dp
private val ROW_HEIGHT_MAX = 96.dp

/** 单元格之间的水平窄缝（仅左右方向，垂直方向不加，保证行距严格相等） */
private val CELL_GAP = 1.dp

/** 课表容器左右内边距（网格线定位必须与它一致，否则竖线会错位） */
private val GRID_H_PADDING = 4.dp

/**
 * 周课表。
 *
 * 布局模型：整个课表 = **10 行 × 7 列** 的固定等距表格。
 * - 10 行对应 1-10 节课时，行高完全相等（= 设置里的 `rowHeight`）；
 * - 课与课之间的"下课时间"**不参与布局**，不额外留白；
 * - 一门课占用 N 个课时，就渲染 **N 格**的高度。
 */
@Composable
fun WeekPage(
    courses: List<Course>,
    settings: AppSettings,
    /** 底部导航栏高度：内容延伸到栏后，需预留这么多空白 */
    bottomInset: Dp = 0.dp,
    onCourseClick: (Course, Int) -> Unit = { _, _ -> },
    onEmptyClick: (Int, Int) -> Unit = { _, _ -> },
) {
    val currentWeek = WeekCalc.currentWeek(settings.schoolStartDate)
    val pagerState = rememberPagerState(initialPage = BASE) { BASE * 2 + 1 }
    val scope = rememberCoroutineScope()
    val shownWeek = currentWeek + (pagerState.currentPage - BASE)

    // 行高标尺：来自设置「课程高度」滑块，clamp 到合法范围
    val rowHeight: Dp = settings.rowHeight
        .coerceIn(ROW_HEIGHT_MIN.value, ROW_HEIGHT_MAX.value).dp

    // 非本周课程开关关闭时，直接过滤掉，不再绘制
    val visibleCourses = remember(courses, settings.showOtherWeeks) {
        if (settings.showOtherWeeks) courses
        else courses.filter { it.weekSet.isEmpty() || it.activeInWeek(currentWeek) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 页面顶部标题栏（与今日课程页同款）
        PageHeader(title = "本周课表")

        // 周切换栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
            }) {
                Icon(imageVector = Icons.Filled.ChevronLeft, contentDescription = "上一周")
            }
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "第 $shownWeek 周",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (shownWeek == currentWeek) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text("本周", fontSize = 10.sp, color = MiuixTheme.colorScheme.primary)
                    }
                }
                if (settings.semester.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = settings.semester,
                        fontSize = 10.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            IconButton(onClick = {
                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
            }) {
                Icon(imageVector = Icons.Filled.ChevronRight, contentDescription = "下一周")
            }
        }

        WeekStrip(week = shownWeek, settings = settings)

        // 星期表头
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = GRID_H_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(SECTION_COL_WIDTH))
            for (i in 1..7) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = WEEK_CN[i - 1],
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (i >= 6) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            val week = currentWeek + (page - BASE)
            WeekGrid(
                courses = visibleCourses,
                week = week,
                settings = settings,
                rowHeight = rowHeight,
                bottomInset = bottomInset,
                onCourseClick = { onCourseClick(it, week) },
                onEmptyClick = { day, sec -> onEmptyClick(day, sec) },
            )
        }
    }
}

/** 周日期条 */
@Composable
private fun WeekStrip(week: Int, settings: AppSettings) {
    val monday = WeekCalc.mondayOfWeek(settings.schoolStartDate, week)
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = GRID_H_PADDING, vertical = 2.dp),
    ) {
        Spacer(Modifier.width(SECTION_COL_WIDTH))
        for (i in 0..6) {
            val d = monday.plusDays(i.toLong())
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = "${d.monthValue}/${d.dayOfMonth}",
                    fontSize = 9.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/**
 * 一天里的一个占位块。
 * [from]..[to] 是占用的课时区间（含端点），高 = (to-from+1) × 行高。
 */
internal data class Block(
    val course: Course,
    val from: Int,
    val to: Int,
    /** 冲突课程数（含自己），>1 即存在时间冲突 */
    val conflictCount: Int,
    /** 该块所属星期（1..7），供浮层定位使用 */
    val day: Int = 0,
    /**
     * 非本周块合并后的周次提示，如 "11,13 周" / "9-14 周"。
     * 为空表示该块是本周课程，或只有唯一一条记录（此时直接显示 course.weeks）。
     */
    val mergedWeeks: String = "",
)

/** 课表里用于冲突裁决的中间结构 */
private data class Slot(val course: Course, val from: Int, val to: Int)

/** 把一串课时号切成连续区间，如 [1,2,4,5] -> [1..2, 4..5] */
private fun contiguousRuns(sections: List<Int>): List<IntRange> {
    if (sections.isEmpty()) return emptyList()
    val sorted = sections.sorted()
    val runs = mutableListOf<IntRange>()
    var start = sorted.first()
    var prev = start
    for (i in 1 until sorted.size) {
        val cur = sorted[i]
        if (cur == prev + 1) {
            prev = cur
            continue
        }
        runs += start..prev
        start = cur
        prev = cur
    }
    runs += start..prev
    return runs
}

/**
 * 计算某天在第 week 周应绘制的块（**含时间冲突裁决**）。
 *
 * ## 冲突裁决规则（BETA-v1.3.2 新增）
 *
 * 同一格里排了两门课时，不再让它们重叠压字，而是**把课时判给其中一门**：
 *
 * 1. 用户手动指定「优先显示」的课（`course.displayPriority` 大者）绝对优先；
 * 2. 否则**占课时少的课优先** —— 这正是用户要的效果：
 *    1-2 节排了 A（2 课时）、1-4 节排了 B（4 课时）时，
 *    1-2 节显示 A，第 3-4 节留给 B；
 * 3. 再并列时，起始节次早的、课程名靠前的优先。
 *
 * 抢不到的那部分课时会被从该课的区间里剔除；如果一整门课都被挤掉，
 * 它就不出现在课表上（仍可在冲突课程的详情弹窗里看到并调整）。
 *
 * ## 非本周课程
 *
 * 只做「空位补白」：必须**完全落在没有被本周课程占用的课时里**，
 * 且彼此同区间只保留一块（合并周次提示），避免叠字。
 */
internal fun resolveDayBlocks(
    courses: List<Course>,
    day: Int,
    week: Int,
    showOtherWeeks: Boolean = true,
): List<Block> {
    val dayCourses = courses
        .filter { it.weekday == day }
        .mapNotNull { c ->
            val s = if (c.startSection > 0) c.startSection else sectionFromTime(c)
            if (s <= 0) return@mapNotNull null
            // 课时数由 effectiveSectionCount() 综合字段值与上下课时间得出，
            // 保证「1-2 节 = 2 格」「3-4 节 = 2 格」这类连排课正确渲染。
            val n = c.effectiveSectionCount().coerceIn(1, ClassTime.TOTAL - s + 1)
            Slot(c, s, s + n - 1)
        }

    val inWeek = dayCourses.filter { it.course.activeInWeek(week) }
    val notInWeek = if (showOtherWeeks) {
        dayCourses.filterNot { it.course.activeInWeek(week) }
    } else {
        emptyList()
    }

    // 每门课的冲突数（按**原始**区间统计，用于角标）
    val conflictOf: Map<String, Int> = inWeek.associate { a ->
        a.course.key() to (1 + inWeek.count { b ->
            b.course.key() != a.course.key() && b.from <= a.to && a.from <= b.to
        })
    }

    // ---------- 1) 本周课程：按优先级裁决课时归属 ----------
    val ordered = inWeek.sortedWith(
        compareByDescending<Slot> { it.course.displayPriority }
            .thenBy { it.to - it.from }   // 占课时少的优先（更"专一"）
            .thenBy { it.from }           // 起始更早的优先
            .thenBy { it.course.name },
    )

    val taken = mutableSetOf<Int>()
    val weekBlocks = mutableListOf<Block>()
    for (slot in ordered) {
        val free = (slot.from..slot.to).filter { it !in taken }
        if (free.isEmpty()) continue          // 整段都被更高优先级的课占走
        taken += free
        // 残余课时可能被切成多段（如 B 的 1-4 被 A 占了 2-3，只剩 1 和 4）
        for (run in contiguousRuns(free)) {
            weekBlocks += Block(
                course = slot.course,
                from = run.first,
                to = run.last,
                conflictCount = conflictOf[slot.course.key()] ?: 1,
                day = day,
            )
        }
    }

    // ---------- 2) 非本周课程：只在完全空闲的课时上补白 ----------
    val candidates = notInWeek.filter { slot -> (slot.from..slot.to).none { it in taken } }

    val groups = LinkedHashMap<String, MutableList<Slot>>()
    for (s in candidates) {
        groups.getOrPut("${s.from}-${s.to}") { mutableListOf() }.add(s)
    }

    val otherBlocks = groups.values.map { group ->
        val head = group.first()
        Block(
            course = head.course,
            from = head.from,
            to = head.to,
            conflictCount = 1,
            day = day,
            mergedWeeks = if (group.size == 1) "" else mergeWeekLabels(group.map { it.course.weeks }),
        )
    }

    return weekBlocks + otherBlocks
}

/**
 * 合并多条周次描述，如 ["11(周)", "13(周)"] -> "11,13 周"。
 * 相同名称的课只算一次；无有效周次时返回空串。
 */
private fun mergeWeekLabels(raws: List<String>): String {
    val labels = raws
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { it.replace("(周)", "").replace("周", "").trim() }
        .distinct()
    if (labels.isEmpty()) return ""
    return labels.joinToString(",") + " 周"
}

private fun sectionFromTime(c: Course): Int {
    val m = c.startMinutes()
    if (m <= 0) return 0
    for (i in 1..ClassTime.TOTAL) {
        val t = ClassTime.startOf(i)
        if (t.isEmpty()) continue
        val h = t.substringBefore(":").toIntOrNull() ?: continue
        val mm = t.substringAfter(":").toIntOrNull() ?: 0
        if (h * 60 + mm == m) return i
    }
    return 0
}

@Composable
private fun WeekGrid(
    courses: List<Course>,
    week: Int,
    settings: AppSettings,
    rowHeight: Dp,
    /** 底部导航栏高度：课表底部预留这么多空白 */
    bottomInset: Dp = 0.dp,
    onCourseClick: (Course) -> Unit,
    onEmptyClick: (Int, Int) -> Unit,
) {
    // 每天已占用的课时（被长格覆盖的行不再绘制空格）
    val dayOccupied = remember(courses, week, settings.showOtherWeeks) {
        val map = mutableMapOf<Int, MutableSet<Int>>()
        val blockMap = mutableMapOf<Int, List<Block>>()
        for (day in 1..7) {
            val blocks = resolveDayBlocks(courses, day, week, settings.showOtherWeeks)
            blockMap[day] = blocks
            val set = mutableSetOf<Int>()
            for (b in blocks) {
                for (s in b.from..b.to) set.add(s)
            }
            map[day] = set
        }
        blockMap to map
    }
    val blocksByDay = dayOccupied.first

    /**
     * 扁平化后的绘制顺序：day 1..7 依次、每天按起始节次升序。
     *
     * 浮层 `content` 与 `Layout` 的测量/摆放都严格按这个列表遍历，
     * 保证 measurables[i] 一定对应 orderedBlocks[i]，不会错位。
     */
    val orderedBlocks: List<Block> = remember(blocksByDay) {
        (1..7).flatMap { d -> blocksByDay[d].orEmpty().sortedBy { it.from } }
    }

    // 行距：每行 = rowHeight + 上下各 CELL_GAP 的垂直 padding
    val rowStride: Dp = rowHeight + CELL_GAP * 2
    // 表格整体高度（10 行 × 行距）
    val gridHeight: Dp = rowStride * ClassTime.TOTAL

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(gridHeight)
                .background(MiuixTheme.colorScheme.surface)
                .gridBackground(
                    enabled = settings.showGridLines,
                    rowHeight = rowHeight,
                    sectionColWidth = SECTION_COL_WIDTH,
                    cellGap = CELL_GAP,
                    horizontalPadding = GRID_H_PADDING,
                )
                .padding(horizontal = GRID_H_PADDING),
        ) {
            // ---------- 底层：严格 10 行等距网格 ----------
            Column(modifier = Modifier.fillMaxSize()) {
                for (sec in 1..ClassTime.TOTAL) {
                    val slot = ClassTime.slots[sec - 1]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowStride)
                            .padding(vertical = CELL_GAP),
                    ) {
                        // 节次标签
                        Box(
                            modifier = Modifier.width(SECTION_COL_WIDTH).fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = "$sec",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = slot.first,
                                    fontSize = 8.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                )
                                Text(
                                    text = slot.second,
                                    fontSize = 8.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                )
                            }
                        }
                        // 7 天空位底格
                        for (day in 1..7) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = CELL_GAP),
                            ) {
                                if (sec in dayOccupied.second[day].orEmpty()) {
                                    // 该行已被课程长格覆盖，只留占位不画空格
                                    Spacer(Modifier.fillMaxSize())
                                } else {
                                    EmptyCell(
                                        settings = settings,
                                        onClick = { onEmptyClick(day, sec) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---------- 上层：课程浮层 ----------
            // 关键：课程块不能放进上面 height(rowStride) 的 Row 里，
            // 否则跨 2 行的长格会被父容器约束压成 1 行高。
            // 这里用 Layout 按「行号 × 行距」精确定位，长格才能真实铺满 N 行。
            val density = androidx.compose.ui.platform.LocalDensity.current
            val sectionColPx = with(density) { SECTION_COL_WIDTH.toPx() }
            val rowStridePx = with(density) { rowStride.toPx() }
            val cellGapPx = with(density) { CELL_GAP.toPx() }

            androidx.compose.ui.layout.Layout(
                content = {
                    orderedBlocks.forEach { block ->
                        val span = (block.to - block.from + 1).coerceAtLeast(1)
                        key(block.day, block.from, block.course.key()) {
                            CourseCell(
                                block = block,
                                week = week,
                                settings = settings,
                                heightDp = rowHeight * span +
                                    CELL_GAP * 2 * (span - 1).toFloat(),
                                onClick = { onCourseClick(block.course) },
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) { measurables, constraints ->
                val totalWidth = constraints.maxWidth.toFloat()
                // 7 列可用内容宽（扣掉节次标签列），每列左右各留 CELL_GAP
                val contentWidth = (totalWidth - sectionColPx).coerceAtLeast(1f)
                val columnStride = contentWidth / 7f
                val cellWidthPx = (columnStride - cellGapPx * 2).coerceAtLeast(1f)

                // 先按「列宽 × 跨行高」精确约束测量每一个课程块
                val placeables = measurables.mapIndexed { index, measurable ->
                    val block = orderedBlocks[index]
                    val span = (block.to - block.from + 1).coerceAtLeast(1)
                    val hPx = (rowStridePx * span - cellGapPx * 2).coerceAtLeast(1f)
                    measurable.measure(
                        androidx.compose.ui.unit.Constraints.fixed(
                            width = cellWidthPx.toInt(),
                            height = hPx.toInt(),
                        ),
                    )
                }

                layout(constraints.maxWidth, constraints.maxHeight) {
                    orderedBlocks.forEachIndexed { index, block ->
                        val p = placeables[index]
                        // x = 节次列宽 + 前面 (day-1) 列总宽 + 左侧缝隙
                        val x = sectionColPx + columnStride * (block.day - 1) + cellGapPx
                        // y = 前面 (from-1) 行的总高 + 上端缝隙
                        val y = rowStridePx * (block.from - 1) + cellGapPx
                        p.placeRelative(x = x.toInt(), y = y.toInt())
                    }
                }
            }
        }
        // 底部预留底栏高度：课表可以继续上滚，最后一行不会被半透明底栏压住
        Spacer(Modifier.height(bottomInset))
    }
}

/** 空位：点击可添加课程 */
@Composable
private fun EmptyCell(
    settings: AppSettings,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(4.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .then(
                if (settings.showCellBorder) Modifier.border(
                    0.5.dp,
                    MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.28f),
                    RoundedCornerShape(4.dp),
                ) else Modifier,
            )
            .clickable(onClick = onClick),
    )
}

/** 课程格：铺满 from..to 的全部行 */
@Composable
private fun CourseCell(
    block: Block,
    week: Int,
    settings: AppSettings,
    heightDp: Dp,
    onClick: () -> Unit,
) {
    val display = block.course
    val thisWeek = display.activeInWeek(week)
    // 非本周格子向课表背景色淡化，且**保持不透明**，
    // 这样底层的网格线与边线不会被透出来（BETA-v1.3.2 修复）
    val surface = MiuixTheme.colorScheme.surface
    val bg = CourseColors.resolve(settings, display.name, thisWeek, surface)
    val fg = if (thisWeek) CourseColors.textColorOf(settings, bg)
    else CourseColors.fadedTextColor(settings, bg)
    val pad = settings.cellPadding.dp
    val align = when (settings.alignment) {
        1 -> TextAlign.Start
        2 -> TextAlign.Justify
        else -> TextAlign.Center
    }
    val hAlign = when (settings.alignment) {
        1, 2 -> Alignment.Start
        else -> Alignment.CenterHorizontally
    }
    // 加粗选项：默认 Bold，可在设置里切换为 Medium
    val nameWeight = if (settings.boldName) {
        if (settings.useBoldWeight) FontWeight.Bold else FontWeight.Medium
    } else {
        FontWeight.Normal
    }

    // [非本周] 前缀与合并周次提示各占一行，需要为它们额外让出行数预算，
    // 保证"课程名本身最多 3 行"这一规则不被前缀挤掉
    val extraNameLines = if (!thisWeek) {
        (if (settings.markOtherWeeks) 1 else 0) +
            (if (block.mergedWeeks.isNotEmpty()) 1 else 0)
    } else {
        0
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .then(
                if (settings.showCellBorder) Modifier.border(
                    0.5.dp,
                    fg.copy(alpha = 0.3f),
                    RoundedCornerShape(6.dp),
                ) else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(pad),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopStart),
            horizontalAlignment = hAlign,
        ) {
            Text(
                text = buildString {
                    if (!thisWeek && settings.markOtherWeeks) append("[非本周]\n")
                    append(display.name)
                    // 非本周空位若合并了多条周次记录，补一行周次提示
                    if (!thisWeek && block.mergedWeeks.isNotEmpty()) {
                        append("\n")
                        append(block.mergedWeeks)
                    }
                },
                fontSize = settings.fontSize.sp,
                fontWeight = nameWeight,
                color = fg,
                textAlign = align,
                lineHeight = (settings.fontSize + 2f).sp,
                // 课程名超过 3 行即隐藏后面的内容
                maxLines = if (settings.ellipsizeName) 3 + extraNameLines else 12,
                overflow = if (settings.ellipsizeName) TextOverflow.Ellipsis
                else TextOverflow.Clip,
            )
            if (display.location.isNotBlank()) {
                Text(
                    text = "@" + display.location,
                    fontSize = (settings.fontSize - 2f).coerceAtLeast(8f).sp,
                    color = fg,
                    textAlign = align,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (settings.showTeacher && display.teacher.isNotBlank()) {
                Text(
                    // 多教师课程按「显示 1 位 / 全部」的设定取文本
                    text = display.displayTeacher(),
                    fontSize = (settings.fontSize - 2f).coerceAtLeast(8f).sp,
                    color = fg,
                    textAlign = align,
                    // 教师名超过 2 行即隐藏后面的内容
                    maxLines = if (settings.ellipsizeTeacher) 2 else 4,
                    overflow = if (settings.ellipsizeTeacher) TextOverflow.Ellipsis
                    else TextOverflow.Clip,
                )
            }
        }

        // 冲突角标：精致的圆形警示图标
        if (block.conflictCount > 1) {
            ConflictBadge(
                tint = fg,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/** 冲突角标：圆形底 + 高优先级图标，精致小巧 */
@Composable
private fun ConflictBadge(tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(16.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFFF7043))
            .border(
                1.dp,
                Color.White.copy(alpha = 0.85f),
                RoundedCornerShape(50),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PriorityHigh,
            contentDescription = "时间冲突",
            tint = Color.White,
            modifier = Modifier.size(11.dp),
        )
    }
}

/**
 * 课表背景网格：10 行 × 7 列的**虚线**网格，横竖均有。
 *
 * ## BETA-v1.3.2 对齐修复
 *
 * 旧实现把竖线画在 `size.width / 7 * i`，但真实列布局是
 * 「左右各留 [horizontalPadding] → 节次标签列 [sectionColWidth] → 剩下 7 等分」，
 * 两者根本不是一个坐标系，所以竖线和星期列**永远对不齐**（越靠右偏得越多）。
 *
 * 现在按真实列布局计算：第 i 条竖线位于
 * `horizontalPadding + sectionColWidth + columnStride * i`，
 * 与课程格浮层的 `x = sectionColPx + columnStride * (day-1) + cellGap` 同源。
 *
 * 横线只画在 10 行之间的 9 条分界线上，且从节次标签列右侧起笔，
 * 不再横穿左侧的节次/时间文字。
 */
@Composable
internal fun Modifier.gridBackground(
    enabled: Boolean,
    rowHeight: Dp,
    sectionColWidth: Dp = 34.dp,
    cellGap: Dp = 1.dp,
    horizontalPadding: Dp = 4.dp,
): Modifier {
    if (!enabled) return this
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rowPx = with(density) { rowHeight.toPx() }
    val gapPx = with(density) { cellGap.toPx() }
    val sectionColPx = with(density) { sectionColWidth.toPx() }
    val hPadPx = with(density) { horizontalPadding.toPx() }

    return this.drawBehind {
        val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 7f), 0f)
        val lineColor = Color(0x668A8A8A)

        // 网格区起点（跳过左侧内边距与节次标签列）
        val gridStart = hPadPx + sectionColPx
        val gridWidth = (size.width - gridStart - hPadPx).coerceAtLeast(1f)
        val columnStride = gridWidth / 7f

        // 竖线：7 列之间的 6 条内部分界线
        for (i in 1..6) {
            val x = gridStart + columnStride * i
            drawLine(
                color = lineColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1.6f,
                pathEffect = dash,
            )
        }

        // 横线：10 行之间的 9 条分界线，严格等距
        val stride = rowPx + gapPx * 2
        for (k in 1 until ClassTime.TOTAL) {
            val y = stride * k
            if (y > size.height) break
            drawLine(
                color = lineColor,
                start = Offset(gridStart, y),
                end = Offset(size.width, y),
                strokeWidth = 1.6f,
                pathEffect = dash,
            )
        }
    }
}
