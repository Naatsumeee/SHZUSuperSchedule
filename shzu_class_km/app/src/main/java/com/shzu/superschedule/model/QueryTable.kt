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
) {
    EXAM(
        key = "exam",
        label = "考试安排",
        desc = "各门课的考试时间与考场",
        menuPath = "考试报名 → 我的考试 → 考试安排查询",
        perSemester = true,
        // 从主框架菜单里扒出来的真实入口：
        //   kjcdShow('NEW_XSD_KSBM','NEW_XSD_KSBM_WDKS','NEW_XSD_KSBM_WDKS_KSAPCX','/xsks/xsksap_query',…)
        paths = listOf(
            "/jsxsd/xsks/xsksap_query",
        ),
    ),
    SCORE(
        key = "score",
        label = "课程成绩",
        desc = "各学期课程成绩与学分绩点",
        menuPath = "学籍成绩 → 我的成绩 → 课程成绩查询",
        perSemester = true,
        // 从主框架菜单里扒出来的真实入口（注意是 _frm，是框架页，不是 cjcx_query）：
        //   kjcdShow('NEW_XSD_XJCJ','NEW_XSD_XJCJ_WDCJ','NEW_XSD_XJCJ_WDCJ_KCCJCX','/kscj/cjcx_frm',…)
        paths = listOf(
            "/jsxsd/kscj/cjcx_frm",
        ),
    ),
    GRADE(
        key = "grade",
        label = "等级考试成绩",
        desc = "四六级等等级考试成绩",
        menuPath = "学籍成绩 → 我的成绩 → 等级考试成绩",
        perSemester = false,
        // 主框架菜单里没有独立的「等级考试成绩」入口，
        // 最接近的是「社会考试报名」：kjcdShow(…,'/xsdjks/xsdjks_list','社会考试报名')
        paths = listOf(
            "/jsxsd/xsdjks/xsdjks_list",
        ),
    ),
    ;

    /** 拼出直达地址 */
    fun urlOf(base: String): String = base.trimEnd('/') + paths.first()

    companion object {
        fun of(key: String): QueryKind? = entries.firstOrNull { it.key == key }
    }
}
