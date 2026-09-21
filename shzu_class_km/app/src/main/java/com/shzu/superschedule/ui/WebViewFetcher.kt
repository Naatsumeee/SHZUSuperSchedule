package com.shzu.superschedule.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.shzu.superschedule.data.AppLog
import com.shzu.superschedule.data.JwglSession
import com.shzu.superschedule.data.QueryHtmlFetcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 用 WebView 执行 `fetch` 来抓教务页面 —— [QueryHtmlFetcher] 的实现。
 *
 * ## 为什么绕这一圈
 *
 * 用 `HttpURLConnection` 手工带 Cookie 请求时，真机上**永远被判未登录**
 * （返回 164 字节无标题的空壳页），而同一个会话在 WebView 里完全正常。
 * Cookie 层面的差异始终没定位到，索性**把请求交给浏览器**：
 * WebView 发的 `fetch` 自带正确的 Cookie / UA / Referer，同源策略也由它处理。
 *
 * ## 为什么 WebView 是 1×1 而不是完全隐藏
 *
 * Android 的 WebView 必须**真实 attach 到视图树**才能可靠地执行 JS 与发请求；
 * 完全不挂载的「headless WebView」在部分 ROM 上不会触发页面加载。
 * 所以这里用一个 1×1 像素、不可见的 `AndroidView` 承载它 ——
 * 用户看不到，但它是一个正常工作的浏览器实例。
 *
 * ## 就绪时机
 *
 * 构造后会先 `loadUrl` 一个教务页面，**等它加载完成再允许 fetch**：
 * 必须让 WebView 先处在教务域的同源上下文里，之后的相对路径请求才带得上会话。
 */
class WebViewFetcher : QueryHtmlFetcher {

    companion object {
        private const val TAG = "WebViewFetcher"
        private const val ATTACH_TIMEOUT_MS = 15_000L
        private const val READY_TIMEOUT_MS = 25_000L
        private const val FETCH_TIMEOUT_MS = 30_000L
    }

    @Volatile
    private var webView: WebView? = null

    /**
     * 已挂载的信号。
     *
     * 「注入抓取通道」和「AndroidView 真正创建出 WebView」之间有时序差：
     * 导入课表成功后会立刻触发抓取，而承载 WebView 的宿主可能刚进入组合、
     * 还没执行 factory。所以 [html] 要先等一等，而不是直接报「未就绪」。
     */
    @Volatile
    private var attachSignal = CompletableDeferred<Unit>()

    /** 页面加载完成的信号（用于等待 WebView 就绪） */
    @Volatile
    private var ready = CompletableDeferred<Unit>()

    /** 当前等待中的请求；同一时刻只允许一个，避免回调串台 */
    @Volatile
    private var pending: CompletableDeferred<String>? = null

    /**
     * 等待「下一次页面加载完成」的信号。
     *
     * 抓取用**导航方式**（`loadUrl`）而不是 `fetch`：实测 `fetch('/jsxsd/…')`
     * 会被教务判为「请先登录系统」（返回 33 字节 JSON），
     * 而同一个 WebView 里**正常导航**加载同样的地址却能拿到完整页面。
     * 说明教务对 XHR 与页面导航的处理不一样，那就老老实实走导航。
     */
    @Volatile
    private var pageReady: CompletableDeferred<Unit>? = null

    fun attach(wv: WebView) {
        webView = wv
        if (!attachSignal.isCompleted) attachSignal.complete(Unit)
    }

    fun detach() {
        webView = null
        pending?.cancel()
        pending = null
        attachSignal = CompletableDeferred()
    }

    /** 供 [AndroidBridge.onFetch] 调用（JS 线程，可能非主线程） */
    fun deliver(html: String) {
        val d = pending
        pending = null
        if (d == null) {
            AppLog.w(TAG, "收到无人等待的抓取结果（${html.length} 字节），已忽略")
            return
        }
        d.complete(html)
    }

