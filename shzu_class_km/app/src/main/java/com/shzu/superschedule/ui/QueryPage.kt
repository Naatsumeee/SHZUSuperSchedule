package com.shzu.superschedule.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.shzu.superschedule.data.JwglQueryParser
import com.shzu.superschedule.model.QueryKind
import com.shzu.superschedule.model.QueryTable
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// 查询页（底部导航第 3 项，排在「课表」之后、「设置」之前）
//
// 提供三条教务查询入口：考试安排 / 课程成绩 / 等级考试成绩。
// 数据来源与导入课表一致 —— 内嵌 WebView 打开教务系统，用户自行登录并
// 进入目标查询页，再点「抓取当前页面」把 HTML 交给 [JwglQueryParser] 解析。
//
// 之所以不直接在后端 POST 拿数据（像「刷新可读学期」那样）：
//   1. 三类查询的接口名/参数各校不同，写死容易失效；
//   2. 考试安排查询需要用户自选学年学期，表单参数无法预先确定。
// 走 WebView 则无论教务怎么改，只要能看见表格就能抓。
// ---------------------------------------------------------------------------

/** 查询页内部子页面 */
internal enum class QuerySubPage { MAIN, DETAIL }

/**
 * 查询页的跨页面存活状态。
 *
 * 与 [SettingsUiState] 同理：由 `MainScaffold` 持有（它整个 App 生命周期都在组合树里），
 * 于是切到「课表」看一眼再回来时，二级页与已抓到的查询结果都还在，
 * 不会白跑一趟重新登录抓取。
 */
internal class QueryUiState {
    val stack = PageStack(QuerySubPage.MAIN)
    val mainScroll = ScrollState(0)
    val detailScroll = ScrollState(0)

    /** kind.key → 最近一次解析结果（内存缓存） */
    var tables by mutableStateOf<Map<String, QueryTable>>(emptyMap())
        private set

    /** 当前正在查看的查询类型 key */
    var activeKind by mutableStateOf<String?>(null)

    fun put(table: QueryTable) {
        tables = tables + (table.kind to table)
    }
}

@Composable
internal fun rememberQueryUiState(): QueryUiState = remember { QueryUiState() }

@Composable
internal fun QueryPage(
    ui: QueryUiState,
    bottomInset: Dp = 0.dp,
) {
    PageHost(
        stack = ui.stack,
        modifier = Modifier.fillMaxWidth(),
    ) { page ->
        when (page) {
            QuerySubPage.MAIN -> QueryMain(
                ui = ui,
                bottomInset = bottomInset,
                onOpen = { kind ->
                    ui.activeKind = kind.key
                    ui.stack.push(QuerySubPage.DETAIL)
                },
            )

            QuerySubPage.DETAIL -> {
                val kind = ui.activeKind?.let { QueryKind.of(it) }
                if (kind == null) {
                    // 状态异常（例如进程被回收后恢复），直接退回主页
                    LaunchedEffect(Unit) { ui.stack.pop() }
                } else {
                    QueryDetail(
                        ui = ui,
                        kind = kind,
                        bottomInset = bottomInset,
                        onBack = { ui.stack.pop() },
                    )
                }
            }
        }
    }
}

// ---------------- 一级页：查询主页 ----------------

@Composable
private fun QueryMain(
    ui: QueryUiState,
    bottomInset: Dp,
    onOpen: (QueryKind) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(ui.mainScroll)
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp + bottomInset),
    ) {
        PageHeader(title = "查询", subtitle = "从教务系统读取考试安排与成绩")

        SectionTitle("教务查询")
        Card {
            QueryKind.entries.forEach { kind ->
                val cached = ui.tables[kind.key]
                BasicComponent(
                    title = kind.label,
                    summary = cached
                        ?.let { "上次获取 ${it.count} 条 · ${it.fetchedAt}" }
                        ?: kind.desc,
                    onClick = { onOpen(kind) },
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        SectionTitle("说明")
        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "三条数据都直接来自教务系统，进入后需要先在弹出的网页里登录一次" +
                        "（与导入课表共用登录状态，已登录则免登录）。",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "若没有直接跳到目标页面，可在网页里按菜单手动进入，再点「抓取当前页面」：",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                QueryKind.entries.forEach { k ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "· ${k.label}：${k.menuPath}",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

// ---------------- 二级页：具体查询 ----------------

@Composable
private fun QueryDetail(
    ui: QueryUiState,
    kind: QueryKind,
    bottomInset: Dp,
    onBack: () -> Unit,
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var capturedHtml by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember {
        mutableStateOf("正在打开教务系统…若停在其他页面，请按菜单手动进入「${kind.menuPath}」")
    }
    /** 抓取成功后收起网页、把结果铺满；也可以再切回网页重新定位 */
    var showWeb by remember { mutableStateOf(true) }

    val table = ui.tables[kind.key]

    // 抓到 HTML 后解析
    LaunchedEffect(capturedHtml) {
        val html = capturedHtml ?: return@LaunchedEffect
        capturedHtml = null
        busy = false
        val ft = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val parsed = JwglQueryParser.parse(html, kind.key, ft)
        if (parsed == null) {
            status = "没找到结果表格。请确认已登录，且当前页面已经是「${kind.label}」的结果页" +
                "（能看见数据表格），再点「抓取当前页面」。"
        } else {
            ui.put(parsed)
            status = "已获取 ${parsed.count} 条记录"
            showWeb = false
            ui.detailScroll.scrollTo(0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomInset),
    ) {
        SubPageTopBar(title = kind.label, onBack = onBack)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            // ---- 底层：网页操作区（始终保留，避免销毁 WebView 丢掉页面状态）----
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            buildQueryWebView(
                                ctx = ctx,
                                mainHandler = mainHandler,
                                onHtml = { capturedHtml = it },
                                onProgress = { progress = it },
                            ).also { webView = it }
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
                        Text(text = status, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))

                        Button(
                            onClick = {
                                status = "正在打开「${kind.label}」…若停在其他页面，请按菜单手动进入"
                                webView?.loadUrl(kind.urlOf(START_URL))
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("① 打开「${kind.label}」")
                        }
                        Spacer(Modifier.height(8.dp))

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
                            Text(if (busy) "处理中…" else "② 抓取当前页面")
                        }
                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { webView?.reload() },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.width(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("刷新")
                            }
                            if (table != null) {
                                Button(
                                    onClick = { showWeb = false },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("看上次结果")
                                }
                            }
                        }
                    }
                }
            }

            // ---- 顶层：结果覆盖层 ----
            if (!showWeb && table != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MiuixTheme.colorScheme.surface),
                ) {
                    QueryResultView(
                        title = kind.label,
                        table = table,
                        scroll = ui.detailScroll,
                        onBack = onBack,
                        onBackToWeb = { showWeb = true },
                    )
                }
            }
        }
    }
}

