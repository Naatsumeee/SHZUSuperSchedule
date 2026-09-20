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
                    if (looksLikeLogin(get.html)) {
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
                    if (looksLikeLogin(post.html)) {
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
    ) {
        /** 给用户看的短提示 */
        val hint: String
            get() = when {
                ok > 0 -> "已更新 $ok 组数据"
                fail > 0 -> "获取失败（$fail 项）。请确认已登录教务；" +
                    "连点「设置 → 关于 → 作者」7 下可查看运行日志"
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
                        AppLog.w(TAG, "${kind.label} $sem：${r.reason}")
                    }
                }
            }
        }
        AppLog.i(TAG, "批量抓取结束：成功 $ok / 未查询到数据 $empty / 失败 $fail")
        Summary(out, ok, empty, fail)
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

    private data class Resp(val code: Int, val html: String)

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
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        Resp(code, text)
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

    /** 页面标题（截 40 字），仅用于日志诊断 */
    private fun pageTitleOf(html: String?): String {
        if (html.isNullOrBlank()) return ""
        return runCatching { Jsoup.parse(html).title().trim().take(40) }.getOrDefault("")
    }

    /** 是否被踢回了登录/认证页 */
    private fun looksLikeLogin(html: String): Boolean {
        if (html.isBlank()) return false
        val doc = Jsoup.parse(html)
        if (doc.selectFirst("form[action*=casLogin], #loginForm, #casLoginForm") != null) {
            return true
        }
        val title = doc.title()
        return title.contains("登录") || title.contains("认证")
    }
}
