package com.shzu.superschedule.data

import com.shzu.superschedule.model.QueryKind
import com.shzu.superschedule.model.QueryTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * 教务查询数据的**后台**抓取（考试安排 / 课程成绩 / 等级考试成绩）。
 *
 * ## 请求怎么发（重要转变）
 *
 * 最初这里是自己用 `HttpURLConnection` 带 Cookie 请求的，但在真机上**全部被判未登录**：
 * 拿到的永远是 164 字节、无 `<title>` 的空壳跳转页，
 * 而**同一个会话在 WebView 里完全有效**（能正常打开考试安排查询页）。
 *
 * 那个差异最终没定位到，于是改成**把请求交给 WebView 发 `fetch`** ——
 * 通过 [QueryHtmlFetcher] 抽象，实现见 [com.shzu.superschedule.ui.WebViewFetcher]，
 * 由 UI 层在启动时 [install] 进来。浏览器发的请求天然带对 Cookie / UA / Referer。
 *
 * ## 抓取策略（对「不知道确切接口」的容错）
 *
 * 强智各校的查询页表单名、请求方式都不尽相同，所以这里**不写死一种打法**：
 *
 * 1. 先对候选地址发 **GET** —— 有的学校把学期放 query string 就能出结果；
 * 2. GET 拿回来的若是**查询表单页**（没有结果表），就把表单里的字段全读出来、
 *    把学期字段（name 含 `xnxq`）替换成目标学期，再 **POST** 一次；
 * 3. 两次都拿不到结果表 → 判定为「未查询到数据」（而不是失败）。
 *
 * ## 三种结果必须分开
 *
 * - [FetchResult.Ok]    拿到了数据
 * - [FetchResult.Empty] 页面正常，但教务就是没有这个学期的数据 → 提示「未查询到数据」
 * - [FetchResult.Fail]  通道/登录/结构问题 → 才算「失败」
 *
 * 混在一起的话，一个「这学期没考试」会被说成「导入失败」，用户会以为程序坏了。
 *
 * 每次请求的路径、返回长度、页面标题都会写进 [AppLog]，
 * 万一接口不对，日志里能直接看出教务实际返回了什么。
 */
object JwglQueryFetcher {

    private const val TAG = "QueryFetcher"

    /** 抓取通道；未注入时抓取会明确报「通道未就绪」，而不是静默失败 */
    @Volatile
    private var channel: QueryHtmlFetcher? = null

    fun install(fetcher: QueryHtmlFetcher) {
        channel = fetcher
        AppLog.i(TAG, "抓取通道已注入：${fetcher.javaClass.simpleName}")
    }

    sealed interface FetchResult {
        /** 抓到了数据 */
        data class Ok(val table: QueryTable) : FetchResult

        /** 请求成功，但该学期没有数据 */
        data class Empty(val semester: String) : FetchResult

        /** 请求本身失败（未登录 / 通道不通 / 找不到页面） */
        data class Fail(val reason: String) : FetchResult
    }

    /**
     * 批量抓取结果统计。
     *
     * 必须把「成功 / 教务没数据 / 请求失败」三种分开报给用户：
     * 一个「这学期没考试」和「通道不通」是完全不同的两件事，
     * 混成一句「导入失败」会让用户以为程序坏了。
     */
    data class Summary(
        val tables: List<QueryTable>,
        val ok: Int,
        val empty: Int,
        val fail: Int,
        /** 其中因「登录失效」失败的项数 */
        val authFail: Int = 0,
    ) {
        /**
         * 给用户看的短提示。
         *
         * 「登录失效」必须单独拎出来说 —— 真机上最常见的就是教务会话超时，
         * 这时候让用户跑去翻日志、怀疑路径，纯属浪费时间。
         */
        val hint: String
            get() = when {
                ok > 0 -> "已更新 $ok 组数据"
                authFail > 0 -> "教务登录已失效，请到「设置 → 重新导入课表」登录一次后重试"
                fail > 0 -> "获取失败（$fail 项）。连点「设置 → 关于 → 作者」7 下可查看运行日志"
                else -> "未查询到数据"
            }
    }

