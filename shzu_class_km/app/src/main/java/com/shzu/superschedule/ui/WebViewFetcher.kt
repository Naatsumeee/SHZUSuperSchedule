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
import kotlinx.coroutines.delay
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

        /**
         * 点完菜单后等 iframe 出结果的时间。
         *
         * 刻意给得宽松些：教务本身不快，而且**连续快速请求容易被它当成爬虫**
         * （实测点一次刷新后登录态直接掉）。宁可慢，也别把会话搞丢。
         */
        private const val IFRAME_WAIT_MS = 3_000L
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

        // 🔑 关键认知（从主框架页的菜单结构里读出来的）：
        //
        // 强智的查询页**只能在主框架的子 iframe 里打开**。菜单项全是
        //   kjcdShow('NEW_XSD_XJCJ', …, '/kscj/cjcx_frm', '课程成绩查询')
        // 这样的 JS 调用 —— 也就是说页面是通过「在框架里换子页」加载的。
        // 直接 loadUrl 做**顶层导航**会被判「请先登录系统」：
        // 顶层导航发的是 `Sec-Fetch-Dest: document`，而教务只认 iframe 里的请求。
        //
        // 所以这里完全复刻用户点菜单的动作：
        // 停到主框架页 → 点菜单项 → 等子页加载 → 需要学期的再填表提交 → 读 iframe。
        runCatching { CookieManager.getInstance().flush() }

        val menuKey = path.removePrefix("/jsxsd")
        AppLog.d(TAG, "抓取：点击菜单 $menuKey（${if (form == null) "GET" else "POST"}）")

        // 1) 确保停主框架页
        ensureMainFrame(wv)

        // 2) 点菜单项
        val clicked = evaluate(wv, buildClickMenuJs(menuKey))
        AppLog.i(TAG, "点击菜单 $menuKey -> $clicked")
        if (clicked != "ok") {
            AppLog.w(TAG, "主框架菜单里找不到「$menuKey」，放弃本次抓取")
            return null
        }

        // 3) 等子页加载
        delay(IFRAME_WAIT_MS)

        // 4) 需要按学期查的：在子页里填表并提交（等价于用户选完学期点「查询」）
        if (form != null) {
            val submitted = evaluate(wv, buildSubmitJs(form))
            AppLog.i(TAG, "提交查询表单 -> $submitted")
            delay(IFRAME_WAIT_MS)
        }

        // 5) 把所有 iframe 的内容收集起来交给解析器
        return evaluate(wv, COLLECT_FRAMES_JS)
    }

    /** 确保 WebView 停在主框架页 */
    private suspend fun ensureMainFrame(wv: WebView) {
        val cur = withContext(Dispatchers.Main) { runCatching { wv.url }.getOrNull() }
        if (cur?.contains("xsMain") == true) return

        val r = CompletableDeferred<Unit>()
        pageReady = r
        withContext(Dispatchers.Main) { wv.loadUrl(JwglSession.MAIN_FRAME_URL) }
        if (withTimeoutOrNull(READY_TIMEOUT_MS) { r.await() } == null) {
            AppLog.w(TAG, "加载主框架页超时")
        }
    }

    /** 执行一段 JS 并把字符串结果取回来 */
    private suspend fun evaluate(wv: WebView, js: String): String? {
        val d = CompletableDeferred<String>()
        withContext(Dispatchers.Main) {
            wv.evaluateJavascript(js) { v -> d.complete(decodeJsString(v)) }
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
                var docs = [document];
                var fr = document.querySelectorAll('iframe, frame');
                for (var i = 0; i < fr.length; i++) {
                  try { var d = fr[i].contentDocument; if (d) docs.push(d); } catch (e) {}
                }
                for (var g = 0; g < docs.length; g++) {
                  var fs = docs[g].forms;
                  for (var n = 0; n < fs.length; n++) {
                    var f = fs[n];
                    var hit = false;
                    for (var k in data) { if (f.elements[k]) { hit = true; break; } }
                    if (!hit) continue;
                    for (var key in data) {
                      var el = f.elements[key];
                      if (!el) continue;
                      if (el.length === undefined) {
                        el.value = data[key];
                      } else {
                        for (var m = 0; m < el.length; m++) {
                          if (el[m].value == data[key]) { el[m].checked = true; }
                        }
                      }
                    }
                    f.submit();
                    return 'ok';
                  }
                }
                return 'noform';
              } catch (e) { return 'err:' + e; }
            })()
        """.trimIndent()
    }

    /**
     * 生成「点菜单」的 JS。
     *
     * 在所有 iframe 里找 `onclick` 含目标路径的元素，`.click()` 它 ——
     * 完全等价于用户手动点那个菜单项（教务也就是认这种行为）。
     */
    private fun buildClickMenuJs(menuKey: String): String = """
        (function(){
          try {
            var target = '$menuKey';
            var docs = [document];
            var fr = document.querySelectorAll('iframe, frame');
            for (var i = 0; i < fr.length; i++) {
              try { var d = fr[i].contentDocument; if (d) docs.push(d); } catch (e) {}
            }
            for (var f = 0; f < docs.length; f++) {
              var els = docs[f].querySelectorAll('[onclick]');
              for (var j = 0; j < els.length; j++) {
                var oc = els[j].getAttribute('onclick') || '';
                if (oc.indexOf(target) >= 0) { els[j].click(); return 'ok'; }
              }
            }
            return 'notfound';
          } catch (e) { return 'err:' + e; }
        })()
    """.trimIndent()

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'")

    /**
     * 临时诊断：把主框架页（含左侧菜单 iframe）里所有菜单项
     * 的「文本 / href / onclick」吐到日志，用来分析查询页的真实进入方式。
     */
    suspend fun dumpMenuToLog() {
        val wv = webView ?: return
        val ready2 = CompletableDeferred<Unit>()
        pageReady = ready2
        withContext(Dispatchers.Main) {
            wv.loadUrl(JwglSession.MAIN_FRAME_URL, mapOf("Referer" to JwglSession.BASE))
        }
        if (withTimeoutOrNull(READY_TIMEOUT_MS) { ready2.await() } == null) {
            AppLog.w(TAG, "dumpMenu：加载主框架页超时")
            return
        }

        val d = CompletableDeferred<String>()
        withContext(Dispatchers.Main) {
            wv.evaluateJavascript(MENU_DUMP_JS) { v -> d.complete(decodeJsString(v)) }
        }
        val raw = withTimeoutOrNull(10_000L) { d.await() } ?: return
        AppLog.i(TAG, "=== 主框架菜单结构 ===")
        raw.split("\n").forEach { line ->
            if (line.isNotBlank()) AppLog.i(TAG, "  $line")
        }
    }

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

/**
 * 收集「所有 iframe 子页」的 HTML。
 *
 * 查询页是在主框架的 iframe 里打开的，只读顶层 `documentElement` 拿不到内容。
 * 把每个有实质内容的 iframe 都取出来拼在一起，交给解析器按「数据行最多的表」去找。
 */
private const val COLLECT_FRAMES_JS = """
(function(){
  var out = [];
  var fr = document.querySelectorAll('iframe, frame');
  for (var i = 0; i < fr.length; i++) {
    try {
      var d = fr[i].contentDocument;
      if (!d || !d.documentElement) continue;
      var html = d.documentElement.outerHTML;
      if (html.length < 200) continue;
      out.push('<!--FRAME' + i + ' src=' + (fr[i].src || '') + '-->');
      out.push(html);
    } catch (e) {}
  }
  return out.join('\n');
})()
"""

/**
 * 提取主框架页（含各 iframe）里所有菜单项的文本 / href / onclick。
 * 每行一个菜单项，用 `\n` 分隔，方便日志逐行打印。
 */
private const val MENU_DUMP_JS = """
(function(){
  var out = [];
  var docs = [document];
  var fr = document.querySelectorAll('iframe, frame');
  for (var i = 0; i < fr.length; i++) {
    try { var d = fr[i].contentDocument; if (d) docs.push(d); } catch (e) {}
  }
  // 菜单点击走的是 JS 函数 kjcdShow，把它的源码也吐出来看看到底干了什么
  try {
    if (typeof kjcdShow === 'function') out.push('=== kjcdShow 源码 ===\n' + kjcdShow.toString());
  } catch (e) { out.push('kjcdShow 取不到: ' + e); }
  try {
    var cv = document.querySelectorAll('iframe');
    for (var q = 0; q < cv.length; q++) {
      try {
        var w = cv[q].contentWindow;
        if (w && typeof w.kjcdShow === 'function') {
          out.push('=== 子框架 ' + q + ' 的 kjcdShow ===\n' + w.kjcdShow.toString());
          break;
        }
      } catch (e) {}
    }
  } catch (e) {}
  for (var f = 0; f < docs.length; f++) {
    var links = docs[f].querySelectorAll('a[href], [onclick]');
    for (var j = 0; j < links.length; j++) {
      var a = links[j];
      var txt = (a.textContent || a.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 24);
      var href = a.getAttribute('href') || '';
      var onclick = (a.getAttribute('onclick') || '').slice(0, 120);
      var id = a.getAttribute('id') || '';
      if (!txt && !href && !onclick && !id) continue;
      out.push('[iframe' + f + '] txt=' + txt + ' | href=' + href + ' | onclick=' + onclick + ' | id=' + id);
    }
  }
  return out.join('\n');
})()
"""

