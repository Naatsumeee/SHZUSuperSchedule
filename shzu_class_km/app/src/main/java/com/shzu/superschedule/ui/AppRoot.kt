package com.shzu.superschedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.shzu.superschedule.data.AppLog
import com.shzu.superschedule.data.AppRepository
import com.shzu.superschedule.data.CourseParser
import com.shzu.superschedule.data.JwglQueryFetcher
import com.shzu.superschedule.data.JwglSession
import com.shzu.superschedule.data.QueryStore
import com.shzu.superschedule.data.ScheduleStore
import com.shzu.superschedule.data.ScheduleStore.MergeMode
import com.shzu.superschedule.model.AppSettings
import com.shzu.superschedule.model.Course
import com.shzu.superschedule.model.QueryTable
import com.shzu.superschedule.model.SemesterEntry
import com.shzu.superschedule.widget.ScheduleWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.net.HttpURLConnection
import java.net.URL

/** 应用根：无课表时显示导入页，否则显示主界面 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val repo = remember { AppRepository(context) }

    // 先加载设置，才知道当前学期，才能定位该学期的 json 存档
    var settings by remember { mutableStateOf(repo.loadSettings()) }
    // #4：优先从本地 json 文件读取课表；文件不存在时 ScheduleStore 会自动
    // 从 SharedPreferences 迁移一次，老用户升级不丢数据。
    var courses by remember {
        mutableStateOf(
            ScheduleStore.load(context, settings.semester, repo)?.courses
                ?: repo.loadCourses(),
        )
    }

    var semesters by remember { mutableStateOf(repo.loadSemesters()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showImport by remember { mutableStateOf(courses.isEmpty()) }
    val scope = rememberCoroutineScope()

    // 查询数据（考试安排 / 课程成绩 / 等级考试成绩）：
    // 从本地存档读出来直接可用，后台抓取只负责往里补新数据。
    val initialQueries = remember { QueryStore.load(context) }
    var queryEntries by remember { mutableStateOf(initialQueries.entries) }
    var queryUpdatedAt by remember { mutableStateOf(initialQueries.updatedAt) }
    var queryFetching by remember { mutableStateOf(false) }

    // 弹窗状态
    var detailCourse by remember { mutableStateOf<Course?>(null) }
    var detailWeek by remember { mutableStateOf(0) }
    var addTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // 切换学期时正在抓取
    var switching by remember { mutableStateOf(false) }

    /**
     * 写课表：同时落 json 文件与 SharedPreferences，并刷新小组件。
     * json 文件是**主存档**，SharedPreferences 仅作兼容缓存。
     */
    fun persistCourses(list: List<Course>) {
        courses = list
        repo.saveCourses(settings.semester, list)
        ScheduleStore.save(
            context,
            ScheduleStore.buildArchive(
                semester = settings.semester,
                semesterName = WeekCalc.semesterNameOf(settings.semester),
                schoolStartDate = settings.schoolStartDate,
                appVersion = APP_VERSION,
                courses = list,
            ),
        )
        ScheduleWidgetProvider.refreshAll(context)
    }

    fun persistSettings(s: AppSettings) {
        settings = s
        repo.saveSettings(s)
        ScheduleWidgetProvider.refreshAll(context)
    }

    /**
     * 在后台抓取考试安排 / 课程成绩 / 等级考试成绩。
     *
     * **全程无感**：不弹任何界面、不做任何跳转；某项失败也只写日志，不影响其它项，
     * 更不会影响课表导入本身。
     *
     * 触发时机：导入课表之后（此时必然已登录教务，Cookie 现成），
     * 以及用户在查询页手动下拉刷新时（[silent] = false 会给一句轻提示）。
     *
     * [semestersToFetch] 负责「自动抓取所有学期」—— 由调用方把可读学期列表传进来。
     */
    fun refreshQueries(semestersToFetch: List<String>, silent: Boolean = true) {
        if (queryFetching) {
            AppLog.i("AppRoot", "查询抓取正在进行中，跳过本次触发")
            return
        }
        scope.launch {
            queryFetching = true
            val list = semestersToFetch.filter { it.isNotBlank() }
            AppLog.i("AppRoot", "开始后台抓取查询数据：${list.size} 个学期")

            val summary = JwglQueryFetcher.fetchAll(list)

            // 只有真抓到东西才落盘：merge 是按 (kind, semester) 替换的，
            // 这一轮没抓到的学期会被保留，不会因为一次失败把老数据冲掉。
            if (summary.tables.isNotEmpty()) {
                val merged = QueryStore.merge(queryEntries, summary.tables)
                val archive = QueryStore.Archive(
                    entries = merged,
                    updatedAt = QueryStore.now(),
                )
                queryEntries = merged
                queryUpdatedAt = archive.updatedAt
                QueryStore.save(context, archive)
            }
            queryFetching = false

            // 提示要区分「没数据」和「抓不到」
            if (!silent) toastLong(context, summary.hint)
        }
    }

    fun applyFetchedSemesters(codes: List<String>, activeCode: String) {
        val merged = mergeSemesters(
            existing = semesters,
            fetchedCodes = codes,
            hasDataCode = activeCode,
        )
        semesters = merged
        repo.mergeSemesters(merged)
    }

    /**
     * #4/#5：切换到某学期。
     *
     * 先读本地 json；若本地没有，则**自动去教务抓取该学期课表**并落盘。
     * 这样"刷新可读学期"出来的学期才真正可用，而不是选中后空白。
     */
    fun switchSemester(entry: SemesterEntry) {
        val start = entry.startDate.ifBlank {
            WeekCalc.guessStartDate(entry.code)
        }
        val newSettings = settings.copy(
            semester = entry.code,
            schoolStartDate = start,
        )
        settings = newSettings
        repo.saveSettings(newSettings)

        // 1) 本地 json 有就直接用
        val local = ScheduleStore.load(context, entry.code, repo)
        if (local != null && local.courses.isNotEmpty()) {
            courses = local.courses
            ScheduleWidgetProvider.refreshAll(context)
            return
        }

        // 2) 本地没有 -> 后台自动抓取该学期课表
        courses = emptyList()
        switching = true
        scope.launch {
            val fetched = fetchCoursesSilently(entry.code)
            switching = false
            if (fetched != null && fetched.isNotEmpty()) {
                courses = fetched
                repo.saveCourses(entry.code, fetched)
                ScheduleStore.save(
                    context,
                    ScheduleStore.buildArchive(
                        semester = entry.code,
                        semesterName = WeekCalc.semesterNameOf(entry.code),
                        schoolStartDate = start,
                        appVersion = APP_VERSION,
                        courses = fetched,
                    ),
                )
                persistSettings(
                    newSettings.copy(
                        fetchTime = java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm",
                            java.util.Locale.CHINA,
                        ).format(java.util.Date()),
                    ),
                )
                ScheduleWidgetProvider.refreshAll(context)
            } else {
                toastLong(
                    context,
                    "未能抓取到「${entry.name.ifBlank { entry.code }}」的课表，" +
                        "可能是尚未登录教务或该学期无数据。请先点「重新导入课表」登录一次。",
                )
            }
        }
    }

    if (showImport) {
        ImportPage(
            canCancel = courses.isNotEmpty(),
            onCancel = { showImport = false },
            onImported = { list, semester, fetchTime, allSemesters ->
                val sem = semester.ifBlank { WeekCalc.semesterCodeOf() }
                applyFetchedSemesters(allSemesters, sem)

                val newSettings = settings.copy(
                    semester = sem,
                    fetchTime = fetchTime,
                    schoolStartDate = settings.schoolStartDate.ifBlank {
                        WeekCalc.guessStartDate(sem)
                    },
                )
                settings = newSettings
                repo.saveSettings(newSettings)
                courses = list
                repo.saveCourses(sem, list)
                // #4：导入后立刻落一份 json 存档，下次启动直接读文件
                ScheduleStore.save(
                    context,
                    ScheduleStore.buildArchive(
                        semester = sem,
                        semesterName = WeekCalc.semesterNameOf(sem),
                        schoolStartDate = newSettings.schoolStartDate,
                        appVersion = APP_VERSION,
                        courses = list,
                    ),
                )
                showImport = false
                ScheduleWidgetProvider.refreshAll(context)
                // 导入完成 = 刚登录过，顺手把考试安排/成绩也抓回来（全程无感）
                refreshQueries(allSemesters.ifEmpty { listOf(sem) })
            },
        )
    } else {
        // #4：导出 / 导入 json
        val fileActions = rememberScheduleFileActions(
            fileName = exportFileName(settings.semester),
            contentProvider = {
                ScheduleStore.encode(
                    ScheduleStore.buildArchive(
                        semester = settings.semester,
                        semesterName = WeekCalc.semesterNameOf(settings.semester),
                        schoolStartDate = settings.schoolStartDate,
                        appVersion = APP_VERSION,
                        courses = courses,
                    ),
                )
            },
            onValid = { archive ->
                persistCourses(
                    ScheduleStore.merge(courses, archive.courses, ScheduleStore.MergeMode.REPLACE),
                )
                val sem = archive.semester.ifBlank { settings.semester }
                persistSettings(
                    settings.copy(
                        semester = sem,
                        schoolStartDate = archive.schoolStartDate.ifBlank {
                            settings.schoolStartDate
                        },
                    ),
                )
                toastLong(
                    context,
                    "导入成功：${archive.courses.size} 条课程记录" +
                        if (sem.isNotBlank()) "（学期 $sem）" else "",
                )
            },
            onError = { reason -> toastLong(context, reason) },
        )

        MainScaffold(
            courses = courses,
            settings = settings,
            semesters = semesters,
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            onSettingsChange = { persistSettings(it) },
            onReimport = { showImport = true },
            onCourseClick = { c, w ->
                detailCourse = c
                detailWeek = if (w > 0) w else WeekCalc.currentWeek(settings.schoolStartDate)
            },
            onEmptyClick = { day, sec -> addTarget = day to sec },
            onSwitchSemester = { entry -> switchSemester(entry) },
            // 自动抓取：后台静默读取教务「学年学期」列表，无需用户手动导入
            onRefreshSemesters = {
                if (jwglCookie().isNullOrBlank()) {
                    toastLong(
                        context,
                        "尚未登录教务，请先点「重新导入课表」登录一次",
                    )
                } else {
                    scope.launch {
                        val codes = fetchSemestersSilently()
                        if (codes.isNotEmpty()) {
                            applyFetchedSemesters(codes, settings.semester)
                            // 刷新后把这些学期的 hasData 重新按本地存档标记一次
                            semesters = mergeSemesters(
                                existing = repo.loadSemesters(),
                                fetchedCodes = codes,
                                hasDataCode = settings.semester,
                            )
                            repo.mergeSemesters(semesters)
                            toastLong(context, "已读取 ${codes.size} 个学期")
                        } else {
                            toastLong(
                                context,
                                "读取失败：教务会话已过期或网络异常，请先点「重新导入课表」登录",
                            )
                        }
                    }
                }
            },
            onExportJson = { name, content -> fileActions.export(name, content) },
            onImportJson = { fileActions.import() },
            queryEntries = queryEntries,
            queryUpdatedAt = queryUpdatedAt,
            queryFetching = queryFetching,
            onRefreshQueries = {
                val list = semesters.map { it.code }.ifEmpty { listOf(settings.semester) }
                refreshQueries(list, silent = false)
            },
        )

        if (switching) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                top.yukonga.miuix.kmp.basic.Text("正在抓取该学期课表…")
            }
        }
    }

    // 课程详情
    detailCourse?.let { c ->
        // 只有**当周实际开课**的课程才算冲突：不在本周开的课不与本周的课冲突
        val conflicts = courses
            .filter {
                it.weekday == c.weekday &&
                    it.activeInWeek(detailWeek) &&
                    c.activeInWeek(detailWeek) &&
                    overlapsSection(it, c)
            }
            // 同名同节次的跨周记录算同一门课，避免冲突列表里出现重复项
            .distinctBy { it.key() }
        CourseDetailDialog(
            course = c,
            week = detailWeek,
            settings = settings,
            conflicts = conflicts,
            onDelete = {
                persistCourses(deleteWeekOf(courses, c, detailWeek))
                detailCourse = null
            },
            // 教师显示 / 冲突优先级：就地更新这条课程记录并刷新界面与小组件
            onUpdate = { updated ->
                courses = courses.map { if (it.key() == updated.key()) updated else it }
                repo.saveCourses(settings.semester, courses)
                ScheduleStore.save(
                    context,
                    ScheduleStore.buildArchive(
                        semester = settings.semester,
                        semesterName = WeekCalc.semesterNameOf(settings.semester),
                        schoolStartDate = settings.schoolStartDate,
                        appVersion = APP_VERSION,
                        courses = courses,
                    ),
                )
                // 详情弹窗里的课程对象也要同步，否则弹窗内不会立即刷新
                detailCourse = courses.firstOrNull { it.key() == updated.key() } ?: updated
                ScheduleWidgetProvider.refreshAll(context)
            },
            onDismiss = { detailCourse = null },
        )
    }

    // 添加课程
    addTarget?.let { (day, sec) ->
        AddCourseDialog(
            weekday = day,
            startSection = sec,
            onDismiss = { addTarget = null },
            onConfirm = { c ->
                persistCourses(courses + c)
                addTarget = null
            },
        )
    }
}

