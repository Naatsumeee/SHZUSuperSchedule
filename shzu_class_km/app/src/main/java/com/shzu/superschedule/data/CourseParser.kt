package com.shzu.superschedule.data

import com.shzu.superschedule.model.ClassTime
import com.shzu.superschedule.model.Course
import org.jsoup.Jsoup

/**
 * 强智教务系统课表解析器。
 * 从 WebView 抓取的「学期理论课表」页面 HTML 中解析课程。
 */
object CourseParser {

    private val ws = Regex("\\s+")
    private val secRe = Regex("\\((\\d+(?:,\\d+)*)小节\\)")
    private val timeRe = Regex("(\\d{1,2}:\\d{2}-\\d{1,2}:\\d{2})")
    private val weekRe = Regex("([0-9,\\-]+(?:\\(周\\)|\\(单周\\)|\\(双周\\)))")

    /** 解析课表 HTML，返回课程列表 */
    fun parse(html: String): List<Course> {
        val doc = Jsoup.parse(html)
        val table = doc.selectFirst("table#timetable") ?: run {
            android.util.Log.w(TAG, "未找到 table#timetable，页面可能未登录或结构变化")
            return emptyList()
        }
        val courses = mutableListOf<Course>()

        // 解析表头，拿到「第N节」的节次区间与上下课时间
        val heads = mutableListOf<HeadInfo>()
        for (tr in table.select("tr")) {
            val th = tr.selectFirst("th") ?: continue
            val head = th.text().replace(ws, " ")
            val sectionName = head.substringBefore("(").replace(ws, "")
            val sections = secRe.find(head)?.groupValues?.get(1) ?: ""
            val timeRange = timeRe.find(head)?.groupValues?.get(1) ?: ""
            val (startSec, secCount) = resolveSections(sections, timeRange, sectionName)
            heads.add(HeadInfo(head, sectionName, sections, timeRange, startSec, secCount))
            android.util.Log.d(
                TAG,
                "表头 raw=[$head] 名称=[$sectionName] 小节=[$sections] " +
                    "时间=[$timeRange] -> start=$startSec count=$secCount",
            )

            val tds = tr.select("td")
            for (day in 1..7) {
                if (day > tds.size) break
                val td = tds[day - 1]
                for (div in td.select("div.kbcontent")) {
                    // 同格多门课用长横线分隔
                    val blocks = div.html().split(Regex("-{5,}"))
                    for (block in blocks) {
                        val c = parseBlock(
                            block, day, sectionName, sections, timeRange, startSec, secCount,
                        )
                        if (c != null) courses.add(c)
                    }
                }
            }
        }
        android.util.Log.i(TAG, "解析完成：共 ${courses.size} 条课程记录")
        return courses
    }

    private const val TAG = "CourseParser"

    /** 表头解析结果 */
    private data class HeadInfo(
        val raw: String,
        val sectionName: String,
        val sections: String,
        val timeRange: String,
        val startSec: Int,
        val secCount: Int,
    )

    /** 当前学期（从学期下拉框取选中项） */
    fun currentSemester(html: String): String {
        val doc = Jsoup.parse(html)
        val sel = doc.selectFirst("select#xnxq01id") ?: return ""
        val opt = sel.selectFirst("option[selected]")
        return opt?.attr("value") ?: ""
    }

    /** 从页面标题或下拉框提取学期文本 */
    fun semesterText(html: String): String {
        val doc = Jsoup.parse(html)
        val sel = doc.selectFirst("select#xnxq01id")
        val opt = sel?.selectFirst("option[selected]")
        return opt?.text()?.trim() ?: ""
    }

    /**
     * 从「学期理论课表」页面的「学年学期」下拉框中读取全部可选学期。
     * 强智系统里该下拉框可能是 `select#xnxq01id`，也可能是 `#xnm`(学年) + `#xqm`(学期) 两个联级框，
     * 这里三种都尝试，取到的结果按代码去重后返回。
     */
    fun semesters(html: String): List<String> {
        val doc = Jsoup.parse(html)
        val result = LinkedHashSet<String>()

        // 1) 单选框：select#xnxq01id
        doc.selectFirst("select#xnxq01id")?.select("option")?.forEach { opt ->
            normalizeSemester(opt.attr("value"))?.let { result.add(it) }
        }

        // 2) 联级框：#xnm(学年) + #xqm(学期)
        if (result.isEmpty()) {
            val years = doc.selectFirst("select#xnm")?.select("option")
                ?.mapNotNull { it.attr("value").trim().takeIf { v -> v.length >= 4 } }
                .orEmpty()
            val terms = doc.selectFirst("select#xqm")?.select("option")
                ?.mapNotNull { it.attr("value").trim().takeIf { v -> v.isNotEmpty() } }
                .orEmpty()
            for (y in years) {
                val startYear = y.take(4).toIntOrNull() ?: continue
                val list = if (terms.isEmpty()) listOf("1", "2") else terms
                for (t in list) {
                    val term = t.takeLast(1)
                    if (term == "1" || term == "2") {
                        result.add("$startYear-${startYear + 1}-$term")
                    }
                }
            }
        }

        // 3) 兜底：任意 id 里含 xnxq / xq 的 select
        if (result.isEmpty()) {
            doc.select("select").forEach { sel ->
                val id = sel.id().lowercase()
                if (id.contains("xnxq") || id.contains(" semester".trim()) || id == "xq") {
                    sel.select("option").forEach { opt ->
                        normalizeSemester(opt.attr("value"))?.let { result.add(it) }
                    }
                }
            }
        }

        return result.toList()
    }