    /** 抓取单个查询（[semester] 为空表示该查询与学期无关） */
    suspend fun fetch(kind: QueryKind, semester: String): FetchResult =
        withContext(Dispatchers.IO) {
            val tag = "${kind.key}/${semester.ifBlank { "-" }}"
            val ch = channel ?: run {
                AppLog.w(TAG, "[$tag] 抓取通道未就绪")
                return@withContext FetchResult.Fail("抓取通道未就绪，请先打开一次课表或查询页")
            }

            var lastReason = "未找到可用的查询页面"
            for (path in kind.paths) {
                try {
                    val get = ch.html(path)
                    AppLog.i(
                        TAG,
                        "[$tag] GET $path -> len=${get?.length ?: -1} " +
                            "title=「${pageTitleOf(get)}」${bodyPeek(get)}",
                    )

                    if (get == null) {
                        lastReason = "请求超时或被中断"
                        continue
                    }
                    if (isFetchError(get)) {
                        lastReason = "请求失败：${fetchErrorOf(get)}"
                        continue
                    }
                    if (looksLikeLogin(get)) {
                        lastReason = "登录状态已失效，请重新导入课表完成登录"
                        break
                    }

                    // 1) GET 回来直接带结果表
                    interpret(get, kind, semester)?.let { return@withContext it }

                    // 2) 是查询表单页 → 带上学期提交
                    val params = formParams(get, semester)
                    if (params.isEmpty()) {
                        AppLog.d(TAG, "[$tag] $path 既无结果表也无表单，换下一个候选")
                        continue
                    }
                    val semesterField = params.keys.firstOrNull { it.contains("xnxq", true) }
                    AppLog.i(
                        TAG,
                        "[$tag] POST $path（${params.size} 个字段，学期字段=${semesterField ?: "无"}）",
                    )
                    val post = ch.html(path, params)
                    AppLog.i(
                        TAG,
                        "[$tag] POST $path -> len=${post?.length ?: -1} " +
                            "title=「${pageTitleOf(post)}」",
                    )
                    if (post == null) {
                        lastReason = "提交查询表单超时"
                        continue
                    }
                    if (isFetchError(post)) {
                        lastReason = "提交查询表单失败：${fetchErrorOf(post)}"
                        continue
                    }
                    if (looksLikeLogin(post)) {
                        lastReason = "登录状态已失效，请重新导入课表完成登录"
                        break
                    }
                    interpret(post, kind, semester)?.let { return@withContext it }

                    // 3) 表单提交成功但页面里没有数据表 → 该学期确实没数据
                    AppLog.i(TAG, "[$tag] 表单提交成功但无结果表，判定为「未查询到数据」")
                    return@withContext FetchResult.Empty(semester)
                } catch (t: Throwable) {
                    AppLog.e(TAG, "[$tag] 请求 $path 异常", t)
                    lastReason = t.message ?: t.javaClass.simpleName
                }
            }

            AppLog.w(TAG, "[$tag] 抓取失败：$lastReason")
            FetchResult.Fail(lastReason)
        }

    /**
     * 批量抓取：遍历 [semesters] × 需要按学期查的 kind，
     * 外加一次与学期无关的查询（等级考试）。
     *
     * 任何一项失败都不会中断整体（导入课表不该被它拖垮），失败的项只写日志。
     */
    suspend fun fetchAll(semesters: List<String>): Summary = withContext(Dispatchers.IO) {
        val out = mutableListOf<QueryTable>()
        var ok = 0
        var empty = 0
        var fail = 0
        var authFail = 0
        val list = semesters.filter { it.isNotBlank() }
        AppLog.i(TAG, "开始批量抓取：${list.size} 个学期 × ${QueryKind.entries.size} 类查询")

        for (kind in QueryKind.entries) {
            if (!kind.perSemester) {
                when (val r = fetch(kind, "")) {
                    is FetchResult.Ok -> {
                        out.add(r.table); ok++
                    }

                    is FetchResult.Empty -> {
                        empty++
                        AppLog.i(TAG, "${kind.label}：未查询到数据")
                    }

                    is FetchResult.Fail -> {
                        fail++
                        if (r.reason.contains("登录")) authFail++
                        AppLog.w(TAG, "${kind.label}：${r.reason}")
                    }
                }
                continue
            }
            for (sem in list) {
                when (val r = fetch(kind, sem)) {
                    is FetchResult.Ok -> {
                        out.add(r.table); ok++
                    }

                    is FetchResult.Empty -> {
                        empty++
                        AppLog.i(TAG, "${kind.label} $sem：未查询到数据")
                    }

                    is FetchResult.Fail -> {
                        fail++
                        if (r.reason.contains("登录")) authFail++
                        AppLog.w(TAG, "${kind.label} $sem：${r.reason}")
                    }
                }
            }
        }
        AppLog.i(TAG, "批量抓取结束：成功 $ok / 未查询到数据 $empty / 失败 $fail（其中登录失效 $authFail）")
        Summary(out, ok, empty, fail, authFail)
    }

    // ---------------- 内部 ----------------