/**
 * 教务请求所需的 Cookie 头。
 *
 * 实现已统一到 [JwglSession.cookie]（后台抓取查询数据也要用同一套逻辑，
 * 那边在 `data` 包里，不能反向依赖 `ui`），这里保留一个转发，历史调用点不用动。
 * 关于「为什么必须用带 `/jsxsd` 的 URL 取 Cookie」，详见 [JwglSession] 的注释。
 */
private fun jwglCookie(): String? = JwglSession.cookie()

/**
 * 静默抓取教务「学年学期」下拉框数据。
 *
 * 复用 WebView 已建立的登录 Cookie（[jwglCookie]），直接在后台拉取
 * 「学期理论课表」页面的 HTML，解析出全部可选学期，全程无需用户操作。
 */
private suspend fun fetchSemestersSilently(): List<String> = withContext(Dispatchers.IO) {
    runCatching {
        val cookie = jwglCookie()
        if (cookie.isNullOrBlank()) {
            android.util.Log.w("AppRoot", "抓取学期失败：无可用 Cookie（未登录）")
            return@runCatching emptyList()
        }
        val conn = (URL(SEMESTER_PAGE).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Cookie", cookie)
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36",
            )
            setRequestProperty("Referer", "https://jwgl.shzu.edu.cn/jsxsd/framework/xsMain.jsp")
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            connectTimeout = 12000
            readTimeout = 12000
            instanceFollowRedirects = true
        }
        val html = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val list = CourseParser.semesters(html)
        android.util.Log.i(
            "AppRoot",
            "抓取学期：cookieLen=${cookie.length} htmlLen=${html.length} parsed=${list.size} $list",
        )
        list
    }.getOrElse {
        android.util.Log.w("AppRoot", "抓取学期异常", it)
        emptyList()
    }
}

