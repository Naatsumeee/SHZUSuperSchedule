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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.shzu.superschedule.data.AppLog
import com.shzu.superschedule.data.JwglQueryParser
import com.shzu.superschedule.data.QueryStore
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
// 数据来源：**导入课表时后台自动抓取**（JwglQueryFetcher，全程无感），
// 结果落在 QueryStore；本页只负责「展示 + 手动刷新」。
//
// 同时保留一条**手动兜底通道**：若后台抓取因教务改版失败，
// 用户可以进二级页用内嵌 WebView 自己点到查询页再抓一次。
// ---------------------------------------------------------------------------

/** 查询页内部子页面 */
internal enum class QuerySubPage { MAIN, DETAIL }

/**
 * 查询页的跨页面存活状态。
 *
 * 由 `MainScaffold` 持有（它整个 App 生命周期都在组合树里），
 * 于是切到「课表」看一眼再回来时，二级页、选中的学期、滚动位置都还在。
 */
internal class QueryUiState {
    val stack = PageStack(QuerySubPage.MAIN)
    val mainScroll = ScrollState(0)
    val detailScroll = ScrollState(0)

    /** 手动抓取的结果，key = "kind|semester"（优先级高于持久化存档） */
    var manualTables by mutableStateOf<Map<String, QueryTable>>(emptyMap())
        private set

    /** 二级页当前选中的学期（按查询类型分开记） */
    var pickedSemester by mutableStateOf<Map<String, String>>(emptyMap())

    /** 当前正在查看的查询类型 key */
    var activeKind by mutableStateOf<String?>(null)

    fun putManual(table: QueryTable) {
        manualTables = manualTables + (keyOf(table.kind, table.semester) to table)
    }

    fun pickSemester(kind: String, semester: String) {
        pickedSemester = pickedSemester + (kind to semester)
    }

    companion object {
        fun keyOf(kind: String, semester: String) = "$kind|$semester"
    }
}

@Composable
internal fun rememberQueryUiState(): QueryUiState = remember { QueryUiState() }