    /** 把各种写法的学期值规范化成 "2026-2027-1" */
    private fun normalizeSemester(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        // 已是 2026-2027-1 / 2026-2027-01
        val full = Regex("^(\\d{4})-(\\d{4})-(\\d{1,2})$").find(v)
        if (full != null) {
            val a = full.groupValues[1]
            val b = full.groupValues[2]
            val term = full.groupValues[3].toIntOrNull() ?: return null
            if (term !in 1..2) return null
            return "$a-$b-$term"
        }
        // 2026-2027学年第一学期
        val cn = Regex("(\\d{4})-(\\d{4})").find(v)
        if (cn != null && v.contains("学期")) {
            val term = if (v.contains("一") || v.contains("1")) 1 else 2
            return "${cn.groupValues[1]}-${cn.groupValues[2]}-$term"
        }
        return null
    }

    private fun parseBlock(
        blockHtml: String,
        day: Int,
        sectionName: String,
        sections: String,
        timeRange: String,
        startSecIn: Int,
        secCountIn: Int,
    ): Course? {
        val doc = Jsoup.parse("<div>$blockHtml</div>")
        var name: String? = null
        var teacher = ""
        var location = ""
        var building = ""
        var weekSec = ""
        var clazz = ""
        var remark = ""

        for (f in doc.select("font")) {
            val title = f.attr("title")
            val nm = f.attr("name")
            val text = f.text().trim()
            when {
                title == "教师" -> teacher = text
                title == "教室" -> location = text
                title == "教学楼" -> building = text.replace("【", "").replace("】", "")
                title == "周次(节次)" -> weekSec = text
                nm == "ktmcstr" -> clazz = text.replace("班级：", "")
                nm == "bzstr" -> remark = text.replace("备注：", "")
                title.isEmpty() && text.isNotEmpty() && name == null -> name = text
            }
        }
        if (name.isNullOrEmpty()) return null
        if (location.isEmpty() && building.isNotEmpty()) location = building

        val weeks = weekRe.find(weekSec)?.groupValues?.get(1) ?: weekSec
        // 节次优先用表头算出来的；表头没解析出来时再按时间/名称兜底
        val (startSec, secCount) = if (startSecIn > 0) {
            startSecIn to secCountIn
        } else {
            resolveSections(sections, timeRange, sectionName)
        }
        return Course(
            name = name,
            teacher = teacher,
            location = location,
            weekday = day,
            sectionName = sectionName,
            sections = if (sections.isEmpty()) "" else "${sections}小节",
            timeRange = timeRange,
            weeks = weeks,
            clazz = clazz,
            remark = remark,
            startSection = startSec,
            sectionCount = secCount,
        )
    }

    /**
     * 推断起始节次与节数。
     * 优先用「N小节」编号直接映射，其次用上课时间匹配学校作息表。
     */
    private fun resolveSections(
        sections: String,
        timeRange: String,
        sectionName: String,
    ): Pair<Int, Int> {
        // 1) "01,02小节" 这类编号最可靠
        val nums = Regex("\\d+").findAll(sections)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 1..ClassTime.TOTAL }
            .toList()
        if (nums.isNotEmpty()) {
            val lo = nums.min()
            val hi = nums.max()
            return lo to (hi - lo + 1).coerceIn(1, 4)
        }

        // 2) 用上课/下课时间匹配作息表（最可靠的时间型判据）
        val m = Regex("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})").find(timeRange)
        if (m != null) {
            val startMin = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
            val endMin = m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt()
            val startIdx = (1..ClassTime.TOTAL).firstOrNull {
                minutesOf(ClassTime.startOf(it)) == startMin
            }
            if (startIdx != null) {
                // 从起始节开始，看连续几节的结束时间能覆盖到 endMin
                var count = 1
                while (startIdx + count <= ClassTime.TOTAL) {
                    val endOfThis = minutesOf(ClassTime.endOf(startIdx + count - 1))
                    if (endOfThis >= endMin) break
                    count++
                }
                return startIdx to count.coerceIn(1, 4)
            }
        }

        // 3) 回退：用「第一二节」里的中文数字推断起始节
        val fromName = inferStartFromName(sectionName)
        if (fromName > 0) {
            // 「第一二节」这种含两个中文数字的，说明是连排 2 节
            val cnCount = sectionName.count { ch ->
                "一二三四五六七八九十".contains(ch)
            }
            return fromName to if (cnCount >= 2) 2 else 2
        }

        return 0 to 2
    }

    private fun minutesOf(hhmm: String): Int {
        if (hhmm.isBlank()) return -1
        val h = hhmm.substringBefore(":").toIntOrNull() ?: return -1
        val m = hhmm.substringAfter(":").toIntOrNull() ?: return -1
        return h * 60 + m
    }

    private fun inferStartFromName(name: String): Int {
        val map = mapOf(
            "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5,
            "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10,
        )
        for (ch in name) {
            map[ch.toString()]?.let { return it }
        }
        return 0
    }
}