private const val SEMESTER_PAGE = "https://jwgl.shzu.edu.cn/jsxsd/xskb/xskb_list.do"

/**
 * 静默抓取**指定学期**的课表。
 *
 * 复用 WebView 已登录的 Cookie，POST 到「学期理论课表」接口，
 * `xnxq01id` 传目标学期代码，再用 [CourseParser] 解析出课程。
 * 用于「刷新可读学期」后切换到本地没有数据的学期时自动补齐。
 */
private suspend fun fetchCoursesSilently(semester: String): List<Course>? =
    withContext(Dispatchers.IO) {
        runCatching {
            // 同样必须用带 /jsxsd 路径的 URL 取 Cookie，否则拿不到 JSESSIONID
            val cookie = jwglCookie()
            if (cookie.isNullOrBlank()) {
                android.util.Log.w("AppRoot", "抓取 $semester 失败：无可用 Cookie（未登录）")
                return@runCatching null
            }

            val conn = (URL(SEMESTER_PAGE).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Cookie", cookie)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36",
                )
                setRequestProperty("Referer", SEMESTER_PAGE)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
            }
            val body = "xnxq01id=$semester&sfFD=1"
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val parsed = CourseParser.parse(html)
            android.util.Log.i(
                "AppRoot",
                "抓取 $semester 课表：cookieLen=${cookie.length} htmlLen=${html.length} " +
                    "parsed=${parsed.size}",
            )
            if (parsed.isEmpty()) null else parsed
        }.getOrElse {
            android.util.Log.w("AppRoot", "抓取 $semester 课表异常", it)
            null
        }
    }

