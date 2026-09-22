package com.shzu.superschedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shzu.superschedule.model.AppSettings
import com.shzu.superschedule.model.QueryKind
import com.shzu.superschedule.model.QueryTable
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate

// ---------------------------------------------------------------------------
// 查询结果的三种卡片排版
//
// 三类查询的信息重心完全不同，用同一套「首字段当标题、其余按字段名排」的通用版式
// 会把真正重要的东西埋掉（考试安排里最要紧的是"什么时候、在哪儿考"，
// 课程成绩里最要紧的是"这门课多少分"）。所以这里按 kind 分别排版。
//
// ⚠️ 但**字段取值一律按表头关键词查找，不写死列序号**。
//    强智各校版式、同一学校不同模块的列表顺序都不一样，写死下标遇到改版就全错位。
//    这一条与 JwglQueryParser「不做逐字段建模」的设计是一致的。
// ---------------------------------------------------------------------------

/** 双层表头的父子分隔符（见 JwglQueryParser.readHeaders） */
private const val LABEL_SEP = " / "

/** 成绩不及格的红色。深色主题下用亮一档的红，否则在深底上发闷 */
private val FAIL_RED_LIGHT = Color(0xFFD32F2F)
private val FAIL_RED_DARK = Color(0xFFFF6B6B)

/** 日期：2026-11-28 / 2026年11月28日 / 2026.11.28 / 2026/11/28 都能认 */
private val DATE_RE = Regex("(\\d{4})\\s*[-年/.]\\s*(\\d{1,2})\\s*[-月/.]\\s*(\\d{1,2})")

/** 时间区间里的连接符（~ ～ 至 到）统一成半角连字符 */
private val TIME_RANGE_RE = Regex("(\\d{1,2}:\\d{2})\\s*[~～至到]\\s*(\\d{1,2}:\\d{2})")

/**
 * 单条查询结果的卡片。
 *
 * @param settings 只为拿 `schoolStartDate` —— 考试安排要把日期换算成「第 N 周周 X」
 */
@Composable
internal fun QueryResultCard(
    table: QueryTable,
    row: List<String>,
    settings: AppSettings,
) {
    when (QueryKind.of(table.kind)) {
        QueryKind.EXAM -> ExamCard(table, row, settings)
        QueryKind.SCORE -> ScoreCard(table, row)
        QueryKind.GRADE -> GradeCard(table, row)
        // 未知 kind（将来教务加新入口）沿用通用版式，至少不丢数据
        null -> GenericCard(table, row)
    }
}

// ---------------- 考试安排 ----------------

/**
 * 考试安排：**考试时间、考场放到最上方加粗**。
 *
 * 用户的原话是「时间、考场教室采用加粗、放到上面醒目显示，
 * 考试时间的日期后写好匹配第几周周几」。所以卡片第一行就是
 * `2026年11月28日（第13周周六） 10:00-11:30` 这种形态，
 * 往下才是课程名、场次、校区等次要信息。
 */
