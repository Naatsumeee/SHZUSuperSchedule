package com.shzu.superschedule.ui

import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** 日期 / 教学周 工具 */
object WeekCalc {

    private val cn = listOf("一", "二", "三", "四", "五", "六", "日")

    fun today(): LocalDate = LocalDate.now()

    fun weekdayCn(date: LocalDate): String = cn[date.dayOfWeek.value - 1]

    /** 该日期所在周的周一 */
    fun mondayOf(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - 1).toLong())

    /**
     * 指定教学周的周一。
     * schoolStart 为空时，回退到"本周周一 + (week-1) 周"。
     */
    fun mondayOfWeek(schoolStart: String, week: Int): LocalDate {
        val base = if (schoolStart.isNotBlank()) {
            runCatching { mondayOf(LocalDate.parse(schoolStart)) }.getOrNull()
        } else null
        return (base ?: mondayOf(today())).plusWeeks((week - 1).toLong())
    }

    /** 当前教学周（1 起）。未设置开学日期时恒为 1 */
    fun currentWeek(schoolStart: String, date: LocalDate = today()): Int {
        if (schoolStart.isBlank()) return 1
        return try {
            val start = LocalDate.parse(schoolStart)
            val days = ChronoUnit.DAYS.between(start, date)
            if (days < 0) 1 else (days / 7).toInt() + 1
        } catch (_: Exception) {
            1
        }
    }

    /** 学期结束日期估算：开学日 + 20 周 */
    fun semesterEnd(schoolStart: String): LocalDate? {
        if (schoolStart.isBlank()) return null
        return runCatching { LocalDate.parse(schoolStart).plusWeeks(20) }.getOrNull()
    }

    fun fmt(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"

    fun nowMinutes(): Int {
        val n = LocalTime.now()
        return n.hour * 60 + n.minute
    }

    /** 学期代码，如 "2026-2027-1"。month >= 8 视为第一学期 */
    fun semesterCodeOf(date: LocalDate = today()): String {
        return if (date.monthValue >= 8) {
            "${date.year}-${date.year + 1}-1"
        } else {
            "${date.year - 1}-${date.year}-2"
        }
    }

    /** 由代码推出显示名，如 "2026-2027学年第一学期" */
    fun semesterNameOf(code: String): String {
        val p = code.split("-")
        if (p.size < 3) return code
        val term = if (p[2] == "1") "第一学期" else "第二学期"
        return "${p[0]}-${p[1]}学年$term"
    }

    /** 由学期代码推算开学日期（8 月 / 2 月第一个周一） */
    fun guessStartDate(code: String): String {
        val p = code.split("-")
        if (p.size < 3) return ""
        val year = p[0].toIntOrNull() ?: return ""
        val month = if (p[2] == "1") 9 else 3
        var d = LocalDate.of(year, month, 1)
        // 找到第一个周一
        while (d.dayOfWeek.value != 1) d = d.plusDays(1)
        return d.toString()
    }
}