/**
 * 合并学期列表。
 * [fetchedCodes] 是从教务「学年学期」下拉框读到的全部学期；
 * 其中 [hasDataCode] 是当前激活学期（确定有数据），
 * 其余学期若本地已有课表数据则标记为有数据，否则置灰不可选。
 */
private fun mergeSemesters(
    existing: List<SemesterEntry>,
    fetchedCodes: List<String>,
    hasDataCode: String,
): List<SemesterEntry> {
    val byCode = LinkedHashMap<String, SemesterEntry>()
    existing.forEach { e ->
        if (e.code.isNotBlank()) byCode[e.code] = e
    }
    if (hasDataCode.isNotBlank()) {
        val old = byCode[hasDataCode]
        byCode[hasDataCode] = (old ?: SemesterEntry(code = hasDataCode)).copy(
            name = old?.name?.ifBlank { null } ?: WeekCalc.semesterNameOf(hasDataCode),
            startDate = old?.startDate?.ifBlank { null } ?: WeekCalc.guessStartDate(hasDataCode),
            hasData = old?.hasData == true || true,
        )
    }
    fetchedCodes.forEach { code ->
        if (code.isBlank()) return@forEach
        val old = byCode[code]
        if (old == null) {
            byCode[code] = SemesterEntry(
                code = code,
                name = WeekCalc.semesterNameOf(code),
                startDate = WeekCalc.guessStartDate(code),
                finished = isFinished(code),
                hasData = false,
            )
        } else {
            byCode[code] = old.copy(finished = old.finished || isFinished(code))
        }
    }
    return byCode.values.sortedByDescending { it.code }
}