@Composable
private fun ExamCard(table: QueryTable, row: List<String>, settings: AppSettings) {
    val items = table.labeled(row).filter { it.second.isNotBlank() }
    if (items.isEmpty()) return

    // 「考试时间」优先；兜底任何含「时间」的表头。
    // 注意不要匹配到「考级开始时间」那种 —— 那是等级考试表的列，不会出现在这里。
    val timePair = items.firstOrNull { it.first.contains("考试时间") }
        ?: items.firstOrNull { it.first.contains("时间") }
    // 考场：表头含「考场」。与「考试场次」「考试校区」都不冲突
    // （「考试场次」= 考场次，「考场」二字并不相邻）
    val roomPair = items.firstOrNull { it.first.contains("考场") }

    val highlights = setOfNotNull(timePair?.first, roomPair?.first)
    val rest = items.filter { it.first !in highlights }

    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            timePair?.let { (_, raw) ->
                Text(
                    text = formatExamTime(raw, settings),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
            roomPair?.let { (_, room) ->
                if (timePair != null) Spacer(Modifier.height(5.dp))
                Text(
                    text = room,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
            if (rest.isNotEmpty()) {
                if (timePair != null || roomPair != null) {
                    Spacer(Modifier.height(9.dp))
                    CardDivider()
                }
                rest.forEach { (k, v) -> FieldRow(k, v, first = false) }
            }
        }
    }
}

/**
 * 把教务的考试时间串重排成「2026年11月28日（第13周周六） 10:00-11:30」。
 *
 * 日期部分用 `Regex.replace` 逐段替换：教务有时把补考、连考的多场时间塞进同一格
 * （用空格或 `<br>` 分隔，Jsoup 取 `text()` 后会变成空格），
 * 逐个替换能保证每一段都带上自己的周次，不会只处理第一个。
 *
 * 找不到日期时**原样返回**（宁可不改，也不能把原文弄坏）。
 */
private fun formatExamTime(raw: String, settings: AppSettings): String {
    val text = raw.replace(TIME_RANGE_RE) { "${it.groupValues[1]}-${it.groupValues[2]}" }.trim()
    if (text.isEmpty()) return text
    return DATE_RE.replace(text) { m ->
        val date = runCatching {
            LocalDate.of(
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt(),
                m.groupValues[3].toInt(),
            )
        }.getOrNull() ?: return@replace m.value
        val week = WeekCalc.weekOf(settings.schoolStartDate, date)
        val wd = WeekCalc.weekdayCn(date)
        if (week == null) {
            "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
        } else {
            "${date.year}年${date.monthValue}月${date.dayOfMonth}日（第${week}周周${wd}）"
        }
    }
}

// ---------------- 课程成绩 ----------------

/**
 * 课程成绩：**课程名当标题加粗，成绩放大加粗，不及格标红**。
 *
 * 「开课学期」「课程编号」这两个字段用户要求精简 —— 它们收进卡片底部的
 * 一行小字脚注，不再各占一行；课程编号对学生基本没有阅读价值，
 * 开课学期在上面已经用学期胶囊选过了，重复显示只会把成绩挤下去。
 */
@Composable
private fun ScoreCard(table: QueryTable, row: List<String>) {
    val items = table.labeled(row).filter { it.second.isNotBlank() }
    if (items.isEmpty()) return

    val coursePair = items.firstOrNull { it.first.contains("课程名称") }
        ?: items.firstOrNull { it.first.contains("课程名") }
        ?: items.first()

    // 成绩列：含「成绩」但要排除「成绩排名」「绩点」这类干扰列
    val scorePair = items.firstOrNull { (k, _) ->
        k.contains("成绩") && !k.contains("排名") && !k.contains("绩点")
    }

    val footKeys = listOf("开课学期", "学期", "课程编号", "编号")
    val foot = items
        .filter { (k, _) -> footKeys.any { k.contains(it) } }
        .map { it.second }
        .distinct()

    val main = items.filter { (k, _) ->
        k != coursePair.first && k != scorePair?.first && footKeys.none { k.contains(it) }
    }

    val scoreText = scorePair?.second.orEmpty()
    val fail = isFailingScore(scoreText)

    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = coursePair.second,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (scoreText.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = scoreText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (fail) failRedColor() else MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
            main.forEach { (k, v) -> FieldRow(k, v, first = false) }
            if (foot.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                Text(
                    text = foot.joinToString(" · "),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/**
 * 是否不及格。
 *
 * 百分制看数值 < 60；文字制（合格 / 良好 / 优秀）只有明确写了「不及格 / 不合格 / 未通过」
 * 才算 —— 不能简单地「找不到数字就判不及格」，那样「合格」会被误标成红色。
 */
private fun isFailingScore(raw: String): Boolean {
    val t = raw.trim()
    if (t.isEmpty()) return false
    val num = Regex("-?\\d+(?:\\.\\d+)?").find(t)?.value?.toDoubleOrNull()
    if (num != null) return num < 60.0
    return listOf("不及格", "不合格", "未通过", "未及格").any { t.contains(it) }
}

// ---------------- 等级考试成绩 ----------------

/**
 * 等级考试成绩。
 *
 * 三条用户要求：
 * 1. **考级课程当每条的小标题**（加粗），序号不再占标题位；
 * 2. 双层表头里那些**空着的子列**（「分数类成绩 / 笔试」之类）一律不显示，
 *    每组只保留一条真正有数据的 —— 实测大多数考级记录是「只有总成绩 / 只有等级」，
 *    把空子列全列出来只会让卡片变成一串空标签；
 * 3. 排序见 [gradeRowsSorted]。
 */
@Composable
private fun GradeCard(table: QueryTable, row: List<String>) {
    val items = table.labeled(row).filter { it.second.isNotBlank() }
    if (items.isEmpty()) return

    val titlePair = items.firstOrNull { it.first.contains("考级课程") }
        ?: items.firstOrNull { it.first.contains("等级名称") }
        ?: items.firstOrNull { it.first.contains("课程名称") }
        ?: items.first()

    // 每组只挑一条**真正有分数**的（详见 [pickGroup]）
    val scorePair = pickGroup(items, "分数类成绩")
    val gradePair = pickGroup(items, "等级类成绩")

    // ⚠️ 这里要按**整组**排除，不能只排除被选中的那一列：
    //    「分数类成绩」下可能挂着 笔试 / 机试 / 总成绩 三列，只排掉第一列的话，
    //    其余有值的子列会再以「分数类成绩·机试」的形式冒出来，等于没删干净。
    val rest = items.filter { (k, _) ->
        !k.contains("序号") &&
            !k.contains("分数类成绩") &&
            !k.contains("等级类成绩")
    }

    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = titlePair.second,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            // 成绩排在标题下面第一行，比其它属性更靠前
            scorePair?.let { FieldRow("分数类成绩", it, first = false) }
            gradePair?.let { FieldRow("等级类成绩", it, first = false) }
            rest.forEach { (k, v) ->
                FieldRow(k.replace(LABEL_SEP, "·"), v, first = false)
            }
        }
    }
}

/** 教务用来表示「这项没有成绩」的占位值 */
private val PLACEHOLDER_VALUES = setOf(
    "-", "--", "—", "－", "–", "/", "\\", "无", "没有", "空", "n/a", "na", "null",
)

/**
 * 该值是不是**真实的成绩**（而不是教务用来表示「没有」的占位值）。
 *
 * 🔴 `0` 也算占位，这一点很关键：教务对「这项不适用 / 没考」不是留空，而是填 `0`。
 *    考级成绩（四六级 0–710、计算机等级 0–100）不会出现真实的 0 分，
 *    所以把 0 当空处理是安全的 —— 反过来若把 0 当有效值，
 *    "第一个非空子列"就会挑中那个 0，而真正有分数的子列被丢掉。
 */
private fun isRealValue(raw: String): Boolean {
    val t = raw.trim()
    if (t.isEmpty()) return false
    if (t.lowercase() in PLACEHOLDER_VALUES) return false
    val num = t.toDoubleOrNull()
    if (num != null && num == 0.0) return false
    return true
}

/**
 * 从某个父表头（「分数类成绩」/「等级类成绩」）下面，挑出**那一条真正有分数的子列**。
 *
 * 强智这张表是双层表头，「分数类成绩」下挂着「笔试 / 机试 / 总成绩」等子列，
 * 而**大多数记录只有一个子列有分数，其余是 `0`**（见 [isRealValue]）。
 * 所以不能简单地取"第一个非空值"——实测就会挑到 `0`，
 * 界面上表现为「分数类成绩 0」而真正的总分不见了（用户反馈的问题）。
 *
 * 取值顺序：先滤掉占位值，再优先「总成绩 / 总评 / 总分」，其次「笔试 / 机试」，
 * 都没有就按表头顺序取第一个。整组都没有真实分数时返回 null（那一行干脆不显示）。
 */
private fun pickGroup(items: List<Pair<String, String>>, parent: String): String? {
    val group = items.filter { (k, v) -> k.contains(parent) && isRealValue(v) }
    if (group.isEmpty()) return null
    for (want in listOf("总成绩", "总评", "总分", "笔试", "机试")) {
        group.firstOrNull { (k, _) -> k.contains(want) }?.let { return it.second }
    }
    return group.first().second
}

/**
 * 等级考试成绩的展示顺序（用户指定）：
 * **CET-4 → CET-6 → NCRE 一级 → NCRE 二级 → 其他**；
 * 同一类里按考试时间排，**最近的排前面**（与课程成绩页「最近的学期排前面」保持一致）。
 *
 * 没有时间的那几条排在各自分类的末尾。
 */
internal fun gradeRowsSorted(table: QueryTable): List<List<String>> {
    val titleIdx = table.headers.indexOfFirst { it.contains("考级课程") }
    val timeIdx = table.headers.indexOfFirst {
        it.contains("开始时间") || it.contains("考试时间")
    }
    fun rank(row: List<String>): Int {
        val name = if (titleIdx >= 0) {
            row.getOrNull(titleIdx).orEmpty()
        } else {
            row.joinToString("")
        }
        return gradeRank(name)
    }
    fun epoch(row: List<String>): Long {
        val raw = if (timeIdx >= 0) row.getOrNull(timeIdx).orEmpty() else ""
        return parseDateOrNull(raw)?.toEpochDay() ?: Long.MIN_VALUE
    }
    return table.rows.sortedWith(
        compareBy<List<String>> { rank(it) }.thenByDescending { epoch(it) },
    )
}

/** 考级课程名 → 分类序号（越小越靠前） */
private fun gradeRank(name: String): Int {
    val n = name.uppercase().filterNot { it.isWhitespace() }
    return when {
        n.contains("四级") || n.contains("CET4") || n.contains("CET-4") -> 0
        n.contains("六级") || n.contains("CET6") || n.contains("CET-6") -> 1
        n.contains("一级") -> 2
        n.contains("二级") -> 3
        else -> 4
    }
}

private fun parseDateOrNull(raw: String): LocalDate? {
    val m = DATE_RE.find(raw) ?: return null
    return runCatching {
        LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }.getOrNull()
}

// ---------------- 通用兜底 ----------------

/** 认不出 kind 时的版式：首字段当标题，其余按「字段名 → 值」排列 */
@Composable
private fun GenericCard(table: QueryTable, row: List<String>) {
    val items = table.labeled(row).filter { it.second.isNotBlank() }
    if (items.isEmpty()) return
    val head = items.first()
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = head.second,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            items.drop(1).forEach { (k, v) -> FieldRow(k, v, first = false) }
        }
    }
}

// ---------------- 公共零件 ----------------

/**
 * 卡片内「字段名 → 值」一行。
 *
 * [first] = true 时不留顶部间距（紧跟标题），否则留 5dp 拉开各行。
 * 卡片内部各行的间距都走这里，保证三种卡片的行距完全一致。
 */
@Composable
private fun FieldRow(label: String, value: String, first: Boolean) {
    if (!first) Spacer(Modifier.height(5.dp))
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 极细分隔线：把「醒目区」和「字段区」轻轻分开 */
@Composable
private fun CardDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.16f)),
    )
}

/** 不及格红：按当前主题的明暗挑一档，浅底深红、深底亮红 */
@Composable
private fun failRedColor(): Color =
    if (MiuixTheme.colorScheme.onSurface.luminance() > 0.5f) FAIL_RED_DARK else FAIL_RED_LIGHT
