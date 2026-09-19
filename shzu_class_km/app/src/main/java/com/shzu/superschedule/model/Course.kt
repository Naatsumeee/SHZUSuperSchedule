package com.shzu.superschedule.model

import kotlinx.serialization.Serializable

/** 一节课 */
@Serializable
data class Course(
    val name: String = "",
    val teacher: String = "",
    val location: String = "",
    /** 1=周一 ... 7=周日 */
    val weekday: Int = 1,
    /** 如 "第一二节" */
    val sectionName: String = "",
    /** 如 "01,02小节" */
    val sections: String = "",
    /** 如 "10:00-11:40" */
    val timeRange: String = "",
    /** 如 "1-16周" / "1-16周(单周)" */
    val weeks: String = "",
    val clazz: String = "",
    val remark: String = "",
    /** 用户手动添加的课程（非教务导入） */
    val custom: Boolean = false,
    /** 起始节次（1-10），用于定位格行；0 表示未知 */
    val startSection: Int = 0,
    /** 持续节数，通常为 1 或 2 */
    val sectionCount: Int = 2,
    /**
     * 时间冲突时的**优先显示权重**（越大越优先抢占重叠的课时）。
     *
     * 0 = 由 App 自动裁决（占课时少的课优先，见 WeekPage.resolveDayBlocks）；
     * >0 = 用户在课程详情里手动指定「优先显示本课」。
     * 值越大越优先，因此手动指定的课永远压过自动裁决。
     */
    val displayPriority: Int = 0,
    /**
     * 多位授课教师时，周视图是否显示全部教师。
     * false（默认）= 只显示一位（由 [teacherDisplayIndex] 指定）；
     * true = 全部列出。可在课程详情（点课程格）里随时切换。
     */
    val showAllTeachers: Boolean = false,
    /**
     * 当 [showAllTeachers] = false 时，周视图显示**第几位**教师（0 起）。
     *
     * -1 = 未指定，取第一位。用户可在课程详情里挑选具体某一位教师
     * （BETA-v1.3.2 起从「1 位/全部」二选一升级为可选任意一位）。
     */
    val teacherDisplayIndex: Int = -1,
) {
    /**
     * 拆分教师名。
     *
     * 教务下发的多教师常见分隔符：`、` `,` `，` `/` `;` `；` 以及空格。
     * 只按明确分隔符拆，避免把「张 三」这种误拆。
     */
    fun teacherList(): List<String> =
        teacher.split(Regex("[,，、;/；]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 是否有多位教师 */
    fun hasMultipleTeachers(): Boolean = teacherList().size > 1

    /** 周视图里实际展示的教师文本（全部 / 指定的某一位） */
    fun displayTeacher(): String {
        val list = teacherList()
        if (list.isEmpty()) return teacher
        if (showAllTeachers) return list.joinToString("、")
        // 越界（教师名单变了）时回退到第一位，避免抛异常
        return list[teacherDisplayIndex.coerceIn(0, list.lastIndex)]
    }

    /** 上课开始时间（分钟数），0 表示未知 */
    fun startMinutes(): Int {
        val m = Regex("(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})").find(timeRange) ?: return 0
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

    /** 上课结束时间（分钟数），0 表示未知 */
    fun endMinutes(): Int {
        val m = Regex("(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})").find(timeRange) ?: return 0
        return m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt()
    }

    /** 开始时间文本，如 "10:00" */
    fun startTimeText(): String =
        Regex("(\\d{1,2}:\\d{2})-").find(timeRange)?.groupValues?.get(1) ?: ""

    /** 结束时间文本，如 "10:45" */
    fun endTimeText(): String =
        Regex("-(\\d{1,2}:\\d{2})").find(timeRange)?.groupValues?.get(1) ?: ""

    /** 所属周次集合（为空表示每周都有） */
    val weekSet: Set<Int> by lazy { parseWeeks(weeks) }

    /** 该课程在第 week 周是否有课 */
    fun activeInWeek(week: Int): Boolean = weekSet.isEmpty() || week in weekSet

    /**
     * **实际占用的课时数**（用于课表绘制）。
     *
     * 兼容历史数据：早期版本可能把 `sectionCount` 存成了 1，
     * 但 `timeRange`（如 `10:00-11:40`）能正确反映它其实跨 2 个课时。
     * 这里取「字段值」与「按时间推断值」的较大者，
     * 保证 1-2 节渲染 2 格、3-4 节渲染 2 格，不会退化成 1 格。
     */
    fun effectiveSectionCount(): Int {
        val fromField = sectionCount.coerceIn(1, ClassTime.TOTAL)
        val fromTime = spanFromTimeRange()
        return maxOf(fromField, fromTime).coerceIn(1, ClassTime.TOTAL)
    }

    /** 由 [timeRange] 与 [startSection] 推断跨几个课时；无法判断时返回 1 */
    fun spanFromTimeRange(): Int {
        val start = startSection
        if (start <= 0) return 1
        val endMin = endMinutes()
        if (endMin <= 0) return 1
        for (n in 1..(ClassTime.TOTAL - start + 1)) {
            val e = minutesOfClock(ClassTime.endOf(start + n - 1))
            if (e < 0) continue
            if (e >= endMin) return n
        }
        return 1
    }

    /** "10:45" -> 645；空/非法返回 -1 */
    private fun minutesOfClock(hhmm: String): Int {
        if (hhmm.isEmpty()) return -1
        val h = hhmm.substringBefore(":").toIntOrNull() ?: return -1
        val m = hhmm.substringAfter(":").toIntOrNull() ?: return -1
        return h * 60 + m
    }

    /** 节次描述，如 "第1-2节" */
    fun sectionText(): String {
        val s = startSection
        if (s <= 0) return sectionName.ifBlank { sections }
        val e = s + sectionCount.coerceAtLeast(1) - 1
        return if (e > s) "第$s-$e 节" else "第$s 节"
    }

    /** 周次描述，如 "1-16周" */
    fun weekText(): String = weeks.ifBlank { "每周" }

    /** 唯一键：用于冲突检测与自定义课的稳定识别 */
    fun key(): String = "$name|$weekday|$startSection|${startMinutes()}"

    companion object {
        /** 解析周次字符串，如 "1-16周"、"1,3,5周"、"1-16周(单周)"、"2-16周(双周)" */
        fun parseWeeks(raw: String): Set<Int> {
            if (raw.isBlank()) return emptySet()
            val isOdd = raw.contains("单周") || raw.contains("(单)")
            val isEven = raw.contains("双周") || raw.contains("(双)")
            val result = mutableSetOf<Int>()
            // 匹配所有 "数字" 或 "数字-数字" 片段
            val re = Regex("(\\d+)\\s*-\\s*(\\d+)|(\\d+)")
            for (m in re.findAll(raw)) {
                if (m.groupValues[1].isNotEmpty()) {
                    val a = m.groupValues[1].toInt()
                    val b = m.groupValues[2].toInt()
                    for (i in minOf(a, b)..maxOf(a, b)) result.add(i)
                } else if (m.groupValues[3].isNotEmpty()) {
                    result.add(m.groupValues[3].toInt())
                }
            }
            return when {
                isOdd -> result.filter { it % 2 == 1 }.toSet()
                isEven -> result.filter { it % 2 == 0 }.toSet()
                else -> result
            }
        }

        /** 根据节次编号推断起始时间（用于自定义课程） */
        fun sectionStartTime(section: Int): String = ClassTime.startOf(section)

        /** 根据节次编号推断结束时间 */
        fun sectionEndTime(section: Int): String = ClassTime.endOf(section)
    }
}

/**
 * 石河子大学作息时间表（10 节）。
 * 上午 1-4 节，下午 5-8 节，晚上 9-10 节。
 *
 * 时间取自教务「学期理论课表」实际下发的节次-时间对应关系：
 *   第一二节 10:00-11:40 / 第三四节 12:10-13:50 / 第五六节 16:00-17:40
 *   第七八节 18:00-19:40 / 第九十节 20:30-22:10
 * 每节课之间的间隔是**下课休息时间**，不参与课表布局。
 */
object ClassTime {

    /** 每节的上课 / 下课时间 */
    val slots: List<Pair<String, String>> = listOf(
        "10:00" to "10:45", // 1
        "10:55" to "11:40", // 2
        "12:10" to "12:55", // 3
        "13:05" to "13:50", // 4
        "16:00" to "16:45", // 5
        "16:55" to "17:40", // 6
        "18:00" to "18:45", // 7
        "18:55" to "19:40", // 8
        "20:30" to "21:15", // 9
        "21:25" to "22:10", // 10
    )

    const val TOTAL = 10

    /** 上午 1-4 节 */
    val MORNING = 1..4

    /** 下午 5-8 节 */
    val AFTERNOON = 5..8

    /** 晚上 9-10 节 */
    val EVENING = 9..10

    fun startOf(section: Int): String =
        slots.getOrNull(section - 1)?.first ?: ""

    fun endOf(section: Int): String =
        slots.getOrNull(section - 1)?.second ?: ""

    fun rangeOf(startSection: Int, count: Int): String {
        if (startSection <= 0) return ""
        val s = startOf(startSection)
        val e = endOf((startSection + count.coerceAtLeast(1) - 1).coerceAtMost(TOTAL))
        if (s.isEmpty()) return ""
        return if (e.isEmpty() || e == s) s else "$s-$e"
    }

    /** 节次所属时段名称 */
    fun periodName(section: Int): String = when (section) {
        in MORNING -> "上午"
        in AFTERNOON -> "下午"
        in EVENING -> "晚上"
        else -> ""
    }
}