/** 学期是否已结束（按代码里的学年推断） */
private fun isFinished(code: String): Boolean {
    val year = code.substringBefore("-").toIntOrNull() ?: return false
    val term = code.substringAfterLast("-").toIntOrNull() ?: return false
    val now = java.util.Calendar.getInstance()
    val nowYear = now.get(java.util.Calendar.YEAR)
    val nowMonth = now.get(java.util.Calendar.MONTH) + 1
    val currentTerm = if (nowMonth in 2..7) nowYear - 1 else nowYear
    val key = year * 10 + term
    val curKey = currentTerm * 10 + (if (nowMonth in 2..7) 2 else 1)
    return key < curKey
}

/**
 * 删除某课程在第 week 周的上课安排。
 * 若课程只在该周出现，则整条移除；否则从周次串里剔除该周，并新建一条"不含该周"的课程。
 */
private fun deleteWeekOf(courses: List<Course>, target: Course, week: Int): List<Course> {
    val weeks = target.weekSet
    if (weeks.isEmpty() || weeks.size <= 1 || target.custom) {
        return courses.filterNot { it.key() == target.key() }
    }
    val remaining = weeks - week
    if (remaining.isEmpty()) {
        return courses.filterNot { it.key() == target.key() }
    }
    val weekStr = compressWeeks(remaining.sorted())
    return courses.map {
        if (it.key() == target.key()) it.copy(weeks = weekStr) else it
    }
}

