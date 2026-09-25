package com.shzu.superschedule.data

import com.shzu.superschedule.model.QueryKind
import com.shzu.superschedule.model.QueryTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `JwglQueryParser` 的回归测试。
 *
 * ## 为什么这个文件重要
 *
 * 本项目历史上最痛的三个 bug 全在这个解析器里，而且**全都能通过编译**：
 *
 * 1. `repairStrayHeader` 只接到新抓取路径、漏了 `QueryStore.sanitize`（读取老存档）→ 返工；
 * 2. 等级考试表的第二层表头漏进 tbody → 4 列「分数类成绩」同名 → 挑中占位值 `0`
 *    （用户看到的就是「分数类成绩 0」）；
 * 3. 教务用跨列「未查询到数据」脏行冒充数据 → 空结果被报成「共 1 条」。
 *
 * 这些 bug 靠真机验证一次要十几分钟（构建 → 装机 → 登教务 → 点查询），
 * 且必须联网。搬到这里之后，同样的回归几秒钟就能发现。
 *
 * 夹具来源见 `app/src/test/resources/jwgl/`（真机抓包 + 一处结构复刻，后者有说明）。
 */
class JwglQueryParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("jwgl/$name")!!
            .bufferedReader(Charsets.UTF_8).readText()

    // ---------------- ① 占位行：教务用「未查询到数据」冒充数据 ----------------

    /**
     * 真实夹具 `wv_frame_xsdjks_list.html`：表头正常，数据区只有一行跨 8 列的
     * 「未查询到数据」。若不滤掉，调用方会把这张表当成「1 条有效记录」。
     */
    @Test
    fun `跨列未查询到数据行不被当作数据`() {
        val html = """
            <html><head><title>等级考试成绩查询</title></head><body>
            <table id="dataList">
              <thead><tr><th>序号</th><th>考级课程</th><th>分数类成绩</th></tr></thead>
              <tbody>
                <tr><td colspan="3">未查询到数据</td></tr>
              </tbody>
            </table></body></html>
        """.trimIndent()

        val t = JwglQueryParser.parse(html, "grade", FETCHED_AT, QueryKind.GRADE.headerKeywords)
        assertNotNull("表在、只是没数据，应当返回结果而不是 null", t)
        assertEquals("占位行必须被过滤掉", 0, t!!.rows.size)
        assertEquals("表头仍要读出来", listOf("序号", "考级课程", "分数类成绩"), t.headers)
    }

    /**
     * 判据的另一半：`isPlaceholderRow` 有「非空单元格 ≤ 1」的限制，
     * 所以备注列里恰好写了「无」的**正常数据行不能被误杀**。
     * 这条以前没有测试保护，一旦把限制去掉就会静默丢数据。
     */
    @Test
    fun `正常行里出现无字不会被视为占位行`() {
        val normalRow = listOf("1", "高等数学", "95", "无")
        assertTrue("该行有 3 个以上非空格，不该判成占位行", !JwglQueryParser.isPlaceholderRow(normalRow))
        assertTrue("单格写着未查询到数据才算占位", JwglQueryParser.isPlaceholderRow(listOf("未查询到数据")))
    }

    // ---------------- ② 第二层表头：等级考试表的子列名漏进 tbody ----------------

    /**
     * 🔴 这是 BETA-v1.4 修掉的那个用户可见 bug。
     *
     * 夹具复刻真实结构：thead 只有一行、父列 `1+1+4+3+1+1 = 11` 列，
     * 真正的子列名（笔试/机试/总成绩/…）单独占了 tbody 第一行。
     *
     * 修复要同时满足两点，缺一不可：
     * - 那一行**不能留在数据里**（否则界面凭空多出标题为「笔试」的空卡片）；
     * - 它的子列名**必须补回表头**（否则 4 列「分数类成绩」同名，
     *   取值时只能拿第一个非空值 → 挑中表示"没成绩"的 `0`）。
     */
    @Test
    fun `等级考试表漏进 tbody 的第二层表头被剔除且子列名补回`() {
        val html = fixture("exam_grade_reconstructed.html")
        val t = JwglQueryParser.parse(html, "grade", FETCHED_AT, QueryKind.GRADE.headerKeywords)

        assertNotNull(t)
        t!!

        // 数据里那行「笔试 机试 …」必须消失：表里只有 3 条真数据
        assertEquals("第二层表头行必须被剔除", 3, t.rows.size)

        // 表头必须仍是 11 列（没有因为剔除而错位）
        assertEquals("列数应与真实结构一致", 11, t.headers.size)

        // 子列名被拼到父名后面：分数类成绩 → 分数类成绩 / 总成绩 ……
        // ⚠️ 真实子列名在两组之间**重复**（笔试 机试 总成绩 ×2），
        //    这正是判据③「行内有复读值」能命中的原因。
        assertEquals("序号", t.headers[0])
        assertEquals("考级课程(等级)", t.headers[1])
        assertEquals("分数类成绩 / 笔试", t.headers[2])
        assertEquals("分数类成绩 / 机试", t.headers[3])
        assertEquals("分数类成绩 / 总成绩", t.headers[4])
        assertEquals("等级类成绩 / 机试", t.headers[6])
        assertEquals("等级类成绩 / 总成绩", t.headers[8])
        assertEquals("考级开始时间", t.headers[9])
        assertEquals("考级结束时间", t.headers[10])

        // 补名的**目的**在这里体现：能唯一定位到有分数的那一列
        val scoreCol = t.headers.indexOf("分数类成绩 / 总成绩")
        assertTrue("「分数类成绩 / 总成绩」必须能按名字找到", scoreCol >= 0)
        assertEquals("CET-4 的 452 分必须挂在总成绩列上", "452", t.rows[0][scoreCol])
        assertEquals("CET-6 的 388 分必须挂在总成绩列上", "388", t.rows[1][scoreCol])
    }

    /**
     * 幂等性：补过名之后表头不再重名，再跑一次不该继续拼名字
     * （否则会变成「分数类成绩 / 总成绩 / 总成绩」）。
     * `QueryStore.sanitize` 会在每次读取存档时调用它，所以幂等是硬要求。
     */
    @Test
    fun `补名后再调用一次不会重复拼接`() {
        val headers = listOf("序号", "考级课程", "分数类成绩", "分数类成绩", "等级类成绩")
        val stray = listOf("", "", "总成绩", "笔试", "总成绩")
        val data = listOf("1", "CET-4", "452", "0", "")

        val (h1, r1) = JwglQueryParser.repairStrayHeader(headers, listOf(stray, data))
        assertEquals(
            listOf("序号", "考级课程", "分数类成绩 / 总成绩", "分数类成绩 / 笔试", "等级类成绩 / 总成绩"),
            h1,
        )
        assertEquals(1, r1.size)

        val (h2, r2) = JwglQueryParser.repairStrayHeader(h1, r1)
        assertEquals("第二次调用必须原样返回（幂等）", h1, h2)
        assertEquals("第二次调用不能吃掉数据行", 1, r2.size)
    }

    /**
     * 反向保护：表头**没有**重名时只剔除、不改名。
     * 那种情况下子列名已经在 thead 里正确读到了，再拼一次反而会污染表头。
     */
    @Test
    fun `表头无重名时只剔除不改名`() {
        val headers = listOf("序号", "课程名称", "成绩")
        val stray = listOf("序号", "课程名称", "成绩") // 复读，但表头无重名
        val (h, r) = JwglQueryParser.repairStrayHeader(headers, listOf(stray, listOf("1", "高数", "95")))
        assertEquals("表头应原样保留", headers, h)
        assertEquals("杂散行仍要剔除", 1, r.size)
    }

    /**
     * `isStrayHeaderRow` 不能误杀真数据行。
     * 真数据几乎必然带数字（序号 / 日期 / 分数），这是判据里最稳的一条。
     */
    @Test
    fun `带数字的正常数据行不会被判成表头行`() {
        val headers = listOf("序号", "考级课程(等级)", "等级类成绩 / 合格")
        // 真数据：带序号与时间
        assertTrue(!JwglQueryParser.isStrayHeaderRow(listOf("1", "四级", "合格"), headers))
        // 表头行：无数字 + 复读
        assertTrue(JwglQueryParser.isStrayHeaderRow(listOf("笔试", "机试", "总成绩", "笔试"), headers))
    }

    // ---------------- ③ 挑表：不能挑到无关的大表 ----------------

    /**
     * 抓取时会把主框架里**所有 iframe** 的内容拼在一起交给解析器，
     * 里面混着「修改个人信息」之类的常驻表格。只按「数据行最多」挑会挑错 ——
     * 实测考试安排本该为空，却和课程成绩返回了完全相同的 42 行。
     *
     * 关键词必须让解析器认准目标表。这里用一个「干扰表行数更多」的页面验证。
     */
    @Test
    fun `关键词能避免挑到行数更多的无关表`() {
        val html = """
            <html><head><title>课程成绩查询</title></head><body>
            <table id="other">
              <thead><tr><th>序号</th><th>项目</th><th>值</th></tr></thead>
              <tbody>
                <tr><td>1</td><td>姓名</td><td>张三</td></tr>
                <tr><td>2</td><td>学号</td><td>2023001</td></tr>
                <tr><td>3</td><td>班级</td><td>计科1班</td></tr>
                <tr><td>4</td><td>专业</td><td>计算机</td></tr>
              </tbody>
            </table>
            <table id="dataList">
              <thead><tr><th>序号</th><th>开课学期</th><th>课程名称</th><th>成绩</th><th>学分</th><th>绩点</th></tr></thead>
              <tbody><tr><td>1</td><td>2026-2027-1</td><td>高等数学</td><td>95</td><td>5.0</td><td>4.5</td></tr></tbody>
            </table></body></html>
        """.trimIndent()

        val t = JwglQueryParser.parse(html, "score", FETCHED_AT, QueryKind.SCORE.headerKeywords)
        assertNotNull(t)
        assertEquals("必须认准含「学分/绩点」的表，而不是行数更多的干扰表", 1, t!!.rows.size)
        assertTrue(t.headers.contains("绩点"))
    }

    /**
     * 页面里没有任何一张表含本类关键词时，**必须返回 null**，
     * 让调用方报「未查询到数据」而不是给出错误的表。
     * 「宁可不给数据，也不能给错数据」。
     */
    @Test
    fun `找不到含关键词的表时返回 null`() {
        val html = """
            <html><head><title>考试安排查询</title></head><body>
            <table id="dataList">
              <thead><tr><th>序号</th><th>课程名称</th><th>成绩</th></tr></thead>
              <tbody><tr><td>1</td><td>高数</td><td>95</td></tr></tbody>
            </table></body></html>
        """.trimIndent()

        // 用考试安排的词去找：这张表里没有「考场/考试场次/考试校区」
        assertNull(JwglQueryParser.parse(html, "exam", FETCHED_AT, QueryKind.EXAM.headerKeywords))
    }

    // ---------------- ④ 安全阀：登录页 / 空页面 ----------------

    @Test
    fun `登录页不会被误解析成数据`() {
        val html = """
            <html><head><title>用户登录</title></head><body>
            <form id="loginForm" action="/casLogin"><input name="username"></form>
            </body></html>
        """.trimIndent()
        assertNull(JwglQueryParser.parse(html, "score", FETCHED_AT, QueryKind.SCORE.headerKeywords))
    }

    @Test
    fun `空 HTML 返回 null`() {
        assertNull(JwglQueryParser.parse("", "score", FETCHED_AT, QueryKind.SCORE.headerKeywords))
        assertNull(JwglQueryParser.parse("   \n  ", "score", FETCHED_AT, QueryKind.SCORE.headerKeywords))
    }

    /**
     * 表在、但一行数据都没有 → 返回**空表**而不是 null。
     * 调用方要靠这个区分「教务确实没有数据」与「页面结构认不出来」，
     * 这两件事对用户的意义完全不同（前者正常，后者要提示去网页手动查）。
     */
    @Test
    fun `有表头无数据行时返回空表而非 null`() {
        val html = """
            <html><head><title>课程成绩查询</title></head><body>
            <table id="dataList">
              <thead><tr><th>序号</th><th>课程名称</th><th>学分</th><th>绩点</th></tr></thead>
              <tbody></tbody>
            </table></body></html>
        """.trimIndent()

        val t = JwglQueryParser.parse(html, "score", FETCHED_AT, QueryKind.SCORE.headerKeywords)
        assertNotNull("有表无数据也必须返回结果，供调用方判定未查询到数据", t)
        assertEquals(0, t!!.rows.size)
        assertEquals(4, t.headers.size)
        assertTrue("isEmpty 应为 true", t.isEmpty)
    }

    // ---------------- ⑤ 生产夹具：真机抓下来的页面结构 ----------------

    /**
     * 🔴 真实夹具暴露的问题，**已修**（2026-09-25）。
     *
     * `wv_frame_xsdjks_list.html` 是「社会考试报名」页，**不是**等级考试成绩页。
     * 但它的表头里有 `考级课程名称`，而 GRADE 原先的关键词之一 `考级课程` 是它的子串
     * —— 于是解析器会把这个**报名页**的数据当成等级考试成绩返回：
     * 列是「报名金额 / 报名时间 / 审核状态」这些报名字段，用户看到的就是错的。
     *
     * 生产上一直没爆，只是因为 `JwglQueryFetcher` 会先按 iframe 的 `src`
     * 锁定目标页（`/jsxsd/kscj/djkscj_list`），关键词只是第二道保险 ——
     * **但第二道保险自己是漏的**：一旦 iframe 锁定失效退回「全收」，这一层挡不住。
     *
     * 修法：GRADE 关键词收紧为 `等级类成绩` / `分数类成绩`（只有真正的成绩表才有），
     * 去掉 `考级课程`。本测试就是那次修复的回归锁。
     */
    @Test
    fun `社会考试报名页不会被当成等级考试成绩页`() {
        val html = fixture("wv_frame_xsdjks_list.html")
        assertNull(
            "报名页表头含「考级课程名称」，但 GRADE 关键词已收紧，不应再命中",
            JwglQueryParser.parse(html, "grade", FETCHED_AT, QueryKind.GRADE.headerKeywords),
        )
    }

    /**
     * 修复的反向保护：关键词收紧后，**真正的等级考试成绩表必须仍然认得出**。
     * 否则就是"修错了方向" —— 用结构复刻夹具守住这一侧。
     */
    @Test
    fun `收紧关键词后真实的等级考试成绩表仍能认出`() {
        val html = fixture("exam_grade_reconstructed.html")
        val t = JwglQueryParser.parse(html, "grade", FETCHED_AT, QueryKind.GRADE.headerKeywords)
        assertNotNull("真正的成绩表不能被误伤", t)
        assertEquals(3, t!!.rows.size)
    }

    /** 真机抓下来的成绩查询**主框架**是 iframe 容器（结果在子 iframe 里），没有结果表 */
    @Test
    fun `成绩查询主框架没有结果表时返回 null`() {
        val html = fixture("wv_frame_cjcx_frm.html")
        assertNull(JwglQueryParser.parse(html, "score", FETCHED_AT, QueryKind.SCORE.headerKeywords))
    }

    // ---------------- ⑥ 表头读取：rowspan 造成的列错位 ----------------

    /**
     * ⚠️ 早期 bug：表头多行时只取第一行，导致「所有值从第 7 列起全部对错名」，
     * 界面上出现 437 分被标成「考级开始时间」这种荒唐结果。
     * 这条用 rowspan + colspan 混排验证按列号累加的正确性。
     */
    @Test
    fun `多行表头按列号累加且 rowspan 不造成错位`() {
        val html = """
            <html><head><title>考试安排查询</title></head><body>
            <table id="dataList">
              <thead>
                <tr>
                  <th rowspan="2">序号</th>
                  <th colspan="2">考试信息</th>
                  <th rowspan="2">考场</th>
                </tr>
                <tr>
                  <th>考试时间</th>
                  <th>考试场次</th>
                </tr>
              </thead>
              <tbody>
                <tr><td>1</td><td>2026-11-28 10:00</td><td>第1场</td><td>A101</td></tr>
              </tbody>
            </table></body></html>
        """.trimIndent()

        val t = JwglQueryParser.parse(html, "exam", FETCHED_AT, QueryKind.EXAM.headerKeywords)
        assertNotNull(t)
        t!!
        assertEquals("rowspan 的「序号」要在第 2 行继续占位，列数必须是 4", 4, t.headers.size)
        assertEquals("序号", t.headers[0])
        assertEquals("考试信息 / 考试时间", t.headers[1])
        assertEquals("考试信息 / 考试场次", t.headers[2])
        assertEquals("考场", t.headers[3])
        // 值必须与列名对上（这正是早期错位 bug 的反面）
        assertEquals("A101", t.rows[0][t.headers.indexOf("考场")])
    }

    /** `labeled()` 是详情卡片的取值依据：列名缺失时要回退成「第N项」而不是崩 */
    @Test
    fun `labeled 在表头缺失时回退为第N项`() {
        val t = QueryTable(
            kind = "score",
            headers = listOf("课程名称", ""),
            rows = listOf(listOf("高等数学", "95")),
        )
        val labeled = t.labeled(t.rows[0])
        assertEquals("课程名称", labeled[0].first)
        assertEquals("空表头列要回退成第2项", "第2项", labeled[1].first)
        assertEquals("95", labeled[1].second)
    }

    /**
     * 🔒 不变式锁：三类查询的关键词必须**互相排斥**。
     *
     * 这是 `QueryKind.headerKeywords` 注释里写明的设计要求，但以前只靠人记着。
     * 已经被咬过一次：GRADE 的 `考级课程` 是「社会考试报名」页 `考级课程名称` 的子串
     * （2026-09-25 修复）。这条断言把「不能互为子串」变成机器检查，
     * 以后谁改关键词都会被立刻拦住。
     */
    @Test
    fun `三类查询的表头关键词互不为子串`() {
        val all = QueryKind.entries.associateWith { it.headerKeywords }
        for ((ka, va) in all) {
            for ((kb, vb) in all) {
                if (ka == kb) continue
                for (a in va) for (b in vb) {
                    assertTrue(
                        "$ka 的关键词「$a」与 $kb 的关键词「$b」互为子串，会导致串台",
                        !a.contains(b) && !b.contains(a),
                    )
                }
            }
        }
    }

    private companion object {
        const val FETCHED_AT = "2026-09-25 21:00"
    }
}
