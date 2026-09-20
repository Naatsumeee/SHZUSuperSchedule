package com.shzu.superschedule.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.shzu.superschedule.data.AppLog
import com.shzu.superschedule.data.CourseParser
import com.shzu.superschedule.model.Course
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal const val START_URL = "https://jwgl.shzu.edu.cn"

/** 课表查询页（「学期理论课表」）直达地址 */
internal const val TIMETABLE_URL = "https://jwgl.shzu.edu.cn/jsxsd/xskb/xskb_list.do"

/**
 * 移动端 UA：必须显式指定，否则不同 WebView 内核（尤其国产 ROM 内置内核、
 * 部分定制浏览器 SDK）默认 UA 差异极大，教务系统会按桌面版渲染，导致
 * 固定宽度布局溢出屏幕、菜单点不到。
 *
 * 这里统一伪装成 Android 上常见的手机浏览器 UA，命中学校统一认证平台的
 * 移动版页面（/static/mobile/css/index.css + weui），得到 width=device-width 的
 * 响应式布局。
 */
internal const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 13; SM-G9910) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"

/**
 * 桌面端 UA：强智教务的「学期理论课表」是宽表格（约 1000px+），
 * 手机竖屏 360dp 下必然挤压变形，因此导入课表时切到桌面 UA，
 * 配合 WebView 的宽视口 + 初始缩放，才能完整显示 7 列课表。
 */
internal const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