/** 把周次列表压缩为 "1-16周" / "1,3,5周" 形式 */
private fun compressWeeks(sorted: List<Int>): String {
    if (sorted.isEmpty()) return ""
    val sb = StringBuilder()
    var start = sorted.first()
    var prev = start
    for (i in 1 until sorted.size) {
        val cur = sorted[i]
        if (cur == prev + 1) {
            prev = cur
            continue
        }
        if (sb.isNotEmpty()) sb.append(",")
        sb.append(if (start == prev) "$start" else "$start-$prev")
        start = cur
        prev = cur
    }
    if (sb.isNotEmpty()) sb.append(",")
    sb.append(if (start == prev) "$start" else "$start-$prev")
    return sb.append("周").toString()
}

@Composable
private fun MainScaffold(
    courses: List<Course>,
    settings: AppSettings,
    semesters: List<SemesterEntry>,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onSettingsChange: (AppSettings) -> Unit,
    onReimport: () -> Unit,
    onCourseClick: (Course, Int) -> Unit,
    onEmptyClick: (Int, Int) -> Unit,
    onSwitchSemester: (SemesterEntry) -> Unit,
    onRefreshSemesters: () -> Unit,
    onExportJson: (String, String) -> Unit = { _, _ -> },
    onImportJson: () -> Unit = {},
    queryEntries: List<QueryTable> = emptyList(),
    queryUpdatedAt: String = "",
    queryFetching: Boolean = false,
    onRefreshQueries: () -> Unit = {},
) {
    // 设置页的页面栈与滚动位置在这里（MainScaffold）持有：
    // MainScaffold 在整个 App 生命周期内都保持组合，所以用户在「自定义课表样式」
    // 二级菜单里切到「课表」页看一眼、再切回「设置」时，二级菜单依然开着，
    // 滚动位置也不会跳回顶部（#11）。
    val settingsUi = rememberSettingsUiState()

    // 查询页的页面栈与结果缓存同理：由 MainScaffold 持有，
    // 切到别的 tab 再回来时二级页与已抓到的查询结果都还在。
    val queryUi = rememberQueryUiState()

    // 抓取用 WebView（1×1，用户不可见）。
    //
    // 后台抓取的请求**由它发出**而不是自己拼 HTTP —— 真机上手工带 Cookie 请求
    // 会被教务判未登录（详见 JwglQueryFetcher 的注释）。这里把它注入给抓取器，
    // 宿主挂在本函数的内容里，于是整个 App 生命周期内抓取通道都可用。
    val fetchHost = remember {
        WebViewFetcher().also { JwglQueryFetcher.install(it) }
    }

    // 底栏「背景高斯模糊」所需的离屏图层。必须由 MainScaffold 持有 ——
    // 它同时是页面内容与底栏的父级，两边才能共用同一组图层。
    val barBackdrop = rememberBarBackdrop()

    Scaffold(
        bottomBar = {
            BlurredBottomBar(
                backdrop = barBackdrop,
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
            )
        },
    ) { padding ->
        // #4 二次修订：内容**不再**按底栏高度内缩，而是延伸到屏幕底部，
        // 这样滚动时内容会从半透明底栏后面穿过，底栏才能真的"模糊背后的内容"。
        // 各页面通过 bottomInset 自己预留底部空白，保证最后一项不被挡住。
        val layoutDirection = LocalLayoutDirection.current
        val bottomInset = padding.calculateBottomPadding()
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 每帧把内容录进离屏图层，供底栏做背景模糊。
                // ⚠️ 必须排在 `.padding()` **之前**：`drawWithContent` 的绘制原点
                // 取决于它在 modifier 链中的位置，若排在 padding 之后，录制区域会从
                // padding 内缩后的位置开始，与 onGloballyPositioned 报告的坐标（节点左上角）
                // 相差一个状态栏高度，导致磨砂层取错内容位置。
                .recordBarBackdrop(barBackdrop)
                // ⚠️ 页面底色必须由**内容侧**铺（外层 MiuiX Scaffold 画的底不在内容
                // 节点的绘制范围里，快照背景会是透明的）。注意**不能**在
                // `recordBarBackdrop` 内部的 `record {}` 里画这块底：
                // `GraphicsLayer.record {}` 中 `drawContent()` 必须是第一个绘制调用，
                // 前面一旦先画了底色，整页内容就录不进图层 —— 底栏会变成一片纯色、
                // 完全看不出模糊。详见 BlurBar.kt 文件头的踩坑记录。
                .background(MiuixTheme.colorScheme.surface)
                .padding(
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                    top = padding.calculateTopPadding(),
                ),
        ) {
            // 抓取用 WebView 的宿主：1×1 像素、不可见、不参与交互。
            // 必须真实存在于视图树里 —— 完全没挂载的 WebView 在部分 ROM 上
            // 不会执行 JS、也不发请求。
            QueryFetchHost(
                fetcher = fetchHost,
                modifier = Modifier.size(1.dp),
            )

            when (selectedTab) {
                0 -> TodayPage(
                    courses = courses,
                    settings = settings,
                    bottomInset = bottomInset,
                    onCourseClick = { onCourseClick(it, 0) },
                )
                1 -> WeekPage(
                    courses = courses,
                    settings = settings,
                    bottomInset = bottomInset,
                    onCourseClick = onCourseClick,
                    onEmptyClick = onEmptyClick,
                )
                // 查询页排在周视图之后
                2 -> QueryPage(
                    ui = queryUi,
                    bottomInset = bottomInset,
                    entries = queryEntries,
                    updatedAt = queryUpdatedAt,
                    fetching = queryFetching,
                    onRefresh = onRefreshQueries,
                )
                else -> SettingsPage(
                    ui = settingsUi,
                    courses = courses,
                    settings = settings,
                    semesters = semesters,
                    bottomInset = bottomInset,
                    onSettingsChange = onSettingsChange,
                    onReimport = onReimport,
                    onSwitchSemester = onSwitchSemester,
                    onRefreshSemesters = onRefreshSemesters,
                    onExportJson = onExportJson,
                    onImportJson = onImportJson,
                )
            }
        }
    }
}