// ---------------- 结果展示 ----------------

@Composable
private fun QueryResultView(
    title: String,
    table: QueryTable,
    scroll: ScrollState,
    onBack: () -> Unit,
    onBackToWeb: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SubPageTopBar(title = title, onBack = onBack)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = "共 ${table.count} 条 · ${table.fetchedAt}",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onBackToWeb) {
                Text("回网页")
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 12.dp),
        ) {
            table.rows.forEach { row -> ResultCard(table, row) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 单条记录的卡片：首字段作标题，其余按「字段名 → 值」排列 */
@Composable
private fun ResultCard(table: QueryTable, row: List<String>) {
    val items = table.labeled(row).filter { it.second.isNotBlank() }
    if (items.isEmpty()) return
    val head = items.first()

    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = head.second,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            items.drop(1).forEach { (k, v) ->
                Spacer(Modifier.height(5.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = k,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.width(80.dp),
                    )
                    Text(
                        text = v,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ---------------- WebView 构建（与 ImportPage 同套配置） ----------------

/**
 * 查询用 WebView。
 *
 * 与导入课表的 WebView 配置一致，唯一差别是**固定使用桌面 UA** ——
 * 考试安排/成绩都是宽表格，桌面版才能完整显示；
 * 导入页那种「手机版/电脑版」切换在这里没必要。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun buildQueryWebView(
    ctx: Context,
    mainHandler: Handler,
    onHtml: (String) -> Unit,
    onProgress: (Float) -> Unit,
): WebView = WebView(ctx).apply {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
    val s = settings
    s.javaScriptEnabled = true
    s.domStorageEnabled = true
    s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    s.allowFileAccess = true
    s.allowContentAccess = true

    s.userAgentString = DESKTOP_UA

    s.useWideViewPort = true
    s.loadWithOverviewMode = true
    s.setSupportZoom(true)
    s.builtInZoomControls = true
    s.displayZoomControls = false
    s.textZoom = 100
    s.minimumFontSize = 8

    s.javaScriptCanOpenWindowsAutomatically = true
    s.setSupportMultipleWindows(true)
    s.cacheMode = WebSettings.LOAD_NO_CACHE

    // 跨域 CAS（authserver ↔ jwgl）必须接受第三方 Cookie，否则登录态串不起来
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

    // 返回键：WebView 是 View 层级，会抢在 Activity 的返回分发之前拿到 BACK。
    // 不处理的话，网页一旦有历史记录，系统返回就被它吃掉用于「网页后退」，
    // 页面栈永远退不出去（而且用户看不出任何变化，像卡死一样）。
    // 这里按浏览器惯例：**网页能后退就先后退，退无可退再交回 Compose 的页面栈**。
    setOnKeyListener { _, keyCode, event ->
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            if (canGoBack()) {
                goBack()
            } else {
                (ctx as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
            }
            true
        } else {
            false
        }
    }

    webViewClient = object : WebViewClient() {
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?,
        ) {
            handler?.proceed() // 学校老证书，放行
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean = false // 交给 WebView 自己跟随 CAS 跳转链

        override fun onPageFinished(view: WebView?, url: String?) {
            // 老页面无 viewport 时兜底，避免宽表格溢出屏幕
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
            onProgress(newProgress / 100f)
        }

        /** 处理 target=_blank / window.open，复用当前 WebView */
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?,
        ): Boolean {
            val msg = resultMsg ?: return false
            val transport = msg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = view
            msg.sendToTarget()
            return true
        }
    }

    addJavascriptInterface(
        object {
            @JavascriptInterface
            fun sendHtml(html: String) {
                mainHandler.post { onHtml(html) }
            }
        },
        "AndroidBridge",
    )

    loadUrl(START_URL)
}