/**
 * 导入页：内嵌 WebView 打开教务系统，用户自行登录并进入「学期理论课表」，
 * 点击底部按钮后抓取当前页面 HTML 解析课程。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ImportPage(
    canCancel: Boolean,
    onCancel: () -> Unit,
    onImported: (List<Course>, String, String, List<String>) -> Unit,
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var capturedHtml by remember { mutableStateOf<String?>(null) }
    var status by remember {
        mutableStateOf("请先登录，然后进入「学期理论课表」页面，再点下方按钮导入")
    }
    var progress by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var chromeVer by remember { mutableStateOf("") }

    // 处理抓到的 HTML
    LaunchedEffect(capturedHtml) {
        val html = capturedHtml ?: return@LaunchedEffect
        capturedHtml = null
        busy = false
        val courses = CourseParser.parse(html)
        if (courses.isEmpty()) {
            status = "没找到课表。请确认已进入「学期理论课表」页面（能看见周次表格），再点导入。"
            return@LaunchedEffect
        }
        val sem = CourseParser.semesterText(html).ifBlank { CourseParser.currentSemester(html) }
        // 「学年学期」下拉框里的全部学期，用于设置页的学期切换
        val semList = CourseParser.semesters(html)
        val ft = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        onImported(courses, sem, ft, semList)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "导入课表",
                navigationIcon = {
                    if (canCancel) {
                        IconButton(onClick = onCancel) {
                            Icon(imageVector = Icons.Filled.Close, contentDescription = "关闭")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            val s = settings
                            s.javaScriptEnabled = true
                            s.domStorageEnabled = true
                            s.databaseEnabled = true
                            // 学校老证书/混合内容：全部放行，否则登录页资源加载失败
                            s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            s.allowFileAccess = true
                            s.allowContentAccess = true

                            // —— 关键 1：显式 UA，命中学校移动版页面 —— //
                            s.userAgentString = MOBILE_UA

                            // —— 关键 2：视口与缩放，解决"显示不正常" —— //
                            s.useWideViewPort = true
                            s.loadWithOverviewMode = true
                            s.setSupportZoom(true)
                            s.builtInZoomControls = true
                            s.displayZoomControls = false          // 隐藏丑陋的默认缩放条，保留双指缩放
                            s.textZoom = 100
                            s.minimumFontSize = 8

                            // —— 关键 3：弹窗 / 多窗口 / 新标签 —— //
                            s.javaScriptCanOpenWindowsAutomatically = true
                            s.setSupportMultipleWindows(true)
                            s.setGeolocationEnabled(false)

                            // 缓存：禁用，避免登录态被旧缓存污染
                            s.cacheMode = WebSettings.LOAD_NO_CACHE
                            s.setNeedInitialFocus(true)

                            chromeVer = "内核 " + (s.userAgentString ?: "").let { ua ->
                                Regex("Chrome/([\\d.]+)").find(ua)?.groupValues?.get(1)
                                    ?: "unknown"
                            }
                            status = "请先登录，然后进入「学期理论课表」页面，再点下方按钮导入  ($chromeVer)"

                            // —— 关键 4：接受第三方 Cookie（authserver ↔ jwgl 跨域 CAS 必须）—— //
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            webViewClient = object : WebViewClient() {
                                override fun onReceivedSslError(
                                    view: WebView?,
                                    handler: SslErrorHandler?,
                                    error: SslError?,
                                ) {
                                    // 学校老证书，直接放行
                                    handler?.proceed()
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean = false // 交给 WebView 自己跟随 CAS 跳转链

                                override fun onPageStarted(view: WebView?, url: String?, fav: Bitmap?) {
                                    progress = 0f
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    progress = 1f
                                    // 把桌面版页面强制收缩适配（老页面无 viewport 时兜底）
                                    view?.evaluateJavascript(
                                        "(function(){" +
                                            "var m=document.querySelector('meta[name=viewport]');" +
                                            "if(!m){m=document.createElement('meta');" +
                                            "m.name='viewport';document.head.appendChild(m);}" +
                                            "m.content='width=device-width,initial-scale=1.0,maximum-scale=3.0,user-scalable=yes';" +
                                            "})()",
                                        null,
                                    )
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress / 100f
                                }

                                /**
                                 * 处理 target=_blank / window.open。
                                 *
                                 * ⚠️ **不能把当前 WebView 直接塞进 transport**：同一个 View
                                 * 会同时挂在主窗口与新窗口上（两个 parent），Android 立刻抛
                                 * `IllegalStateException: The specified child already has a parent`
                                 * 闪退 —— 教务里点「查询」正是用 window.open 打开结果页的。
                                 *
                                 * 改用临时 WebView 承接新窗口，把要加载的 URL 转回主 WebView，
                                 * 等于「新窗口的链接在当前窗口打开」。
                                 */
                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: Message?,
                                ): Boolean {
                                    val host = view ?: return false
                                    val msg = resultMsg ?: return false
                                    val transport =
                                        msg.obj as? WebView.WebViewTransport ?: return false

                                    AppLog.i("WebView", "页面请求打开新窗口，转由当前窗口加载")
                                    val bridge = WebView(host.context)
                                    bridge.webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            v: WebView?,
                                            request: WebResourceRequest?,
                                        ): Boolean {
                                            request?.url?.let { url ->
                                                AppLog.i("WebView", "新窗口 URL 转当前窗口: $url")
                                                host.loadUrl(url.toString())
                                            }
                                            return true
                                        }
                                    }
                                    transport.webView = bridge
                                    msg.sendToTarget()
                                    return true
                                }
                            }

                            addJavascriptInterface(
                                object {
                                    @JavascriptInterface
                                    fun sendHtml(html: String) {
                                        mainHandler.post { capturedHtml = html }
                                    }
                                },
                                "AndroidBridge",
                            )

                            loadUrl(START_URL)
                        }.also { webView = it }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (progress in 0.01f..0.99f) {
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(text = status, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(10.dp))

                    // 一键跳转「学期理论课表」，解决"找不到入口"
                    Button(
                        onClick = {
                            status = "正在打开「学期理论课表」…登录后若仍跳到首页，请手动点击菜单「课表查询」"
                            webView?.loadUrl(TIMETABLE_URL)
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("① 打开「学期理论课表」")
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            busy = true
                            status = "正在读取当前页面…"
                            webView?.evaluateJavascript(
                                "AndroidBridge.sendHtml(document.documentElement.outerHTML)",
                                null,
                            )
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (busy) "处理中…" else "② 导入当前页面课表")
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { webView?.reload() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.width(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("刷新")
                        }
                        Button(
                            onClick = {
                                webView?.settings?.userAgentString = DESKTOP_UA
                                webView?.reload()
                                status = "已切换为电脑版显示（课表表格更完整）"
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("电脑版")
                        }
                        Button(
                            onClick = {
                                webView?.settings?.userAgentString = MOBILE_UA
                                webView?.reload()
                                status = "已切换为手机版显示"
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("手机版")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
