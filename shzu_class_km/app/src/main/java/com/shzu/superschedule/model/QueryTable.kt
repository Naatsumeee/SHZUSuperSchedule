package com.shzu.superschedule.model

import kotlinx.serialization.Serializable

/**
 * 一次教务查询的结果。
 *
 * 教务系统里「考试安排查询」「课程成绩查询」「等级考试成绩」这三类页面
 * 结构完全不同、且学校可能随时改版，因此**不做逐字段建模**，
 * 而是统一收敛成「表头 + 行」这种通用表格形态 —— 只要页面还是表格，
 * 换版式也不会解析失败。
 */
@Serializable
data class QueryTable(
    /** 查询类型 key，见 [QueryKind.key] */
    val kind: String,
    /** 该结果对应的学期代码，如 "2026-2027-1"；与学期无关的查询（等级考试）留空 */
    val semester: String = "",
    /** 页面标题（取自 <title> 或页面主标题） */
    val title: String = "",
    /** 表头；可能为空（页面没有 thead 时由首行推断） */
    val headers: List<String> = emptyList(),
    /** 数据行，每行与 [headers] 按下标对应 */
    val rows: List<List<String>> = emptyList(),
    /** 抓取时间，形如 2026-09-21 01:02 */
    val fetchedAt: String = "",
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    /** 记录条数（列表里显示「共 N 条」） */
    val count: Int get() = rows.size

    /**
     * 把一行摊平成「字段名 → 值」，供详情卡片逐条渲染。
     * 表头缺失或长度不足时回退成「第N列」，保证不会丢数据。
     */
    fun labeled(row: List<String>): List<Pair<String, String>> =
        row.mapIndexed { i, v ->
            val h = headers.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "第${i + 1}项"
            h to v
        }
}

/**
 * 三个查询入口。
 *
 * [paths] 是按可能性排序的候选地址（强智教务各校版式/文件名差异较大），
 * 取第一个作为「直达」按钮的目标；**打不开时用户可以在网页里手动点菜单**，
 * 抓取逻辑不依赖具体地址，只要当前页面是目标查询页即可。
 */
enum class QueryKind(
    val key: String,
    val label: String,
    val desc: String,
    /** 教务系统里的菜单路径，显示在二级页提示里，方便用户手动导航 */
    val menuPath: String,
    /** 是否按学期查询：考试安排、课程成绩需要选学期；等级考试不需要 */
    val perSemester: Boolean,
    val paths: List<String>,
    /**
     * 教务主框架里打开本查询页的 `kjcdShow(...)` 实参（从菜单 HTML 的 onclick 里扒出来的）。
     *
     * 强智的查询页**只能在主框架的子 iframe 里打开**，而菜单项点击最终就是调这个函数。
     * 直接复用它比自己「找菜单项再 click」可靠得多 —— 菜单是异步渲染的，
     * 时机不对就找不到元素（实测点击时返回 notfound）。
     */
    val menuCall: List<String> = emptyList(),
    /**
     * 该类结果表的**表头关键词**（任一命中即认）。
     *
     * 抓取时已按 iframe 的 `src` 锁定目标页，这里只是**第二道保险**：
     * 万一目标 iframe 没匹配上而退回了「全收」，也不能抓到别的表。
     *
     * 所以三类的词必须**互相排斥**，尤其不能都用「成绩」这种共性词 ——
     * 等级考试表的表头就是「考试成绩」，用「成绩」会把两类混在一起。
     * 定稿：
     * - 成绩表认「学分 / 绩点」（只有课程成绩表有）；
     * - 等级考试认「证书 / 准考证 / 考试等级」；
     * - 考试安排认「考场 / 考试场次 / 考试校区」。
     */
    val headerKeywords: List<String> = emptyList(),
) {
    EXAM(
        key = "exam",
        label = "考试安排",
        desc = "各门课的考试时间与考场",
        menuPath = "考试报名 → 我的考试 → 考试安排查询",
        perSemester = true,
        paths = listOf(
            "/jsxsd/xsks/xsksap_query",
        ),
        menuCall = listOf(
            "NEW_XSD_KSBM", "NEW_XSD_KSBM_WDKS", "NEW_XSD_KSBM_WDKS_KSAPCX",
            "/xsks/xsksap_query", "考试安排查询",
        ),
        // 考试安排表特有的列
        headerKeywords = listOf("考场", "考试场次", "考试校区"),
    ),
    SCORE(
        key = "score",
        label = "课程成绩",
        desc = "各学期课程成绩与学分绩点",
        menuPath = "学籍成绩 → 我的成绩 → 课程成绩查询",
        perSemester = true,
        paths = listOf(
            "/jsxsd/kscj/cjcx_frm",
        ),
        menuCall = listOf(
            "NEW_XSD_XJCJ", "NEW_XSD_XJCJ_WDCJ", "NEW_XSD_XJCJ_WDCJ_KCCJCX",
            "/kscj/cjcx_frm", "课程成绩查询",
        ),
        // 「学分 / 绩点」是课程成绩表独有的（等级考试 / 考试安排表都没有）
        headerKeywords = listOf("学分", "绩点"),
    ),
    GRADE(
        key = "grade",
        label = "等级考试成绩",
        desc = "四六级、计算机等级考试成绩",
        menuPath = "学籍成绩 → 我的成绩 → 等级考试成绩",
        perSemester = false,
        // 实测（从「frame 清单」里读出来的真实地址）：
        //   Frame3@https://jwgl.shzu.edu.cn/jsxsd/kscj/djkscj_list
        // 它挂在 kscj 模块下，不在 xsdjks 下 —— 第一版按「等级考试」中文
        // 猜成 /xsdjks/xsdjks_list，那其实是「社会考试报名」，抓错了页。
        paths = listOf(
            "/jsxsd/kscj/djkscj_list",
        ),
        // 🔑 kjcdShow 的真实签名是
        //     function kjcdShow(yjcode, ejcode, sjcode, url, name)
        //   函数体只做 `parent.showMenuErji($("li[data-sjcode='"+sjcode+"']"), name, 3)`
        //   —— **url 参数根本没用**，真正决定打开哪一页的是 sjcode（三级菜单 id）。
        //   所以这里必须给对的 sjcode；第一版错用了「社会考试报名」的 id，
        //   抓到的是报名页而不是等级考试成绩页。
        menuCall = listOf(
            "NEW_XSD_XJCJ", "NEW_XSD_XJCJ_WDCJ", "NEW_XSD_XJCJ_WDCJ_DJKSCJ",
            "/xsdjks/xsdjks_list", "等级考试成绩",
        ),
        // ⚠️ 关键词必须用**这张表真正独有的列名**。
        // 实测该表表头是「序号 考级课程(等级) 分数类成绩 等级类成绩 考级开始时间 考级结束时间」，
        // 先前写「证书 / 考试等级」一个都没命中，于是明明有数据却报「未查询到数据」。
        // 另外不能用「准考证」—— 考试安排表里就有「准考证号」，会串台。
        headerKeywords = listOf("等级类成绩", "分数类成绩", "考级课程"),
    ),
    ;

    /** 拼出直达地址 */
    fun urlOf(base: String): String = base.trimEnd('/') + paths.first()

    companion object {
        fun of(key: String): QueryKind? = entries.firstOrNull { it.key == key }
    }
}