/**
 * 半透明磨砂底部导航栏（磨砂层在底、图标文字在上）。
 *
 * ## 透明效果不明显的真正原因（BETA-v1.3.2 二次修复 #4）
 *
 * MiuiX 的 `NavigationBar` 内部**硬编码**了 `.background(color)`，
 * 而 `color` 默认取 `MiuixTheme.colorScheme.surface` —— 一块完全不透明的底色。
 * 我们的磨砂层虽然画在它下面，却被这块不透明底整个盖住，等于白做。
 *
 * 因此这里必须显式把底色调成透明（`Color.Transparent`），
 * 并把 MiuiX 自带的分隔线关掉（改由磨砂层顶部的 1px 高光代替），
 * 磨砂层才真正透得出来 —— 进而才能看见它做的**真实背景模糊**。
 */
@Composable
private fun BlurredBottomBar(
    backdrop: BarBackdropState,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
) {
    BlurredBarContainer(
        state = backdrop,
        modifier = Modifier.fillMaxWidth(),
    ) {
        NavigationBar(
            color = Color.Transparent,
            showDivider = false,
        ) {
            NavigationBarItem(
                selected = selectedTab == 0,
                onClick = { onTabSelected(0) },
                icon = Icons.Filled.Today,
                label = "今日",
            )
            NavigationBarItem(
                selected = selectedTab == 1,
                onClick = { onTabSelected(1) },
                icon = Icons.Filled.DateRange,
                label = "课表",
            )
            NavigationBarItem(
                selected = selectedTab == 2,
                onClick = { onTabSelected(2) },
                icon = Icons.Filled.Search,
                label = "查询",
            )
            NavigationBarItem(
                selected = selectedTab == 3,
                onClick = { onTabSelected(3) },
                icon = Icons.Filled.Settings,
                label = "设置",
            )
        }
    }
}

/** 两门课的节次区间是否重叠 */
internal fun overlapsSection(a: Course, b: Course): Boolean {
    val sa = spanOf(a)
    val sb = spanOf(b)
    if (sa == null || sb == null) return false
    return sa.first <= sb.last && sb.first <= sa.last
}

private fun spanOf(c: Course): IntRange? {
    val s = if (c.startSection > 0) c.startSection else return null
    val cnt = c.sectionCount.coerceAtLeast(1)
    return s until (s + cnt)
}