    override suspend fun html(path: String, form: Map<String, String>?): String? {
        // 等宿主把 WebView 创建出来（导入后立刻抓取时会有这个时序差）
        if (webView == null) {
            withTimeoutOrNull(ATTACH_TIMEOUT_MS) { attachSignal.await() }
        }
        val wv = webView ?: run {
            AppLog.w(TAG, "抓取通道未就绪：WebView 始终没有挂载")
            return null
        }

        // 等 WebView 先进入教务域上下文
        if (!ready.isCompleted) {
            val ok = withTimeoutOrNull(READY_TIMEOUT_MS) { ready.await() }
            if (ok == null) {
                AppLog.w(TAG, "等待抓取用 WebView 就绪超时")
                return null
            }
        }

        val url = JwglSession.BASE + path
        AppLog.d(TAG, "发起抓取：${if (form == null) "GET" else "POST"} $path")

        // 关键对照：CookieManager（全局、含 HttpOnly）里到底有没有会话 Cookie。
        // 只记名字与总长，不记值。
        // 强制把 CookieManager 里的 Cookie 落盘并同步到 WebView 实例。
        // 抓取用的 WebView 是 App 启动时创建的（那时还没登录），
        // 而会话 Cookie 是之后在导入页 WebView 里登录才写进去的 ——
        // 不刷新的话，这个 WebView 的请求可能仍按旧状态发出。
        runCatching { CookieManager.getInstance().flush() }

        val cmCookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        val cmNames = cmCookie.orEmpty()
            .split(';')
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
            .joinToString(",")
        AppLog.i(TAG, "CookieManager 视角：len=${cmCookie?.length ?: 0} names=[$cmNames]")

        // 第一次导航。
        //
        // 🔑 强智的查询页（xsksap_query / cjcx_query 等）会**校验 Referer**：
        // 只有「从主框架页点进来的」请求才认，直接访问一律回
        // `{"flag1":2,"msgContent":"请先登录系统"}`。
        // 当初用 HttpURLConnection 时显式带了 Referer 所以成功过一次，
        // 改用 WebView 导航后 Referer 变成「上一个页面」，查询页就全被拒了。
        // 这里用 loadUrl(url, headers) 把 Referer 固定成主框架页。
        val first = CompletableDeferred<Unit>()
        pageReady = first
        withContext(Dispatchers.Main) {
            wv.loadUrl(
                url,
                mapOf("Referer" to JwglSession.MAIN_FRAME_URL),
            )
        }
        if (withTimeoutOrNull(FETCH_TIMEOUT_MS) { first.await() } == null) {
            pageReady = null
            AppLog.w(TAG, "导航超时：$path")
            return null
        }

        // 需要查学期的，接着在本页填表并提交（等价于用户选完学期点「查询」）
        if (form != null) {
            val second = CompletableDeferred<Unit>()
            pageReady = second
            withContext(Dispatchers.Main) {
                wv.evaluateJavascript(buildSubmitJs(form), null)
            }
            if (withTimeoutOrNull(FETCH_TIMEOUT_MS) { second.await() } == null) {
                pageReady = null
                AppLog.w(TAG, "提交表单后加载超时：$path")
                return null
            }
        }
        pageReady = null

        return readDom(wv)
    }

    /**
     * 把当前页面的 DOM 读回来。
     *
     * 顺带回传一次**诊断信息**（当前 URL / document.cookie / 是否有表单），
     * 用来回答「这个 WebView 到底处在什么状态」——
     * 导航后 URL 没变不代表页面正常，教务可能直接返回一段错误 JSON。
     */
    private suspend fun readDom(wv: WebView): String? {
        val d = CompletableDeferred<String>()
        withContext(Dispatchers.Main) {
            wv.evaluateJavascript(
                "(function(){try{return JSON.stringify({" +
                    "url:location.href," +
                    // 只取 Cookie 的**名字**，不带值 —— 会话凭据不进日志
                    "cookieNames:(function(){try{return document.cookie.split(';')" +
                    ".map(function(s){return s.trim().split('=')[0];})" +
                    ".filter(function(x){return x;}).join(',');}catch(e){return 'n/a';}})()," +
                    "cookieLen:document.cookie.length," +
                    // sessionStorage 是**每个 WebView 独立**的。
                    // 如果强智把会话 token 放在这里，就能解释「同一个 App 里
                    // 导入页 WebView 有会话、抓取页没有」这个现象。
                    "ssLen:(function(){try{return sessionStorage.length;}catch(e){return -1;}})()," +
                    "ssKeys:(function(){try{var a=[];for(var i=0;i<sessionStorage.length;i++){" +
                    "a.push(sessionStorage.key(i));}return a.join(',');}catch(e){return 'n/a';}})()," +
                    "lsLen:(function(){try{return localStorage.length;}catch(e){return -1;}})()," +
                    "forms:document.forms.length," +
                    "len:document.documentElement.outerHTML.length" +
                    "});}catch(e){return 'PROBE-ERR '+e;}})()",
            ) { v ->
                AppLog.i(TAG, "探针：${decodeJsString(v)}")
            }
            wv.evaluateJavascript("document.documentElement.outerHTML") { v ->
                d.complete(decodeJsString(v))
            }
        }
        return withTimeoutOrNull(10_000L) { d.await() }
    }