@Composable
internal fun QueryPage(
    ui: QueryUiState,
    bottomInset: Dp = 0.dp,
    /** 已持久化的查询数据（来自 QueryStore） */
    entries: List<QueryTable> = emptyList(),
    updatedAt: String = "",
    /** 后台抓取进行中 */
    fetching: Boolean = false,
    /** 触发后台刷新 */
    onRefresh: () -> Unit = {},
) {
    PageHost(
        stack = ui.stack,
        modifier = Modifier.fillMaxWidth(),
    ) { page ->
        when (page) {
            QuerySubPage.MAIN -> QueryMain(
                ui = ui,
                bottomInset = bottomInset,
                entries = entries,
                updatedAt = updatedAt,
                fetching = fetching,
                onRefresh = onRefresh,
                onOpen = { kind ->
                    ui.activeKind = kind.key
                    ui.stack.push(QuerySubPage.DETAIL)
                },
            )

            QuerySubPage.DETAIL -> {
                val kind = ui.activeKind?.let { QueryKind.of(it) }
                if (kind == null) {
                    LaunchedEffect(Unit) { ui.stack.pop() }
                } else {
                    QueryDetail(
                        ui = ui,
                        kind = kind,
                        bottomInset = bottomInset,
                        entries = entries,
                        fetching = fetching,
                        onRefresh = onRefresh,
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
    entries: List<QueryTable>,
    updatedAt: String,
    fetching: Boolean,
    onRefresh: () -> Unit,
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
                val group = entries.filter { it.kind == kind.key }
                val total = group.sumOf { it.count }
                BasicComponent(
                    title = kind.label,
                    summary = when {
                        group.isEmpty() -> "${kind.desc} · 尚未获取"
                        total == 0 -> "${kind.desc} · 未查询到数据"
                        kind.perSemester -> "${group.size} 个学期 · 共 $total 条"
                        else -> "共 $total 条"
                    },
                    onClick = { onOpen(kind) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (updatedAt.isBlank()) "尚未抓取过查询数据" else "更新于 $updatedAt",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onRefresh, enabled = !fetching) {
                Text(if (fetching) "抓取中…" else "刷新")
            }
        }

        Spacer(Modifier.height(4.dp))
        SectionTitle("说明")
        Card {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "导入课表时会自动在后台抓取考试安排、课程成绩与等级考试成绩，" +
                        "全部学期一次抓完，不需要手动操作。",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "想拿最新数据点上方「刷新」即可（同样在后台完成）。" +
                        "若某个入口一直取不到数据，可以进去用「手动查询」自己打开教务网页抓一次。",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "教务对应菜单：",
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

// ---------------- 二级页：某类查询 ----------------

private enum class DetailMode { DATA, WEB }

@Composable
private fun QueryDetail(
    ui: QueryUiState,
    kind: QueryKind,
    bottomInset: Dp,
    entries: List<QueryTable>,
    fetching: Boolean,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    // 该类查询下已有的数据（持久化的 + 本次手动抓的，手动优先）
    val tables = remember(entries, ui.manualTables, kind) {
        val manual = ui.manualTables.values.filter { it.kind == kind.key }
        val manualKeys = manual.map { it.semester }.toSet()
        val persisted = entries.filter { it.kind == kind.key && it.semester !in manualKeys }
        persisted + manual
    }

    val semesters = remember(tables) { tables.map { it.semester }.filter { it.isNotBlank() }.distinct().sortedDescending() }
    val picked = ui.pickedSemester[kind.key]
        ?: semesters.firstOrNull()
        ?: ""

    val current = tables.lastOrNull { it.semester == picked } ?: tables.lastOrNull()

    var mode by remember(kind.key) {
        mutableStateOf(if (tables.isEmpty()) DetailMode.WEB else DetailMode.DATA)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = bottomInset),
    ) {
        SubPageTopBar(title = kind.label, onBack = onBack)

        // 顶部操作条：模式切换 + 刷新
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val hint = when {
                fetching -> "后台抓取中…"
                current == null -> "尚未获取数据"
                current.count == 0 -> "未查询到数据"
                else -> "共 ${current.count} 条 · ${current.fetchedAt}"
            }
            Text(
                text = hint,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            if (tables.isNotEmpty() && mode == DetailMode.WEB) {
                Button(onClick = { mode = DetailMode.DATA }) { Text("看数据") }
                Spacer(Modifier.width(8.dp))
            }
            if (mode == DetailMode.DATA) {
                Button(onClick = { mode = DetailMode.WEB }) { Text("手动查询") }
                Spacer(Modifier.width(8.dp))
            }
            Button(onClick = onRefresh, enabled = !fetching) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.width(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("刷新")
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (mode) {
                DetailMode.DATA -> DataView(
                    kind = kind,
                    tables = tables,
                    semesters = semesters,
                    picked = picked,
                    current = current,
                    scroll = ui.detailScroll,
                    onPickSemester = { ui.pickSemester(kind.key, it) },
                    onGoManual = { mode = DetailMode.WEB },
                )

                DetailMode.WEB -> ManualQueryView(
                    ui = ui,
                    kind = kind,
                    onCaptured = { mode = DetailMode.DATA },
                )
            }
        }
    }
}

// ---------------- 数据视图 ----------------

@Composable
private fun DataView(
    kind: QueryKind,
    tables: List<QueryTable>,
    semesters: List<String>,
    picked: String,
    current: QueryTable?,
    scroll: ScrollState,
    onPickSemester: (String) -> Unit,
    onGoManual: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 学期选择（只有按学期查询的才显示）
        if (kind.perSemester && semesters.size > 1) {
            SemesterChips(
                semesters = semesters,
                picked = picked,
                onPick = onPickSemester,
            )
        }

        if (current == null || current.count == 0) {
            EmptyHint(kind = kind, onGoManual = onGoManual)
            return@Column
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 12.dp),
        ) {
            current.rows.forEach { row -> ResultCard(current, row) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun EmptyHint(kind: QueryKind, onGoManual: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "未查询到数据",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "可能是该学期确实没有${kind.label}，或数据还没来得及抓取。\n" +
                "可以先点上方「刷新」，或用手动查询自己打开教务网页抓一次。",
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGoManual) { Text("手动查询") }
    }
}

/** 学期筛选：一行可横向滚动的胶囊按钮 */
@Composable
private fun SemesterChips(
    semesters: List<String>,
    picked: String,
    onPick: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        semesters.forEach { sem ->
            val active = sem == picked
            Box(
                modifier = Modifier
                    .background(
                        color = if (active) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(16.dp),
                    )
                    .clickable { onPick(sem) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = semesterLabel(sem),
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    color = if (active) {
                        MiuixTheme.colorScheme.onPrimary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            }
        }
    }
}

/** "2026-2027-1" → "26-27 第1学期"（太长的话横向滚动也难受） */
private fun semesterLabel(code: String): String {
    val parts = code.split("-")
    if (parts.size != 3) return code
    val y1 = parts[0].takeLast(2)
    val y2 = parts[1].takeLast(2)
    return "$y1-$y2 第${parts[2]}学期"
}

/** 单条记录的卡片：首字段作标题，其余按「字段名 → 值」排列 */
@Composable
private fun ResultCard(table: QueryTable, row: List<String>) {
    val items = table.labeled(row).filter { it.second.isNotBlank() }
    if (items.isEmpty()) return
    val head = items.first()

    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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

// ---------------- WebView 手动查询（兜底通道） ----------------

@Composable
private fun ManualQueryView(
    ui: QueryUiState,
    kind: QueryKind,
    onCaptured: () -> Unit,
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var capturedHtml by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember {
        mutableStateOf("正在打开教务系统…若停在其他页面，请按菜单手动进入「${kind.menuPath}」")
    }

    LaunchedEffect(capturedHtml) {
        val html = capturedHtml ?: return@LaunchedEffect
        capturedHtml = null
        busy = false
        val parsed = JwglQueryParser.parse(html, kind.key, QueryStore.now())
        when {
            parsed == null -> {
                status = "没找到结果表格。请确认已登录，且当前页面已经是「${kind.label}」的结果页。"
                AppLog.w("QueryPage", "手动抓取 ${kind.key} 未找到结果表")
            }
            parsed.rows.isEmpty() -> {
                ui.putManual(parsed.copy(semester = parsed.semester))
                status = "未查询到数据"
                AppLog.i("QueryPage", "手动抓取 ${kind.key} 结果为空")
                onCaptured()
            }
            else -> {
                ui.putManual(parsed)
                status = "已获取 ${parsed.count} 条记录"
                AppLog.i("QueryPage", "手动抓取 ${kind.key} 成功 ${parsed.count} 条")
                onCaptured()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
            }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
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
                ) { Text("① 打开「${kind.label}」") }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        busy = true
                        status = "正在读取当前页面（含框架内的子页）…"
                        webView?.evaluateJavascript("AndroidBridge.sendHtml($COLLECT_HTML_JS)", null)
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "处理中…" else "② 抓取当前页面") }
            }
        }
    }
}

/**
 * 收集「当前页 + 所有 iframe 子页」的 HTML。
 *
 * ⚠️ 教务是 **iframe 框架布局**：主框架地址永远不变，真正的内容在子 iframe 里。
 * 只抓 `document.documentElement.outerHTML` 会拿到一个空壳（`<iframe>` 标签本身
 * 不含其内部文档），必然解析不到表格 —— 这正是「手动抓取总说没找到结果表」的原因。
 *
 * 这里把每个 iframe 的内容也一并取出、拼成一段 HTML 交给解析器。
 * 解析器本来就按「数据行最多的表」来找，混在一起也不受影响。
 */
private const val COLLECT_HTML_JS = """
(function(){
  var out = [document.documentElement.outerHTML];
  var fr = document.querySelectorAll('iframe, frame');
  for (var i = 0; i < fr.length; i++) {
    var f = fr[i];
    out.push('<!--IFRAME src=' + (f.src || '') + '-->');
    try {
      var d = f.contentDocument;
      if (d && d.documentElement) out.push(d.documentElement.outerHTML);
    } catch (e) {
      out.push('<!--IFRAME-FAIL ' + e + '-->');
    }
  }
  return out.join('\n');
})()
"""

// ---------------- WebView 构建 ----------------

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

    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

    // 返回键：WebView 是 View 层级，会抢在 Activity 的返回分发之前拿到 BACK。
    // 不处理的话，网页一旦有历史记录，系统返回就被它吃掉用于「网页后退」，
    // 页面栈永远退不出去（而用户看不出任何变化，像卡死一样）。
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
            handler?.proceed()
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean = false

        override fun onPageFinished(view: WebView?, url: String?) {
            AppLog.i("WebView", "页面加载完成: $url")
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

        @Deprecated("旧 API，给老页面兜底")
        override fun onReceivedError(
            view: WebView?,
            errorCode: Int,
            description: String?,
            failingUrl: String?,
        ) {
            AppLog.w("WebView", "加载出错($errorCode): $description @ $failingUrl")
        }
    }

    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            onProgress(newProgress / 100f)
        }

        /**
         * 处理 target=_blank / window.open。
         *
         * ⚠️ **不能把当前 WebView 直接塞进 transport** —— 那样同一个 View 会同时
         * 挂在主窗口和新窗口上（两个 parent），Android 立刻抛
         * `IllegalStateException: The specified child already has a parent` 闪退。
         * 强智教务的「查询」按钮用 `window.open` 打开结果页，正好踩中这一点。
         */
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?,
        ): Boolean {
            val host = view ?: return false
            val msg = resultMsg ?: return false
            val transport = msg.obj as? WebView.WebViewTransport ?: return false

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
                mainHandler.post { onHtml(html) }
            }
        },
        "AndroidBridge",
    )

    loadUrl(START_URL)
}
