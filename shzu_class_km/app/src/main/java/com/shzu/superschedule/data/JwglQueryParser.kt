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
     * 「没有数据」占位行的特征。
     *
     * 🔴 强智在结果为空时**不是不给表**，而是给一张只有一行的表，
     * 那行跨列表格里写着「未查询到数据」。如果不把它滤掉，
     * 调用方会把它当成「1 条有效数据」——
     * 实测「考试安排」本该为空，却因此报出 1 条记录，
     * 界面上看起来就像查到了东西。
     *
     * 判据加一个「非空单元格 ≤ 1」的限制，避免误伤正常数据行里
     * 恰好出现的同名文字（例如备注列写了「无」）。
     */
    private val NO_DATA_HINTS = listOf(
        "未查询到数据", "没有查询到", "暂无数据", "无相关数据", "没有数据", "未找到数据",
    )

    /**
     * 解析一次查询结果。
     *
     * @param kind 查询类型 key，写回 [QueryTable.kind]
     * @param keywords 该查询结果表的**表头关键词**（任一命中即可）。
     *
     *   ⚠️ 这个参数很关键。抓取时我们把主框架里**所有 iframe 的内容**拼在一起交给解析器，
     *   里面混着「修改个人信息」之类的常驻 iframe。如果只按「数据行最多的表」去挑，
     *   会挑到那些无关的大表 —— 实测考试安排本该为空，却和课程成绩返回了**完全相同的
     *   42 行**，就是挑错了表。所以必须靠表头特征来认。
     *
     *   找不到任何匹配的表时返回 null（调用方据此判「未查询到数据」），
     *   宁可不给数据，也不能给错数据。
     * @return 解析失败（未登录 / 还没进到查询页 / 表格改版到认不出）时返回 null
     */
    fun parse(
        html: String,
        kind: String,
        fetchedAt: String,
        keywords: List<String> = emptyList(),
    ): QueryTable? {
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

        val table = pickTable(doc, keywords) ?: run {
            Log.w(TAG, "未找到可用结果表。title=$title keywords=$keywords")
            return null
        }
        val (headers, rows) = readTable(table)
        Log.i(
            TAG,
            "解析 $kind：title=$title 表头=${headers.size} 列 数据=${rows.size} 行",
        )
        // 表在、但一行数据都没有 → 仍然返回结果（rows 为空），由调用方判定成
        // 「未查询到数据」。若这里直接返回 null，调用方就没法区分
        // 「教务确实没有数据」和「页面结构认不出来」，只能一律报「失败」，
        // 而这两件事对用户的意义完全不同。
        return QueryTable(
            kind = kind,
            title = title,
            headers = headers,
            rows = rows,
            fetchedAt = fetchedAt,
        )
    }

    /**
     * 该行是否为强智的「没有数据」占位行。
     *
     * 解析时用它滤掉占位行；**读取历史存档时也用它** —— 早期版本没做这层过滤，
     * 存档里可能已经写进了占位行，加载时清一遍，用户不刷新也能看到正确结果。
     *
     * 判据带「非空单元格 ≤ 1」的限制，避免误伤正常数据行里恰好出现的同名文字
     * （例如备注列写了「无」）。
     */
    fun isPlaceholderRow(cells: List<String>): Boolean {
        if (cells.count { it.isNotBlank() } > 1) return false
        val joined = cells.joinToString("")
        return NO_DATA_HINTS.any { joined.contains(it) }
    }

    // ---------------- 找表 ----------------

    private fun pickTable(doc: Document, keywords: List<String>): Element? {
        // 1) 先用常见的结果表 id/class —— 但**仍要过关键词校验**，
        //    因为这些 id 在无关 iframe 里也可能存在。
        for (sel in PREFERRED) {
            val t = doc.selectFirst(sel) ?: continue
            if (keywords.isEmpty() || headerMatches(t, keywords)) {
                Log.d(TAG, "命中优先选择器 $sel（${dataRowCount(t)} 行数据）")
                return t
            }
            Log.d(TAG, "选择器 $sel 命中但表头不含关键词，继续找")
        }

        // 2) 全表扫描：表头含关键词的表里，取数据行最多的那个
        if (keywords.isNotEmpty()) {
            val hit = doc.select("table")
                .filter { headerMatches(it, keywords) }
                .maxByOrNull { dataRowCount(it) }
            if (hit != null) {
                Log.d(TAG, "按表头关键词选中（${dataRowCount(hit)} 行）")
                return hit
            }
            return null
        }

        // 3) 没给关键词时（老调用路径）才退回「数据行最多的表」
        val best = doc.select("table")
            .map { it to dataRowCount(it) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
        if (best != null) {
            Log.d(TAG, "兜底选中数据行最多的表（${best.second} 行）")
        }
        return best?.first
    }

    /** 表头行（thead 首行，或首个含 th 的行）是否含任一关键词 */
    /** 表头（整个 thead，含多行；无 thead 时取首行）是否含任一关键词 */
    private fun headerMatches(table: Element, keywords: List<String>): Boolean {
        val headerText = run {
            // ⚠️ 用整个 thead 而不是 `> thead tr`（第一行）：
            //    等级考试成绩表的「分数类成绩 / 等级类成绩」在第二行，
            //    只看第一行会漏判，明明有数据却报「未查询到数据」。
            val head = table.selectFirst("> thead") ?: table.selectFirst("tr")
            clean(head?.text().orEmpty())
        }
        if (headerText.isBlank()) return false
        return keywords.any { headerText.contains(it) }
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

    /**
     * 把表头铺成「每列一个名字」。
     *
     * ⚠️ 表头**可能是多行的**，不能只取第一行。等级考试成绩那张表就是这样：
     * 第一行只有 6 个 `th`（序号 / 考级课程(等级) / 分数类成绩 / 等级类成绩 /
     * 考级开始时间 / 考级结束时间），但数据行有 11 个格子 ——
     * 「分数类成绩」下面还分「笔试 / 机试 / 总成绩」等子列，写在第二行。
     * 只取第一行会让**所有值从第 7 列起全部对错名**，
     * 界面上表现为 437 分被标成「考级开始时间」这种荒唐结果，
     * 剩下的列则退化成「第7项 / 第10项」。
     *
     * 这里的做法是按**列号**累加：逐行走，遇到 `colspan` 就把同一个名字铺到多列，
     * 遇到 `rowspan` 就把名字挂起来、在后续行继续占位（这正是数据格会右移的原因）。
     * 同一列多行都有名字时（如「分数类成绩」+「笔试」）拼成「父 / 子」，
     * 既保留层级，又保证列数对齐。
     */
    private fun readHeaders(table: Element): Pair<List<String>, Element?> {
        val theadRows = table.selectFirst("> thead")?.select("tr")?.toList().orEmpty()
        // 没有 thead：首行若含 th 就当作表头（返回该行，供调用方从数据里剔除）
        var fallback: Element? = null
        val rows = if (theadRows.isNotEmpty()) {
            theadRows
        } else {
            val first = table.selectFirst("> tbody > tr") ?: table.selectFirst("> tr")
            if (first != null && first.select("> th").isNotEmpty()) {
                fallback = first
                listOf(first)
            } else {
                emptyList()
            }
        }
        if (rows.isEmpty()) return emptyList<String>() to null

        val labels = linkedMapOf<Int, MutableList<String>>()
        /** 列号 → (名字, 还占着几行)；rowspan 未走完的列，下一行的格子要往右让 */
        val carry = mutableMapOf<Int, Pair<String, Int>>()

        fun put(col: Int, text: String) {
            labels.getOrPut(col) { mutableListOf() }.add(text)
        }
        /** 把该列上尚未结束的 rowspan 依次结清 */
        fun flushCarry(col: Int): Int {
            var c = col
            while (true) {
                val held = carry[c] ?: return c
                val (text, left) = held
                put(c, text)
                if (left <= 1) carry.remove(c) else carry[c] = text to (left - 1)
                c++
            }
        }

        for (tr in rows) {
            var col = 0
            for (cell in tr.select("> th, > td")) {
                col = flushCarry(col)
                val span = cell.attr("colspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
                val rspan = cell.attr("rowspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
                val text = clean(cell.text())
                repeat(span) { _ ->
                    put(col, text)
                    if (rspan > 1) carry[col] = text to (rspan - 1)
                    col++
                }
            }
            // 行尾可能还有没结清的 rowspan（本行格子比列数少）
            flushCarry(col)
        }

        val cols = (labels.keys.maxOrNull() ?: return emptyList<String>() to fallback) + 1
        val names = (0 until cols).map { i ->
            labels[i].orEmpty()
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" / ")
        }
        return names to fallback
    }

    private fun readTable(table: Element): Pair<List<String>, List<List<String>>> {
        // 第二项是「没有 thead 时被当成表头的那一行」，要在数据里跳过，
        // 否则它会被既当表头又当数据，界面上凭空多出一行。
        val (parsedHeaders, fallbackHeaderRow) = readHeaders(table)
        var headers: List<String> = parsedHeaders

        val rows = mutableListOf<List<String>>()
        for (tr in directRows(table)) {
            if (tr === fallbackHeaderRow) continue
            // 表头行（thead 里的）不重复收进数据
            val inThead = tr.parents().any { it.normalName() == "thead" }
            if (inThead) continue

            val cells = tr.select("> th, > td").map { clean(it.text()) }
            if (cells.isEmpty() || cells.all { it.isBlank() }) continue

            // 「未查询到数据」占位行不算数据行（详见 NO_DATA_HINTS 的说明）
            if (isPlaceholderRow(cells)) {
                Log.d(TAG, "跳过占位行：${cells.joinToString("")}")
                continue
            }

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
