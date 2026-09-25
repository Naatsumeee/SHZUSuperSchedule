package com.shzu.superschedule.ui

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `WeekCalc` 的单元测试。
 *
 * 这 101 行是整个课表与考试标注的**时间基准**，而它有几处刻意的"不对称"设计，
 * 正是最容易在后续重构中被"顺手统一"掉、从而悄悄改变行为的地方：
 *
 * - [WeekCalc.currentWeek] 出错时**兜底成 1**（界面总得显示一周）；
 * - [WeekCalc.weekOf] 出错时**返回 null**（考试标注宁可不标，也不能标错周次）。
 *
 * 两者看起来像重复实现，其实是两种业务诉求。测试把差异钉死。
 */
class WeekCalcTest {

    // ---------------- 周一基准 ----------------

    @Test
    fun `mondayOf 对一周内任意一天都返回同一个周一`() {
        val monday = LocalDate.of(2026, 9, 21) // 周一
        for (offset in 0..6) {
            assertEquals("第 $offset 天", monday, WeekCalc.mondayOf(monday.plusDays(offset.toLong())))
        }
    }

    @Test
    fun `weekdayCn 映射周一到周日`() {
        val monday = LocalDate.of(2026, 9, 21)
        val expect = listOf("一", "二", "三", "四", "五", "六", "日")
        expect.forEachIndexed { i, cn ->
            assertEquals(cn, WeekCalc.weekdayCn(monday.plusDays(i.toLong())))
        }
    }

    // ---------------- 教学周计算 ----------------

    /**
     * 开学日当天就是第 1 周，且**按整天差除以 7**（不是按"第几个周一"）。
     * 这条规则决定了考试周次标注是否与教务一致 —— 教务的周次就是这么数的。
     */
    @Test
    fun `currentWeek 以开学日为第一周并按七天递进`() {
        val start = "2026-09-01"
        assertEquals("开学当天是第 1 周", 1, WeekCalc.currentWeek(start, LocalDate.of(2026, 9, 1)))
        assertEquals("第 6 天仍是第 1 周", 1, WeekCalc.currentWeek(start, LocalDate.of(2026, 9, 7)))
        assertEquals("第 7 天进入第 2 周", 2, WeekCalc.currentWeek(start, LocalDate.of(2026, 9, 8)))
        assertEquals("第 13 周", 13, WeekCalc.currentWeek(start, LocalDate.of(2026, 11, 24)))
    }

    /**
     * 未设置开学日期时恒为第 1 周（而不是崩 / 显示第 0 周）。
     * 首次启动、用户还没填开学日期时走的就是这条。
     */
    @Test
    fun `未设置开学日期时 currentWeek 恒为 1`() {
        assertEquals(1, WeekCalc.currentWeek("", LocalDate.of(2026, 9, 1)))
        assertEquals("空白字符串同样按未设置处理", 1, WeekCalc.currentWeek("   ", LocalDate.of(2026, 9, 1)))
    }

    /** 日期早于开学日（跨学期 / 补考）时也兜底成第 1 周，不出现 0 或负数 */
    @Test
    fun `日期早于开学日时 currentWeek 兜底为 1`() {
        assertEquals(1, WeekCalc.currentWeek("2026-09-01", LocalDate.of(2026, 8, 20)))
    }

    @Test
    fun `非法开学日期不会抛异常`() {
        assertEquals(1, WeekCalc.currentWeek("不是日期", LocalDate.of(2026, 9, 1)))
    }

    /**
     * 🔴 [WeekCalc.weekOf] 与 [WeekCalc.currentWeek] 的关键差异：
     * 它用于「考试时间 → 第几周」的标注，**算不出就返回 null**，
     * 绝不兜底成第 1 周 —— 那会让寒假补考被标成"第 1 周"，比不标更误导。
     */
    @Test
    fun `weekOf 在无法判定时返回 null 而不兜底`() {
        assertNull("未设置开学日期 → null", WeekCalc.weekOf("", LocalDate.of(2026, 11, 24)))
        assertNull("日期早于开学日 → null", WeekCalc.weekOf("2026-09-01", LocalDate.of(2026, 8, 20)))
        assertNull("非法日期 → null", WeekCalc.weekOf("abc", LocalDate.of(2026, 11, 24)))
    }