    /** `evaluateJavascript` 回传的是 JSON 字符串字面量，需要还原 */
    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrEmpty() || raw == "null") return ""
        return runCatching {
            org.json.JSONTokener(raw).nextValue() as? String ?: raw
        }.getOrDefault(raw)
    }

    /**
     * 生成「填表并提交」的 JS。
     *
     * 强智的查询页把学年学期放在表单里，提交后才出结果 ——
     * 这里把目标学期写进对应控件再 `submit()`，等价于用户手动选完点「查询」。
     */
    private fun buildSubmitJs(form: Map<String, String>): String {
        val entries = form.entries.joinToString(",") { (k, v) ->
            "'${esc(k)}':'${esc(v)}'"
        }
        return """
            (function(){
              try {
                var data = {$entries};
                var f = document.forms[0];
                if (!f) { return 'noform'; }
                for (var k in data) {
                  var el = f.elements[k];
                  if (!el) { continue; }
                  if (el.length === undefined) {
                    el.value = data[k];
                  } else {
                    for (var i = 0; i < el.length; i++) {
                      if (el[i].value == data[k]) { el[i].checked = true; }
                    }
                  }
                }
                f.submit();
              } catch (e) {}
              return 'ok';
            })()
        """.trimIndent()
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'")

    /** 页面加载完成（由 [buildFetchWebView] 回调） */
    internal fun markReady() {
        if (!ready.isCompleted) ready.complete(Unit)
    }

    /** 一次导航/表单提交后的加载完成（唤醒等待中的 [html]） */
    internal fun signalPageReady() {
        pageReady?.complete(Unit)
    }

    /** 重新等待下一次就绪（例如重新加载后） */
    internal fun resetReady() {
        ready = CompletableDeferred()
    }
}

/**
 * 承载抓取用 WebView 的宿主。
 *
 * **必须放在 Compose 树里**（`MainScaffold` 根部），它整个 App 生命周期都存在，
 * 于是后台抓取随时可用，不受当前在哪个页面的影响。
 *
 * 尺寸给 1×1 像素并禁用交互 —— 用户看不见它，但它是一个真实、可用的浏览器实例。
 */
@Composable
fun QueryFetchHost(
    fetcher: WebViewFetcher,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { ctx -> buildFetchWebView(ctx, fetcher) },
        modifier = modifier,
    )
}

@SuppressLint("SetJavaScriptEnabled")
private fun buildFetchWebView(ctx: Context, fetcher: WebViewFetcher): WebView =
    WebView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(1, 1)
        isFocusable = false
        isClickable = false

        val s = settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.userAgentString = JwglSession.DESKTOP_UA
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.cacheMode = WebSettings.LOAD_NO_CACHE

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        addJavascriptInterface(
            object {
                @JavascriptInterface
                fun onFetch(html: String) = fetcher.deliver(html)
            },
            "AndroidBridge",
        )

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                AppLog.i("WebViewFetcher", "抓取用 WebView 加载完成：$url")
                // 顺带把页面状态探一次：加载成功的 URL 不代表会话有效，
                // 教务可能直接回一段错误内容而不改 URL。
                view?.evaluateJavascript(
                    "(function(){try{return 'cookie=' + document.cookie.length +" +
                        "' names=' + document.cookie.split(';').map(function(s){" +
                        "return s.trim().split('=')[0];}).filter(function(x){return x;}).join(',') +" +
                        "' ss=' + (function(){try{return sessionStorage.length;}catch(e){return -1;}})() +" +
                        "' ssKeys=' + (function(){try{var a=[];for(var i=0;i<sessionStorage.length;i++){" +
                        "a.push(sessionStorage.key(i));}return a.join(',');}catch(e){return 'n/a';}})() +" +
                        "' forms=' + document.forms.length +" +
                        "' len=' + document.documentElement.outerHTML.length;}catch(e){return 'ERR '+e;}})()",
                ) { v ->
                    AppLog.i("WebViewFetcher", "加载后状态：$v")
                }
                fetcher.markReady()
                fetcher.signalPageReady()
            }
        }

        fetcher.attach(this)
        // 先落在教务域内的一个真实页面上，后续相对路径 fetch 才能带上会话。
        //
        // ⚠️ 这里刻意用「学期理论课表」页（导入课表时用户过的同一个页面），
        // 而不是框架页 xsMain.htmlx：实测框架页加载完 onPageFinished 就触发了，
        // 但此时会话上下文未必就绪，随后 fetch 会被判「请先登录系统」。
        // xskb_list.do 是已验证能带会话的普通内容页，落在它上面最稳。
        loadUrl(JwglSession.TIMETABLE_URL)
    }
