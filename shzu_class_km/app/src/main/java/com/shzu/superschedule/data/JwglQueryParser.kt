package com.shzu.superschedule.data

import android.util.Log
import com.shzu.superschedule.model.QueryTable
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 强智教务「考试安排 / 课程成绩 / 等级考试成绩」三类查询页的**通用**解析器。
 *
 * ## 为什么不做逐字段建模
 *
 * 三类页面结构互不相同，而且强智各校版式、甚至同校不同模块的表格 id 都不一样
 * （`dataList` / `tabList` / `tbllist` …），学校升级一次就可能全变。
 * 所以这里只做一件事：**把页面上的「结果表」找出来，读成「表头 + 行」**。
 * 只要教务还在用表格展示数据，改版也不会解析失败。
 *
 * ## 找表的优先级
 *
 * 1. 常见的结果表 id / class（见 [PREFERRED]）
 * 2. 兜底：页面上**有效数据行最多**的表格（有效 = 至少 2 个非空单元格）
 *
 * 表格里的嵌套表格会被跳过 —— 强智的布局表常套着数据表，
 * 若用 `table.select("tr")` 会把内外两层的行混在一起。
 */
object JwglQueryParser {

    private const val TAG = "JwglQueryParser"
    private val ws = Regex("\\s+")

    /** 强智系统里结果表常见的 id / class，按命中优先级排列 */
    private val PREFERRED = listOf(
        "table#dataList",
        "table#tabList",
        "table.tbllist",
        "table#dataTable",
        "table.tablelist",
        "table.datelist",
        "table#cjTable",
        "table#ksTable",
    )

    /** 疑似未登录 / 认证跳转页的特征 */
    private val LOGIN_HINTS = listOf(
        "casLogin", "统一身份认证", "用户登录", "请输入学号", "loginForm",
    )

    /**
     * 解析一次查询结果。
     *
     * @param kind 查询类型 key，写回 [QueryTable.kind]
     * @return 解析失败（未登录 / 还没进到查询页 / 表格改版到认不出）时返回 null
     */
    fun parse(html: String, kind: String, fetchedAt: String): QueryTable? {
        if (html.isBlank()) {
            Log.w(TAG, "HTML 为空")
            return null
        }
        val doc = Jsoup.parse(html)
        val title = pageTitle(doc)

        if (looksLikeLoginPage(doc, title)) {
            Log.w(TAG, "疑似登录页/认证页，放弃解析。title=$title")
            return null
        }

        val table = pickTable(doc) ?: run {
            Log.w(TAG, "未找到可用结果表。title=$title")
            return null
        }
        val (headers, rows) = readTable(table)
        Log.i(
            TAG,
            "解析 $kind：title=$title 表头=${headers.size} 列 数据=${rows.size} 行",
        )
        if (rows.isEmpty()) return null

        return QueryTable(
            kind = kind,
            title = title,
            headers = headers,
            rows = rows,
            fetchedAt = fetchedAt,
        )
    }

    // ---------------- 找表 ----------------

    private fun pickTable(doc: Document): Element? {
        for (sel in PREFERRED) {
            val t = doc.selectFirst(sel) ?: continue
            if (dataRowCount(t) > 0) {
                Log.d(TAG, "命中优先选择器 $sel（${dataRowCount(t)} 行）")
                return t
            }
        }
        // 兜底：数据行最多的表
        val best = doc.select("table")
            .map { it to dataRowCount(it) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
        if (best != null) {
            Log.d(TAG, "兜底选中数据行最多的表（${best.second} 行）")
        }
        return best?.first
    }

    /** 有效数据行数：直接子行里，至少 2 个非空单元格的行 */
    private fun dataRowCount(table: Element): Int =
        directRows(table).count { tr ->
            tr.select("> td").map { clean(it.text()) }.count { it.isNotEmpty() } >= 2
        }

    /**
     * 表格的直接子行。
     * 优先 `tbody > tr`；没有 tbody 时退回 `table > tr`；
     * 两者都没有再退到全部 tr（并剔除嵌套表格里的行）。
     */
    private fun directRows(table: Element): List<Element> {
        val viaTbody = table.select("> tbody > tr")
        if (viaTbody.isNotEmpty()) return viaTbody
        val direct = table.select("> tr")
        if (direct.isNotEmpty()) return direct
        return table.select("tr").filter { tr ->
            // 向上找最近的 table 祖先，是自己才算本表的行
            var p = tr.parent()
            while (p != null && p.normalName() != "table") p = p.parent()
            p === table
        }
    }

    // ---------------- 读表 ----------------

    private fun readTable(table: Element): Pair<List<String>, List<List<String>>> {
        var headers: List<String> = table.selectFirst("> thead")
            ?.select("tr")
            ?.firstOrNull()
            ?.select("th, td")
            ?.map { clean(it.text()) }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        val rows = mutableListOf<List<String>>()
        for (tr in directRows(table)) {
            // 表头行（thead 里的）不重复收进数据
            val inThead = tr.parents().any { it.normalName() == "thead" }
            if (inThead) continue

            val cells = tr.select("> th, > td").map { clean(it.text()) }
            if (cells.isEmpty() || cells.all { it.isBlank() }) continue

            // 没有 thead 时，若首行含 th 且不含数字 → 当作表头
            if (headers.isEmpty() && rows.isEmpty() && tr.select("> th").isNotEmpty()) {
                val h = cells.filter { it.isNotEmpty() }
                if (h.size >= 2) {
                    headers = h
                    continue
                }
            }
            rows.add(cells)
        }
        return headers to rows
    }

    // ---------------- 页面元信息 ----------------

    /** 页面标题：优先页面内标题栏，兜底 <title> */
    private fun pageTitle(doc: Document): String {
        for (sel in listOf(".Nsb_title", "#topTitle", "h2", ".main_title", ".pagetitle")) {
            val t = doc.selectFirst(sel)?.text()?.let { clean(it) }
            if (!t.isNullOrBlank() && t.length in 2..40) return t
        }
        return clean(doc.title())
    }

    private fun looksLikeLoginPage(doc: Document, title: String): Boolean {
        if (title.contains("登录") || title.contains("认证")) return true
        if (doc.selectFirst("form[action*=casLogin], #loginForm, #casLoginForm") != null) return true
        val text = doc.body().text()
        return LOGIN_HINTS.any { text.contains(it) && text.length < 2000 }
    }

    private fun clean(s: String): String = s.replace(ws, " ").trim()
}
