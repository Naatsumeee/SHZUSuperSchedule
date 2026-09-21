package com.shzu.superschedule.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
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

        /**
         * 主框架加载完后，等菜单 iframe 渲染出来的时间。
         *
         * `onPageFinished` 只代表顶层文档就绪，左侧菜单和「常用功能」
         * 都是随后的 iframe 异步加载 —— 不等就去找菜单项必然 notfound。
         */
        private const val MENU_RENDER_WAIT_MS = 2_500L
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

    override suspend fun html(
        path: String,
        semester: String?,
        menuCall: List<String>,
    ): String? {
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
        AppLog.d(TAG, "抓取：打开 $menuKey（学期=${semester ?: "-"}）")

        // 1) 确保停主框架页，并且**菜单 iframe 已经渲染出来**。
        //    主框架的 onPageFinished 只代表顶层文档加载完，
        //    左侧菜单 / 常用功能都是随后的 iframe 异步加载 ——
        //    不等一下就去找菜单项，必然是 notfound（实测如此）。
        ensureMainFrame(wv)
        delay(MENU_RENDER_WAIT_MS)

        // 2) 打开查询页：优先直接调用教务自己的 kjcdShow(...)，
        //    它正是菜单项被点击时执行的函数，最贴近「用户手动点菜单」；
        //    找不到这个函数再回退到「找菜单项并 click」。
        var opened: String? = null
        if (menuCall.size >= 5) {
            opened = evaluate(wv, buildKjcdShowJs(menuCall))
            AppLog.i(TAG, "调用 kjcdShow(${menuCall[3]}) -> $opened")
        }
        if (opened != "ok") {
            repeat(2) { attempt ->
                opened = evaluate(wv, buildClickMenuJs(menuKey))
                AppLog.i(TAG, "点击菜单 $menuKey -> $opened（第 ${attempt + 1} 次）")
                if (opened == "ok") return@repeat
                if (attempt == 0) {
                    AppLog.w(TAG, "没找到菜单项，重载主框架页后重试")
                    reloadMainFrame(wv)
                    delay(MENU_RENDER_WAIT_MS)
                }
            }
        }
        if (opened != "ok") {
            AppLog.w(TAG, "始终打不开「$menuKey」，放弃本次抓取")
            return null
        }

        // 3) 等子页加载
        delay(IFRAME_WAIT_MS)

        // 目标 iframe 的识别特征：路径最后一段。
        val frameKey = path.substringAfterLast('/')

        // 4) 需要按学期查的：**在页面内部**选学期并点「查询」。
        //
        // ⚠️ 绝对不能拿「所有 iframe 拼起来的 HTML」去 Jsoup 里找表单：
        // 主框架常驻着「修改个人信息」等 iframe，`forms.first{}` 抓到的是它们的表，
        // 提交了等于没提交（实测 GET 与 POST 返回的长度一字不差）。
        if (semester != null) {
            val detail = evaluate(wv, buildQueryInFrameJs(frameKey, semester))
            AppLog.i(TAG, "选学期并提交查询 -> $detail")
            delay(IFRAME_WAIT_MS)
        }

        // 5) 递归收集**所有层级**的 frame（结果往往在嵌套的子 frame 里）。
        val html = evaluate(wv, buildCollectFramesJs(frameKey))
        if (DUMP_TARGET_FRAME) dumpAllFrames(wv, frameKey)
        return html
    }

    /**
     * 临时诊断开关：把各 frame 的 HTML 分片写进日志。
     *
     * 平时**保持 false**。只在「页面结构认不出来」时才临时打开 ——
     * 它能直接告诉我们要填哪个表单、点哪个按钮，比反复猜想快得多。
     * （v158/v159 定位「等级考试表表头是多行的」就靠它。）
     */
    private val DUMP_TARGET_FRAME = false

    /**
     * 把各 frame 的 HTML 分片打进日志（logcat 单条上限约 4K，故按 900 字切片）。
     *
     * ⚠️ HTML 分片**只走 logcat，不走 [AppLog]**。
     * 一次 dump 近 200 行，而 [AppLog] 的内存缓冲只有 800 条、整轮抓取要 dump 十几次——
     * 挂到 AppLog 上会把真正的抓取结论（成功几项、哪项没数据）整个挤出缓冲，
     * 用户到「设置 → 关于 → 作者」7 下打开日志页，只会看到满屏 HTML。
     * 用 `Log.i` 就只留在 logcat 里，供开发排查，不干扰应用内日志。
     */
    private suspend fun dumpAllFrames(wv: WebView, frameKey: String) {
        val names = evaluate(wv, buildListFramesJs()) ?: return
        AppLog.i(TAG, "=== frame 清单 === $names")
        // 只 dump 与目标相关的那几个：按 src 含 frameKey 的最多 3 个
        val html = evaluate(wv, buildFrameHtmlJs(frameKey)) ?: return
        Log.i(TAG, "=== iframe[$frameKey] HTML（${html.length} 字）===")
        html.chunked(900).forEachIndexed { i, part -> Log.i(TAG, "  [$i] $part") }
    }

    /**
     * **递归**枚举所有 window（含嵌套 iframe/subframe）。
     *
     * 🔴 这是之前最大的盲点：`document.querySelectorAll('iframe, frame')`
     * **只看顶层**，而强智的查询结果全在**嵌套**的子 frame 里 ——
     * 课程成绩在 `cjcx_frm` 内层的 `cjcx_list_frm`，
     * 考试安排在 `xsksap_query` 内层的 `fcenter`。
     * 顶层那 4 个 frame 里当然一条数据都没有，于是怎么改都「未查询到数据」。
     *
     * 深度限 4 层，避免异常结构把栈打爆。
     */
    private val FRAME_UTILS_JS = """
        function __walk(win, depth, out, up) {
          if (depth > 4 || !win) return out;
          var fr;
          try { fr = win.frames; } catch (e) { return out; }
          if (!fr) return out;
          for (var i = 0; i < fr.length; i++) {
            try {
              var w = fr[i];
              var d = w.document;
              if (!d || !d.documentElement) continue;
              var href = '';
              try { href = w.location.href || ''; } catch (e) {}
              var nm = '';
              try { nm = w.name || ''; } catch (e) {}
              var me = out.length;
              out.push({ w: w, d: d, name: nm, href: href, depth: depth, up: up });
              __walk(w, depth + 1, out, me);
            } catch (e) {}
          }
          return out;
        }
        function __allFrames() { return __walk(window, 0, [], -1); }
        // 自身或任一祖先的 URL 命中 key → 属于目标页的子树
        function __underKey(list, i, key) {
          if (!key) return false;
          var n = i;
          while (n >= 0) {
            if ((list[n].href || '').indexOf(key) >= 0) return true;
            n = list[n].up;
          }
          return false;
        }
    """.trimIndent()

    /** 诊断用：列出所有 frame 的 `name@href` */
    private fun buildListFramesJs(): String = """
        (function(){
          try {
            $FRAME_UTILS_JS
            return __allFrames().map(function(x, i){
              return i + ':' + (x.name || '-') + '@' + (x.href || '').slice(0, 70)
                + '[' + x.d.documentElement.outerHTML.length + ']';
            }).join(' | ');
          } catch (e) { return 'ERR ' + e; }
        })()
    """.trimIndent()

    /**
     * 收集**所有层级** frame 的内容。
     *
     * 优先只交与 [frameKey] 相关的（目标页自己 + 它内部嵌套的子 frame），
     * 匹配不到才退回全收 —— 由解析器的表头关键词兜底。
     *
     * 每条 `<!--FRAME…-->` 注释在 Kotlin 侧被提取出来写日志
     * （**JS 里不能调 AppLog** —— 那是 Kotlin 对象，JS 里不存在，
     * 会抛 ReferenceError 把整个收集函数搞挂，实测踩过）。
     */
    private fun buildCollectFramesJs(frameKey: String): String = """
        (function(){
          try {
            $FRAME_UTILS_JS
            var key = '${esc(frameKey)}';
            var all = __allFrames();
            var picked = [];
            for (var i = 0; i < all.length; i++) {
              if (__underKey(all, i, key)) picked.push(all[i]);
            }
            var use = picked.length > 0 ? picked : all;
            var out = [];
            for (var k = 0; k < use.length; k++) {
              var f = use[k];
              var html;
              try { html = f.d.documentElement.outerHTML; } catch (e) { continue; }
              if (!html || html.length < 200) continue;
              var tb = f.d.querySelectorAll('table');
              var hdr = '';
              if (tb.length > 0) {
                var rows = tb[0].querySelectorAll('tr');
                if (rows.length > 0) {
                  hdr = (rows[0].textContent || '').replace(/\s+/g, ' ').trim().slice(0, 70);
                }
              }
              out.push('<!--FRAME d=' + f.depth + ' name=' + (f.name || '-')
                + ' | src=' + (f.href || '').slice(0, 90)
                + ' | len=' + html.length
                + ' | tables=' + tb.length
                + ' | hdr=' + hdr + '-->');
              out.push(html);
            }
            return out.join('\n');
          } catch (e) { return 'ERR ' + e; }
        })()
    """.trimIndent()

    /** 取目标页整棵子树的 HTML（诊断用，总量截到 15000 字） */
    private fun buildFrameHtmlJs(frameKey: String): String = """
        (function(){
          try {
            $FRAME_UTILS_JS
            var key = '${esc(frameKey)}';
            var all = __allFrames();
            var out = '';
            for (var i = 0; i < all.length && out.length < 15000; i++) {
              if (!__underKey(all, i, key)) continue;
              var h = '';
              try { h = all[i].d.documentElement.outerHTML; } catch (e) { continue; }
              out += '\n<!--SUBTREE d=' + all[i].depth + ' name=' + (all[i].name || '-')
                   + ' src=' + (all[i].href || '').slice(0, 80) + '-->' + '\n' + h;
            }
            return out.length > 0 ? out.slice(0, 15000) : ('NO-FRAME:' + key);
          } catch (e) { return 'ERR ' + e; }
        })()
    """.trimIndent()

    /** 确保 WebView 停在主框架页 */
    private suspend fun ensureMainFrame(wv: WebView) {
        val cur = withContext(Dispatchers.Main) { runCatching { wv.url }.getOrNull() }
        if (cur?.contains("xsMain") == true) return
        reloadMainFrame(wv)
    }

    /** 重新加载主框架页并等它加载完 */
    private suspend fun reloadMainFrame(wv: WebView) {
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
     * 生成「**在页面内部**选学期并触发查询」的 JS。
     *
     * 这是整条链路最关键的一步。之前的写法是：
     * Kotlin 侧把所有 iframe 的 HTML 拼起来 → Jsoup 找第一个带 input 的 form
     * → 把字段表交给 JS 去填。问题是主框架里常驻着「修改个人信息」等 iframe，
     * 抓到的往往是它们的表单 —— **提交了等于没提交**（实测 GET 与 POST 返回的
     * 页面长度一字不差）。
     *
     * 现在按实测到的真实结构来做：
     *
     * 1. **改写 URL 参数直取**：强智的成绩列表页 `cjcx_list` 把学期放在
     *    query string 里（实测 `?kksj=2026-2027-1&zylx=0`）。凡是有子 frame 的
     *    URL 里带 `kksj=`，直接换成目标学期并 reload —— 这是最短、最可靠的一条路。
     * 2. **找不到就模拟用户操作**：在任意层级找 name/id 含 `xnxq` 的学期控件，
     *    写入 [semester]（**选项里没有该学期就绝不提交**，免得查成别的学期还给用户看），
     *    再点「查询」按钮。
     * 3. 点按钮时做**拼接匹配** `value+text+onclick+id`：教务的查询按钮常写成
     *    `<button type="button" onclick="queryKsap()">查 询</button>` ——
     *    按钮文字里的空格会让「查询」匹配失败，而 `onclick` 才是可靠特征。
     *    （早先写成 `value || textContent || onclick` 短路取值，恰好被文字挡住，
     *    于是永远走不到 `onclick`，白白退化成 `form.submit()`。）
     *
     * 返回**诊断串**（frame 清单 / 控件 / 表单 / 点到了什么），直接进日志。
     */
    private fun buildQueryInFrameJs(frameKey: String, semester: String): String = """
        (function(){
          try {
            $FRAME_UTILS_JS
            var key = '${esc(frameKey)}';
            var sem = '${esc(semester)}';
            var all = __allFrames();
            var info = 'frames=' + all.length;

            // ---- 1) 直取：子 frame 的 URL 里带学期参数（kksj=…）就改写它
            var rewritten = 0;
            for (var i = 0; i < all.length; i++) {
              var h = all[i].href || '';
              if (!/kksj=/i.test(h)) continue;
              var nh = h.replace(/([?&]kksj=)[^&]*/i, '${'$'}1' + encodeURIComponent(sem));
              if (nh === h) continue;
              try { all[i].w.location.replace(nh); rewritten++; } catch (e) {}
            }
            if (rewritten > 0) {
              return info + ' | 直取 kksj 改写=' + rewritten + ' sem=' + sem;
            }

            // ---- 2) 找学期控件（任意层级）
            var host = null, ctl = null;
            for (var p = 0; p < all.length && !ctl; p++) {
              var cands = all[p].d.querySelectorAll('select, input');
              for (var c = 0; c < cands.length; c++) {
                var nm = cands[c].name || cands[c].id || '';
                if (/xnxq/i.test(nm)) { host = all[p]; ctl = cands[c]; break; }
              }
            }
            info += ' | ctl=' + (ctl ? (ctl.tagName + '#' + (ctl.name || ctl.id)) : 'none');
            if (!ctl) return info + ' | noctl';

            var form = ctl.form || null;
            info += ' | form=' + (form ? (form.name || form.id || '?') : 'none');
            if (form) {
              info += ' action=' + (form.getAttribute('action') || '')
                    + ' target=' + (form.getAttribute('target') || '');
            }

            if (ctl.tagName === 'SELECT') {
              var hit = -1;
              for (var o = 0; o < ctl.options.length; o++) {
                if (ctl.options[o].value === sem) { hit = o; break; }
              }
              info += ' | setSem=' + (hit >= 0 ? 'ok' : 'NOOPT/' + ctl.options.length);
              if (hit < 0) return info;   // 学期不在候选里 → 不提交
              ctl.selectedIndex = hit;
            } else {
              ctl.value = sem;
              info += ' | setSem=ok';
            }

            // ---- 3) 点「查询」按钮（value + 文字 + onclick + id 一起看）
            var scope = host ? host.d : document;
            var bs = scope.querySelectorAll(
              'input[type=submit], input[type=button], button, a[onclick]');
            for (var b = 0; b < bs.length; b++) {
              var el = bs[b];
              var t = (el.value || '') + ' ' + (el.textContent || '') + ' '
                    + (el.getAttribute('onclick') || '') + ' ' + (el.id || '');
              if (/查询|搜索|检索|search|query|_cx/i.test(t)) {
                el.click();
                return info + ' | click=「'
                  + (el.value || el.textContent || el.id || '').replace(/\s+/g, '').slice(0, 12)
                  + '」';
              }
            }
            if (form) { form.submit(); return info + ' | submit=form'; }
            return info + ' | nobody';
          } catch (e) { return 'err:' + e; }
        })()
    """.trimIndent()

    /**
     * 生成「调用教务自己的 kjcdShow」的 JS。
     *
     * 该函数可能挂在顶层 window，也可能挂在某个子框架的 window 上，所以都试一遍。
     */
    private fun buildKjcdShowJs(args: List<String>): String {
        val quoted = args.joinToString(",") { "'${esc(it)}'" }
        return """
            (function(){
              try {
                var a = [$quoted];
                var wins = [window];
                var fr = document.querySelectorAll('iframe, frame');
                for (var i = 0; i < fr.length; i++) {
                  try { if (fr[i].contentWindow) wins.push(fr[i].contentWindow); } catch (e) {}
                }
                for (var w = 0; w < wins.length; w++) {
                  try {
                    if (typeof wins[w].kjcdShow === 'function') {
                      wins[w].kjcdShow(a[0], a[1], a[2], a[3], a[4]);
                      return 'ok';
                    }
                  } catch (e) {}
                }
                return 'nofn';
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
     * 诊断：把主框架页（含左侧菜单 iframe）里所有菜单项
     * 的「文本 / href / onclick」吐到日志，用来分析查询页的真实进入方式。
     */
    override suspend fun dumpMenu() = dumpMenuToLog()

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
 * 提取主框架页（含各 iframe）里所有菜单项的文本 / href / onclick。
 * 每行一个菜单项，用 `\n` 分隔，方便日志逐行打印。
 */
private const val MENU_DUMP_JS = """
(function(){
  var MENU_IDS = [
    'NEW_XSD_XJCJ_WDCJ_DJKSCJ',
    'NEW_XSD_XJCJ_WDCJ_KCCJCX',
    'NEW_XSD_KSBM_WDKS_KSAPCX',
    'NEW_XSD_XJCJ_WDCJ'
  ];
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
  // 菜单的真实 URL 藏在 JS 里（三级菜单项只有 id，没有 onclick）。
  // 把所有含 kjcdShow / 目标 id 的脚本文本挖出来，直接看它调了什么。
  try {
    for (var sd = 0; sd < docs.length; sd++) {
      var ss = docs[sd].querySelectorAll('script');
      for (var si2 = 0; si2 < ss.length; si2++) {
        var txt = ss[si2].textContent || '';
        for (var mi = 0; mi < MENU_IDS.length; mi++) {
          var at = txt.indexOf(MENU_IDS[mi]);
          if (at >= 0) {
            out.push('=== SCRIPT 命中 ' + MENU_IDS[mi] + ' ===');
            out.push(txt.slice(Math.max(0, at - 260), at + 260).replace(/\s+/g, ' '));
          }
        }
      }
    }
  } catch (e) { out.push('脚本扫描失败: ' + e); }
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

