package com.shzu.superschedule.data

import com.shzu.superschedule.model.QueryKind
import com.shzu.superschedule.model.QueryTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 教务查询数据的**后台**抓取（考试安排 / 课程成绩 / 等级考试成绩）。
 *
 * ## 为什么走 HTTP 而不是再开一个 WebView
 *
 * 后台抓取不需要渲染页面（用户看不到），也就不需要 WebView 那一坨内存开销。
 * 复用 WebView 已经建立的登录 Cookie（[JwglSession.cookie]），
 * 直接 GET/POST 拿 HTML，再用 [JwglQueryParser] 解析。
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
 * - [FetchResult.Fail]  网络/登录/结构问题 → 才算「失败」
 *
 * 混在一起的话，一个「这学期没考试」会被说成「导入失败」，用户会以为程序坏了。
 *
 * 每次请求的 URL / 状态码 / HTML 长度都会写进 [AppLog]，
 * 万一接口不对，日志里能直接看出教务实际返回了什么。
 */
object JwglQueryFetcher {

    private const val TAG = "QueryFetcher"
    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 20000

    sealed interface FetchResult {
        /** 抓到了数据 */
        data class Ok(val table: QueryTable) : FetchResult

        /** 请求成功，但该学期没有数据 */
        data class Empty(val semester: String) : FetchResult

        /** 请求本身失败（未登录 / 网络 / 找不到页面） */
        data class Fail(val reason: String) : FetchResult
    }

    /** 抓取单个查询（[semester] 为空表示该查询与学期无关） */
    suspend fun fetch(kind: QueryKind, semester: String): FetchResult =
        withContext(Dispatchers.IO) {
            val tag = "${kind.key}/${semester.ifBlank { "-" }}"
            val cookie = JwglSession.cookie()
            if (cookie.isNullOrBlank()) {
                AppLog.w(TAG, "[$tag] 中止：无可用 Cookie（未登录）")
                return@withContext FetchResult.Fail("尚未登录教务系统")
            }

            var lastReason = "未找到可用的查询页面"
            for (path in kind.paths) {
                val url = JwglSession.BASE + path
                try {
                    val get = request(url, cookie, "GET", null, JwglSession.MAIN_FRAME_URL)
                    // 把返回页面的 <title> 也记下来 —— 路径猜错时，
                    // 一眼就能从标题看出教务到底返回的是什么页（错误页？登录页？框架页？）
                    AppLog.i(
                        TAG,
                        "[$tag] GET $path -> HTTP ${get?.code ?: -1} " +
                            "len=${get?.html?.length ?: -1} title=「${pageTitleOf(get?.html)}」",
                    )

                    if (get == null) {
                        lastReason = "网络请求失败"
                        continue
                    }
                    if (get.code == 404) {
                        lastReason = "页面不存在（$path）"
                        continue
                    }
                    if (looksLikeLogin(get)) {
                        lastReason = "登录状态已失效，请重新导入课表完成登录"
                        break
                    }

                    // 1) GET 回来直接带结果表
                    interpret(get.html, kind, semester)?.let { return@withContext it }

                    // 2) 是查询表单页 → 带上学期提交
                    val params = formParams(get.html, semester)
                    if (params.isEmpty()) {
                        AppLog.d(TAG, "[$tag] $path 既无结果表也无表单，换下一个候选")
                        continue
                    }
                    val post = post(url, cookie, params, referer = url)
                    AppLog.i(
                        TAG,
                        "[$tag] POST $path（${params.size} 个字段，" +
                            "学期字段=${params.keys.firstOrNull { it.contains("xnxq", true) } ?: "无"}）" +
                            " -> HTTP ${post?.code ?: -1} len=${post?.html?.length ?: -1}",
                    )
                    if (post == null) {
                        lastReason = "提交查询表单失败"
                        continue
                    }
                    if (looksLikeLogin(post)) {
                        lastReason = "登录状态已失效，请重新导入课表完成登录"
                        break
                    }
                    interpret(post.html, kind, semester)?.let { return@withContext it }

                    // 3) 表单提交成功但页面里没有数据表 → 该学期确实没数据
                    AppLog.i(TAG, "[$tag] 表单提交成功但无结果表，判定为「未查询到数据」")
                    return@withContext FetchResult.Empty(semester)
                } catch (t: Throwable) {
                    AppLog.e(TAG, "[$tag] 请求 $path 异常", t)
                    lastReason = t.message ?: t.javaClass.simpleName
                }
            }

            // 候选路径全军覆没 → 从主框架页里把真实地址扒出来再试一次。
            // 教务是 iframe 框架布局，查询页地址藏在菜单里，靠猜是猜不到的。
            ensureDiscovered(cookie)[kind.key]?.let { url ->
                AppLog.i(TAG, "[$tag] 用探测到的地址重试：$url")
                request(url, cookie, "GET", null, JwglSession.MAIN_FRAME_URL)?.let { resp ->
                    interpret(resp.html, kind, semester)?.let { return@withContext it }
                }
            }

            AppLog.w(TAG, "[$tag] 抓取失败：$lastReason")
            FetchResult.Fail(lastReason)
        }

