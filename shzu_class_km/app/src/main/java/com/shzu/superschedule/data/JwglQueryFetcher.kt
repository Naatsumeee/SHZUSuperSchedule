package com.shzu.superschedule.data

import com.shzu.superschedule.model.QueryKind
import com.shzu.superschedule.model.QueryTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    /**
     * 每个查询页之间的间隔。
     *
     * 实测：连着快速发十几次请求，教务会直接判定为爬虫并**踢掉整个会话**
     * （用户反馈「点一次刷新后登录就掉了」）。宁可整体慢几十秒，也不能把会话搞丢。
     */
    private const val STEP_DELAY_MS = 1_200L

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

        /**
         * 请求成功，但该学期没有数据。
         *
         * 🔴 [table] 不能省。查询存档是按 (kind, semester) 做**覆盖式合并**的
         * （见 [QueryStore.merge]），只有出现在 [fetchAll] 结果里的项才会顶掉旧值。
         * 早先这里不带表，于是「教务现在没数据」这个事实**永远写不回存储**——
         * 旧版本抓到的占位行（强智在空结果时会给一行「未查询到数据」）
         * 就一直留在存档里，界面上表现为「考试安排 · 共 6 条」这种假数据。
         * 带上这张 0 行的表，合并时才能把它替换成空，计数归零。
         */
        data class Empty(val semester: String, val table: QueryTable? = null) : FetchResult

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
                    // 一次到位：打开查询页 → 在它的 iframe 里选学期并点「查询」→ 收目标 iframe。
                    //
                    // 早先是「先 GET 探页面、再 POST 提交表单」两步。第二步那些表单字段
                    // 是 Kotlin 侧把**所有 iframe 拼起来**用 Jsoup 找出来的，抓到的往往是
                    // 主框架里常驻的「修改个人信息」表单 —— 提交了等于没提交
                    // （实测 GET 与 POST 返回长度一字不差）。现在把选学期、点按钮
                    // 全部下放到目标 iframe 内部完成，Kotlin 侧只传学期代码。
                    val html = ch.html(
                        path,
                        semester.takeIf { kind.perSemester && it.isNotBlank() },
                        kind.menuCall,
                    )
                    AppLog.i(
                        TAG,
                        "[$tag] GET $path -> len=${html?.length ?: -1} " +
                            "title=「${pageTitleOf(html)}」${bodyPeek(html)}",
                    )
                    logFrameSummary(html)

                    if (html == null) {
                        lastReason = "请求超时或被中断"
                        continue
                    }
                    if (isFetchError(html)) {
                        lastReason = "请求失败：${fetchErrorOf(html)}"
                        continue
                    }
                    if (looksLikeLogin(html)) {
                        lastReason = "登录状态已失效，请重新导入课表完成登录"
                        break
                    }

                    interpret(html, kind, semester)?.let { return@withContext it }

                    // 页面正常、但没有目标结果表 → 该学期确实没数据
                    //
                    // 注意这里**刻意不返回空表**：走这条分支意味着连结果表的表头都没认出来
                    // （最可能是教务改版），此时存档里若已有旧数据，保留它比清空更有用。
                    // 与 interpret() 里「表认出来了、只是 0 行」那种明确的无数据区分开。
                    AppLog.i(TAG, "[$tag] 页面无目标结果表，判定为「未查询到数据」（存档若有旧值则保留）")
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

        // 先把主框架的菜单结构 dump 一份进日志：三类查询各自挂在哪个入口、
        // kjcdShow 的实参是什么，全在这里，省得每次靠猜。
        runCatching { channel?.dumpMenu() }
            .onFailure { AppLog.w(TAG, "菜单诊断失败：${it.message}") }

        for (kind in QueryKind.entries) {
            if (!kind.perSemester) {
                when (val r = fetch(kind, "")) {
                    is FetchResult.Ok -> {
                        out.add(r.table); ok++
                    }

                    is FetchResult.Empty -> {
                        empty++
                        // 空的表也要收进结果：合并是覆盖式的，不收就等于旧数据永远清不掉
                        r.table?.let { out.add(it) }
                        AppLog.i(TAG, "${kind.label}：未查询到数据")
                    }

                    is FetchResult.Fail -> {
                        fail++
                        if (r.reason.contains("登录")) authFail++
                        AppLog.w(TAG, "${kind.label}：${r.reason}")
                    }
                }
                // 每访问一个查询页之间停一下：连续快速请求会被教务当成爬虫，
                // 实测「点一次刷新后登录态直接掉」就是这么来的。
                delay(STEP_DELAY_MS)
                continue
            }
            for (sem in list) {
                when (val r = fetch(kind, sem)) {
                    is FetchResult.Ok -> {
                        out.add(r.table); ok++
                    }

                    is FetchResult.Empty -> {
                        empty++
                        // 同上：空表写回，才能把该学期的旧数据替换掉
                        r.table?.let { out.add(it) }
                        AppLog.i(TAG, "${kind.label} $sem：未查询到数据")
                    }

                    is FetchResult.Fail -> {
                        fail++
                        if (r.reason.contains("登录")) authFail++
                        AppLog.w(TAG, "${kind.label} $sem：${r.reason}")
                    }
                }
                delay(STEP_DELAY_MS)
            }
        }
        AppLog.i(TAG, "批量抓取结束：成功 $ok / 未查询到数据 $empty / 失败 $fail（其中登录失效 $authFail）")
        Summary(out, ok, empty, fail, authFail)
    }

    // ---------------- 内部 ----------------

    /** 解析一页；返回 null 表示「这一页上没有目标结果表」，需要继续尝试 */
    private fun interpret(html: String, kind: QueryKind, semester: String): FetchResult? {
        // 必须带表头关键词：拼进来的 HTML 里混着无关 iframe 的表，
        // 不靠特征认表就会抓错（考试安排曾经返回了课程成绩的数据）。
        val parsed = JwglQueryParser.parse(html, kind.key, QueryStore.now(), kind.headerKeywords)
            ?: return null
        // 标题固定用我们自己的名称。
        // 不能用页面的 <title>：主框架里常驻着「修改个人信息」之类的 iframe，
        // 它也会被一起收进来，取到的标题会是那个，看着像抓错了页面。
        val withSem = parsed.copy(
            semester = if (kind.perSemester) semester else "",
            title = kind.label,
        )
        return if (withSem.rows.isEmpty()) {
            FetchResult.Empty(semester, withSem)
        } else {
            FetchResult.Ok(withSem)
        }
    }

    /**
     * 把收集到的各 iframe 摘要写进日志（src / 标题 / 大小 / 表头）。
     *
     * 抓取时如果目标 iframe 没匹配上就会退回「全收」，出问题时必须能看出
     * 「到底收了几个、分别是什么」，否则只能瞎猜。
     */
    private fun logFrameSummary(html: String?) {
        if (html.isNullOrBlank()) return
        val marks = Regex("<!--FRAME[^>]*-->").findAll(html).take(12).toList()
        if (marks.isEmpty()) {
            AppLog.w(TAG, "  未收集到任何 iframe（可能登录态失效或页面未加载完）")
            return
        }
        marks.forEach { AppLog.i(TAG, "  ${it.value.removePrefix("<!--").removeSuffix("-->")}") }
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