    @Test
    fun `weekOf 能算出周次且与 currentWeek 口径一致`() {
        val start = "2026-09-01"
        val exam = LocalDate.of(2026, 11, 28)
        assertEquals("同一日期两个函数的周次必须一致", WeekCalc.currentWeek(start, exam), WeekCalc.weekOf(start, exam))
        assertEquals(13, WeekCalc.weekOf(start, exam))
    }

    // ---------------- 学期代码 ----------------

    @Test
    fun `semesterCodeOf 以八月为第一学期分界`() {
        assertEquals("2026-2027-1", WeekCalc.semesterCodeOf(LocalDate.of(2026, 8, 1)))
        assertEquals("2026-2027-1", WeekCalc.semesterCodeOf(LocalDate.of(2026, 12, 31)))
        assertEquals("2026-2027-2", WeekCalc.semesterCodeOf(LocalDate.of(2027, 1, 1)))
        assertEquals("2026-2027-2", WeekCalc.semesterCodeOf(LocalDate.of(2027, 7, 31)))
    }

    @Test
    fun `semesterNameOf 由代码推出中文名`() {
        assertEquals("2026-2027学年第一学期", WeekCalc.semesterNameOf("2026-2027-1"))
        assertEquals("2026-2027学年第二学期", WeekCalc.semesterNameOf("2026-2027-2"))
        assertEquals("格式不符时原样返回", "2026", WeekCalc.semesterNameOf("2026"))
    }

    /**
     * 开学日推定：第一学期取 9 月第一个周一、第二学期取 3 月第一个周一。
     * ⚠️ 这个函数名里的 "8 月" 与实现（9 月）在旧注释里不一致，
     * 测试以**实现**为准把真实行为钉住，避免重构时按注释改错。
     */
    @Test
    fun `guessStartDate 取当月第一个周一`() {
        // 2026-09-01 是周二，所以 9 月第一个周一是 09-07（不是 09-01）
        val first = WeekCalc.guessStartDate("2026-2027-1")
        assertEquals(LocalDate.of(2026, 9, 7), LocalDate.parse(first))
        assertEquals("推出来的必须是周一", 1, LocalDate.parse(first).dayOfWeek.value)

        val second = WeekCalc.guessStartDate("2026-2027-2")
        assertEquals("2026-03-01 是周日，第一个周一是 03-02", LocalDate.of(2026, 3, 2), LocalDate.parse(second))
        assertEquals(1, LocalDate.parse(second).dayOfWeek.value)
    }

    @Test
    fun `guessStartDate 对非法代码返回空串`() {
        assertEquals("", WeekCalc.guessStartDate("2026"))
        assertEquals("", WeekCalc.guessStartDate("abcd-2027-1"))
    }

    // ---------------- 其它 ----------------

    @Test
    fun `semesterEnd 为开学日加二十周`() {
        assertEquals(LocalDate.of(2026, 9, 1).plusWeeks(20), WeekCalc.semesterEnd("2026-09-01"))
        assertNull("未设置开学日期时无结束日", WeekCalc.semesterEnd(""))
    }

    @Test
    fun `mondayOfWeek 按教学周定位周一`() {
        val start = "2026-09-01"
        val startDate = LocalDate.parse(start)
        val w1 = WeekCalc.mondayOfWeek(start, 1)
        assertEquals("开学日所在周的周一", WeekCalc.mondayOf(startDate), w1)
        assertEquals("第 2 周比第 1 周晚 7 天", w1.plusWeeks(1), WeekCalc.mondayOfWeek(start, 2))
    }

    @Test
    fun `fmt 输出中文月日`() {
        assertEquals("9月21日", WeekCalc.fmt(LocalDate.of(2026, 9, 21)))
        assertEquals("12月1日", WeekCalc.fmt(LocalDate.of(2026, 12, 1)))
    }
}