    /** 解析一页；返回 null 表示「这一页上没有结果表」，需要继续尝试 */
    private fun interpret(html: String, kind: QueryKind, semester: String): FetchResult? {
        val parsed = JwglQueryParser.parse(html, kind.key, QueryStore.now()) ?: return null
        val withSem = parsed.copy(semester = if (kind.perSemester) semester else "")
        return if (withSem.rows.isEmpty()) {
            FetchResult.Empty(semester)
        } else {
            FetchResult.Ok(withSem)
        }
    }

    /**
     * 从页面里读出查询表单的字段，并把学期字段换成目标学期。
     *
     * 「学期字段」的判定：`name` 含 `xnxq`（强智的统一命名，如 `xnxq01id`）。
     */
    private fun formParams(html: String, semester: String): Map<String, String> {
        val doc = Jsoup.parse(html)
        val form = doc.select("form").firstOrNull { f ->
            f.select("input[name], select[name]").isNotEmpty()
        } ?: return emptyMap()

        val params = LinkedHashMap<String, String>()
        form.select("input[name]").forEach { inp ->
            val type = inp.attr("type").lowercase()
            if (type in setOf("submit", "button", "image", "reset", "file")) return@forEach
            if (type in setOf("checkbox", "radio") && !inp.hasAttr("checked")) return@forEach
            params[inp.attr("name")] = inp.attr("value")
        }
        form.select("select[name]").forEach { sel ->
            val name = sel.attr("name")
            val opt = sel.selectFirst("option[selected]") ?: sel.selectFirst("option")
            params[name] = if (semester.isNotBlank() && name.contains("xnxq", ignoreCase = true)) {
                semester
            } else {
                opt?.attr("value").orEmpty()
            }
        }
        form.select("textarea[name]").forEach { ta ->
            params[ta.attr("name")] = ta.text()
        }
        return params
    }

    /**
     * 短响应直接把内容打进日志。
     *
     * 教务返回异常时往往只给几十字节（重定向壳 / 错误 JSON），
     * 光看长度完全猜不出是什么；把内容打出来一眼就能定位。
     * 只在 < 300 字节时打，正常页面不受影响，也不会把大段 HTML 灌进日志。
     */
    private fun bodyPeek(html: String?): String {
        if (html == null || html.length >= 300) return ""
        return " body=「${html.replace('\n', ' ').replace('\r', ' ').trim()}」"
    }

    /** JS fetch 失败时注入的哨兵 */
    private fun isFetchError(html: String): Boolean = html.startsWith("<!--FETCH-ERROR")

    private fun fetchErrorOf(html: String): String =
        html.removePrefix("<!--FETCH-ERROR").removeSuffix("-->").trim().take(80)

    /** 页面标题（截 40 字），仅用于日志诊断 */
    private fun pageTitleOf(html: String?): String {
        if (html.isNullOrBlank()) return ""
        return runCatching { Jsoup.parse(html).title().trim().take(40) }.getOrDefault("")
    }

    /**
     * 是否被踢回了登录/认证流程。
     *
     * ⚠️ 实测确认：未登录时教务会给一个**空壳跳转页**（实测仅 164 / 16 字节），
     * 里面既没有 `<title>` 也没有表单，只有一句 JS 跳转：
     *
     * ```html
     * <script>window.location.href='http://authserver.shzu.edu.cn/authserver/login?service=...'</script>
     * ```
     *
     * 只按「标题含登录 / 有没有登录表单」判会漏掉它，从而把登录问题误报成
     * 「找不到查询页」，把人往 URL 方向带偏（我一开始就栽在这上面）。
     */
    private fun looksLikeLogin(html: String): Boolean {
        if (html.isBlank()) return false
        if (html.contains("authserver.shzu.edu.cn") || html.contains("authserver/login")) {
            return true
        }
        // 强智在未登录时返回一小段 JSON（实测原文）：
        //   {"flag1":2,"msgContent":"请先登录系统"}
        // 既没有 title 也没有表单，必须单独认出来。
        //
        // ⚠️ 判据必须用 **ASCII 字段名**，不能匹配中文：教务这段 JSON 用 GBK 编码
        // 却没有声明 charset，经 WebView 渲染后中文会变成乱码
        // （"请先登录系统" → "璇峰厛鐧诲綍绯荤粺"），匹配中文会大面积漏判。
        if (html.length < 400 &&
            (html.contains("msgContent") || html.contains("flag1") || html.contains("请先登录"))
        ) {
            return true
        }
        if (html.length < 800 &&
            (html.contains("location.href") || html.contains("location.replace"))
        ) {
            return true
        }
        val doc = Jsoup.parse(html)
        if (doc.selectFirst("form[action*=casLogin], #loginForm, #casLoginForm") != null) {
            return true
        }
        val title = doc.title()
        return title.contains("登录") || title.contains("认证")
    }
}