    /**
     * 批量抓取结果统计。
     *
     * 必须把「成功 / 教务没数据 / 请求失败」三种分开报给用户：
     * 一个「这学期没考试」和「路径不对抓不到」是完全不同的两件事，
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
         * 这时候让用户跑去翻日志、怀疑路径，纯属浪费时间；
         * 直接告诉他去重新登录一次就行。
         */
        val hint: String
            get() = when {
                ok > 0 -> "已更新 $ok 组数据"
                authFail > 0 -> "教务登录已失效，请到「设置 → 重新导入课表」登录一次后重试"
                fail > 0 -> "获取失败（$fail 项）。连点「设置 → 关于 → 作者」7 下可查看运行日志"
                else -> "未查询到数据"
            }
    }

    /**
     * 批量抓取：遍历 [semesters] × 需要按学期查的 kind，
     * 外加一次与学期无关的查询（等级考试）。
     *
     * 任何一项失败都不会中断整体（导入课表不该被它拖垮），
     * 失败的项只写日志。
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

    private data class Resp(
        val code: Int,
        val html: String,
        /** 跟随重定向后的最终地址（用来识别「被踢回登录页」） */
        val finalUrl: String = "",
    )

    private fun request(
        url: String,
        cookie: String,
        method: String,
        body: String?,
        referer: String,
    ): Resp? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Cookie", cookie)
            setRequestProperty("User-Agent", JwglSession.DESKTOP_UA)
            setRequestProperty("Referer", referer)
            setRequestProperty(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            )
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true
            if (method == "POST") {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        }
        if (body != null) {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        // 跟随重定向后的最终地址：未登录会被甩到 /jsxsd/sso.jsp
        val finalUrl = conn.url.toString()
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        Resp(code, text, finalUrl)
    }.onFailure {
        AppLog.e(TAG, "请求 $url 失败", it)
    }.getOrNull()

    private fun post(url: String, cookie: String, params: Map<String, String>, referer: String): Resp? {
        val body = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return request(url, cookie, "POST", body, referer)
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

    // ---------------- 真实地址探测 ----------------

    /** 探测结果缓存（一次运行只探测一轮，否则每个学期都要重跑几十个请求） */
    @Volatile
    private var discovered: Map<String, String>? = null

    private fun ensureDiscovered(cookie: String): Map<String, String> {
        discovered?.takeIf { it.isNotEmpty() }?.let { return it }
        val d = discover(cookie)
        if (d.isNotEmpty()) discovered = d
        return d
    }

    /**
     * 从主框架页里探测三类查询的真实地址。
     *
     * ## 为什么必须探测
     *
     * 强智是 **iframe 框架式布局**：主框架页（`xsMain.htmlx`）的地址**永远不变**，
     * 点菜单只是在内部 iframe 里换子页面 —— 用户看地址栏根本看不到子页地址，
     * 开发者也没法靠「看一眼 URL」拿到。所以只能：
     *
     * 1. 拉主框架页，把里面所有 `.do` / `.htmlx` 链接、以及内联 JS 里的路径全捞出来
     *    （强智的菜单多半是 JS 拼出来的，光看 `<a href>` 不够）；
     * 2. 逐个 GET，凡是能解析出「结果表」的，按标题关键词归类到三类查询。
     *
     * 全程写日志：用户把日志复制出来，开发者就能据此把 [QueryKind.paths] 修正掉。
     */
    private fun discover(cookie: String): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        for (frameUrl in listOf(JwglSession.MAIN_FRAME_URL, JwglSession.MAIN_FRAME_URL_LEGACY)) {
            val resp = request(frameUrl, cookie, "GET", null, JwglSession.BASE) ?: continue
            AppLog.i(
                TAG,
                "探测主框架 $frameUrl -> HTTP ${resp.code} len=${resp.html.length} " +
                    "title=「${pageTitleOf(resp.html)}」",
            )
            if (resp.code !in 200..299 || resp.html.isBlank()) continue
            if (looksLikeLogin(resp)) {
                // 未登录时探测毫无意义（每个候选都会被打回登录页），
                // 直接收工，免得白刷几十个请求
                AppLog.w(TAG, "探测中止：未登录（被重定向到 ${resp.finalUrl}）")
                return found
            }

            val candidates = candidateUrls(resp.html)
            AppLog.i(TAG, "主框架页提取到 ${candidates.size} 个候选地址")
            candidates.forEach { AppLog.i(TAG, "  候选: $it") }
            if (candidates.isEmpty()) continue

            for (u in candidates) {
                val r = request(u, cookie, "GET", null, frameUrl) ?: continue
                val title = pageTitleOf(r.html)
                val rows = JwglQueryParser.parse(r.html, "probe", QueryStore.now())?.count ?: 0
                AppLog.i(TAG, "  试探 $u -> HTTP ${r.code} 标题「$title」表格行数=$rows")
                if (rows <= 0) continue
                val kind = kindByText(title, u) ?: continue
                if (!found.containsKey(kind.key)) {
                    found[kind.key] = u
                    AppLog.i(TAG, "  ✔ 识别为「${kind.label}」→ $u")
                }
            }
            break // 新版主框架页能用就不用再试老版
        }
        AppLog.i(TAG, "探测结束，识别出 ${found.size} 类查询地址")
        return found
    }

    /** 把主框架页里所有可能的子页地址捞出来 */
    private fun candidateUrls(html: String): List<String> {
        val out = LinkedHashSet<String>()
        val doc = Jsoup.parse(html)

        fun add(raw: String?) {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty() || s.startsWith("javascript:") || s.startsWith("#")) return
            val full = when {
                s.startsWith("http") -> s
                s.startsWith("/") -> JwglSession.BASE + s
                else -> return
            }
            if (!full.contains("jwgl.shzu.edu.cn")) return
            if (!Regex("\\.(do|htmlx|jsp)(\\?|$)").containsMatchIn(full)) return
            out.add(full)
        }

        doc.select("a[href]").forEach { add(it.attr("href")) }
        doc.select("iframe[src]").forEach { add(it.attr("src")) }
        doc.select("frame[src]").forEach { add(it.attr("src")) }
        // 强智的菜单常由 JS 拼出，链接只存在于脚本字符串里
        Regex("['\"]([^'\"]{3,140}?\\.(?:do|htmlx))['\"]").findAll(html).forEach {
            add(it.groupValues[1])
        }
        return out.take(80).toList()
    }

    /** 按页面标题 + URL 关键词判断这是哪一类查询 */
    private fun kindByText(title: String, url: String): QueryKind? {
        val t = "$title $url"
        return when {
            t.contains("等级") || t.contains("djks") -> QueryKind.GRADE
            t.contains("考试") || t.contains("xsks") || t.contains("ksap") -> QueryKind.EXAM
            t.contains("成绩") || t.contains("cjcx") -> QueryKind.SCORE
            else -> null
        }
    }

    /** 页面标题（截 40 字），仅用于日志诊断 */
    private fun pageTitleOf(html: String?): String {
        if (html.isNullOrBlank()) return ""
        return runCatching { Jsoup.parse(html).title().trim().take(40) }.getOrDefault("")
    }

    /**
     * 是否被踢回了登录/认证流程。
     *
     * ⚠️ 这里有个很坑的地方（实测确认）：未登录时教务返回 **302 → `/jsxsd/sso.jsp`**，
     * 而 sso.jsp 本身是 **HTTP 200**，内容只有一段 JS 跳转脚本：
     *
     * ```html
     * <script languge='javascript'>
     *   window.location.href='http://authserver.shzu.edu.cn/authserver/login?service=...'
     * </script>
     * ```
     *
     * 它**既没有 `<title>` 也没有表单** —— 只按「标题含登录/有没有登录表单」去判，
     * 会认不出来，于是请求就被误判成「路径不对，找不到查询页」，
     * 白白把矛头指向 URL（我一开始就栽在这上面）。
     *
     * 所以三重判据：最终地址、响应体里的 authserver 特征串、以及传统登录表单。
     */
    private fun looksLikeLogin(resp: Resp): Boolean {
        val url = resp.finalUrl
        if (url.contains("sso.jsp") || url.contains("authserver")) return true

        val html = resp.html
        if (html.isBlank()) return false
        if (html.contains("authserver.shzu.edu.cn") || html.contains("authserver/login")) {
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
