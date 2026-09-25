package com.shzu.superschedule.ui

import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.shzu.superschedule.BuildConfig
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.shzu.superschedule.data.AppLog
import com.shzu.superschedule.data.Notifier
import com.shzu.superschedule.data.ScheduleStore
import com.shzu.superschedule.model.AppSettings
import com.shzu.superschedule.model.ColorSchemes
import com.shzu.superschedule.model.Course
import com.shzu.superschedule.model.CustomPalette
import com.shzu.superschedule.model.SemesterEntry
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate

/**
 * App 版本号，取自 `build.gradle.kts` 的 `versionName`（经 BuildConfig 生成）。
 *
 * ## 为什么不再写字面量（2026-09-25 改）
 *
 * 原先这里是 `const val APP_VERSION = "BETA-v1.4"`，与 `build.gradle.kts` 的
 * `versionName` 是**两处独立维护**的 —— 发版时漏改一处，就会出现
 * 「APK 装的是 v1.5、关于页却写着 v1.4」这种对不上的情况。
 * 现在只有一处真值（`versionName`），改版本号不用再记得同步这里。
 */
val APP_VERSION: String = BuildConfig.VERSION_NAME
const val AUTHOR_NAME = "@Natsume"
const val AUTHOR_EMAIL = "xu.tianhao@outlook.com"

/** 连续点击「开源说明」多少下解锁通知测试（彩蛋） */
private const val EGG_TAP_TARGET = 7

/** 彩蛋/邮箱连击的有效间隔：超过这个时间没点，计数归零 */
private const val TAP_WINDOW_MS = 2500L

/** 导出文件名，如 "石大课表_2026-2027-1.json" */
internal fun exportFileName(semester: String): String {
    val s = semester.ifBlank { "未命名学期" }
    return "石大课表_$s.json"
}

/** 设置页内部子页面 */
internal enum class Page {
    MAIN,
    CUSTOM_STYLE,
    LOG,

    /** 运行日志（连点「作者」7 下进入） */
    RUNTIME_LOG,
}

/**
 * 设置页的跨页面存活状态。
 *
 * ## 为什么要提升到外面（BETA-v1.3.2 #11）
 *
 * 旧实现把 `PageStack` 和滚动位置都 `remember` 在 `SettingsPage` 内部，
 * 而 `SettingsPage` 只在 `selectedTab == 2` 时才进入组合。
 * 于是用户在「自定义课表样式」二级菜单里切到「课表」页看一眼、再切回设置，
 * **二级菜单被关掉、滚动位置跳回顶部**，来回对照修改样式极其难受。
 *
 * 现在这个 holder 由 `MainScaffold` 持有（它整个 App 生命周期都在组合树里），
 * 页面栈与滚动位置都能跨越底部导航的切换而保留。
 */
internal class SettingsUiState {
    val stack = PageStack(Page.MAIN)
    val mainScroll = ScrollState(0)
    val customScroll = ScrollState(0)
    val logScroll = ScrollState(0)

    /** 配色集是否已展开（默认只陈列马卡龙） */
    var schemeExpanded by mutableStateOf(false)

    /** 彩蛋连击计数 */
    var eggTaps by mutableIntStateOf(0)
    var lastEggTapAt by mutableLongStateOf(0L)

    /** 反馈邮箱连击计数 */
    var mailTaps by mutableIntStateOf(0)
    var lastMailTapAt by mutableLongStateOf(0L)

    /** 「作者」连击计数：连点 7 下进运行日志（判定规则同「开源说明」彩蛋） */
    var authorTaps by mutableIntStateOf(0)
    var lastAuthorTapAt by mutableLongStateOf(0L)

    /** 运行日志页滚动位置 */
    val runtimeLogScroll = ScrollState(0)

    /** 通知权限状态的刷新信号（从系统设置回来后重新检测） */
    var notifTick by mutableIntStateOf(0)
}

@Composable
internal fun rememberSettingsUiState(): SettingsUiState = remember { SettingsUiState() }

@Composable
internal fun SettingsPage(
    ui: SettingsUiState,
    courses: List<Course>,
    settings: AppSettings,
    semesters: List<SemesterEntry>,
    onSettingsChange: (AppSettings) -> Unit,
    onReimport: () -> Unit,
    onSwitchSemester: (SemesterEntry) -> Unit,
    onRefreshSemesters: () -> Unit = {},
    onExportJson: (String, String) -> Unit = { _, _ -> },
    onImportJson: () -> Unit = {},
    /** 底部导航栏高度：内容延伸到栏后，需预留这么多空白 */
    bottomInset: Dp = 0.dp,
) {
    PageHost(
        stack = ui.stack,
        modifier = Modifier.fillMaxWidth(),
    ) { page ->
        when (page) {
            Page.CUSTOM_STYLE -> CustomStylePage(
                ui = ui,
                settings = settings,
                onSettingsChange = onSettingsChange,
                onBack = { ui.stack.pop() },
                bottomInset = bottomInset,
            )
            Page.LOG -> ChangelogPage(
                scroll = ui.logScroll,
                onBack = { ui.stack.pop() },
                bottomInset = bottomInset,
            )
            Page.RUNTIME_LOG -> RuntimeLogPage(
                scroll = ui.runtimeLogScroll,
                onBack = { ui.stack.pop() },
                bottomInset = bottomInset,
            )
            Page.MAIN -> MainSettings(
                ui = ui,
                courses = courses,
                settings = settings,
                semesters = semesters,
                onSettingsChange = onSettingsChange,
                onReimport = onReimport,
                onSwitchSemester = onSwitchSemester,
                onRefreshSemesters = onRefreshSemesters,
                onOpenCustomStyle = { ui.stack.push(Page.CUSTOM_STYLE) },
                onOpenLog = { ui.stack.push(Page.LOG) },
                onOpenRuntimeLog = { ui.stack.push(Page.RUNTIME_LOG) },
                onExportJson = onExportJson,
                onImportJson = onImportJson,
                bottomInset = bottomInset,
            )
        }
    }
}

// `SubPageTopBar` 已提升为公共组件，定义在 PageHeader.kt（查询页与本页共用）。

// ---------------- 一级页：设置主页 ----------------

@Composable
private fun MainSettings(
    ui: SettingsUiState,
    courses: List<Course>,
    settings: AppSettings,
    semesters: List<SemesterEntry>,
    onSettingsChange: (AppSettings) -> Unit,
    onReimport: () -> Unit,
    onSwitchSemester: (SemesterEntry) -> Unit,
    onRefreshSemesters: () -> Unit,
    onOpenCustomStyle: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenRuntimeLog: () -> Unit,
    onExportJson: (fileName: String, content: String) -> Unit,
    onImportJson: () -> Unit,
    bottomInset: Dp = 0.dp,
) {
    var showSemesterDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 学科门数：按课程名去重（而不是上课记录条数）
    val subjectCount = remember(courses) { courses.map { it.name }.distinct().size }

    // 通知权限的申请入口（Android 13+）
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        ui.notifTick++
        if (granted) {
            val ok = Notifier.sendTest(context, courses)
            if (ok) toastLong(context, "测试通知已发送，请下拉通知栏查看")
            else toast(context, "权限已授予，但系统通知仍处于关闭状态")
        } else {
            toast(context, "未授予通知权限，上课提醒无法生效")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(ui.mainScroll)
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp + bottomInset),
    ) {
        // 设置页大标题：按 MiuiX 规范的字号与留白
        PageHeader(title = "石大超级课表 · 设置", miuixDefault = true)

        // ================= 教务 =================
        SectionTitle("教务")
        Card {
            BasicComponent(
                title = "当前学期",
                summary = settings.semester.ifBlank { "未识别" },
                endActions = {
                    Text("切换", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
                },
                onClick = { showSemesterDialog = true },
            )
            // 开学日期：紧跟当前学期，调用系统日期选择器
            BasicComponent(
                title = "开学日期",
                summary = "第一周周一，用于自动计算当前教学周",
                endActions = {
                    Text(
                        text = settings.schoolStartDate.ifBlank { "未设置" },
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
                onClick = { showSystemDatePicker(context, settings.schoolStartDate) { picked, warning ->
                    if (picked != null) {
                        onSettingsChange(settings.copy(schoolStartDate = picked))
                    }
                    if (warning != null) toast(context, warning)
                } },
            )
            BasicComponent(
                title = "重新导入课表",
                summary = "打开教务系统，重新抓取当前课表",
                onClick = onReimport,
            )
            BasicComponent(
                title = "刷新可读学期",
                summary = "自动读取教务「学年学期」列表，无需手动导入",
                onClick = onRefreshSemesters,
            )
            BasicComponent(
                title = "数据更新时间",
                summary = settings.fetchTime.ifBlank { "尚未导入" },
            )
            BasicComponent(
                title = "学科门数",
                summary = "$subjectCount 门课程（按课程名去重）",
            )
        }
        Spacer(Modifier.height(4.dp))

        // ================= 显示（#13：紧随「教务」） =================
        SectionTitle("显示")
        Card {
            BasicComponent(
                title = "自定义课表样式",
                summary = "字体、间距、对齐、网格、配色…",
                endActions = {
                    Text("进入", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
                },
                onClick = onOpenCustomStyle,
            )
            SwitchRow(
                title = "显示教师名字",
                summary = "在课程格中显示授课教师",
                checked = settings.showTeacher,
                onChange = { onSettingsChange(settings.copy(showTeacher = it)) },
            )
            SwitchRow(
                title = "显示非本周课程",
                summary = "关闭后非本周课程彻底隐藏",
                checked = settings.showOtherWeeks,
                onChange = { onSettingsChange(settings.copy(showOtherWeeks = it)) },
            )
            SwitchRow(
                title = "标注[非本周]",
                summary = "在课程名前换行加上前缀便于识别",
                checked = settings.markOtherWeeks,
                onChange = { onSettingsChange(settings.copy(markOtherWeeks = it)) },
            )
        }
        Spacer(Modifier.height(4.dp))

        // ================= 课表存档（#13：下移到「系统与交互」前） =================
        SectionTitle("课表存档")
        Card {
            BasicComponent(
                title = "导出课表为 JSON",
                summary = "生成 json 文件，可发送给同学或备份",
                endActions = {
                    Text("导出", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
                },
                onClick = {
                    val archive = ScheduleStore.buildArchive(
                        semester = settings.semester,
                        semesterName = WeekCalc.semesterNameOf(settings.semester),
                        schoolStartDate = settings.schoolStartDate,
                        appVersion = APP_VERSION,
                        courses = courses,
                    )
                    onExportJson(exportFileName(settings.semester), ScheduleStore.encode(archive))
                },
            )
            BasicComponent(
                title = "导入课表 JSON",
                summary = "从 json 文件导入课表，导入前会先校验文件",
                endActions = {
                    Text("导入", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
                },
                onClick = onImportJson,
            )
            Text(
                text = "课表已保存在本地 json 文件中，下次打开 App 直接读取，无需重新导入。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(4.dp))

        // ================= 系统与交互 =================
        SectionTitle("系统与交互")
        Card {
            SwitchRow(
                title = "上课提醒",
                summary = "课程开始前推送通知",
                checked = settings.reminderEnabled,
                onChange = { onSettingsChange(settings.copy(reminderEnabled = it)) },
            )
            if (settings.reminderEnabled) {
                SliderRow(
                    title = "提前提醒",
                    value = settings.reminderAdvanceMinutes.toFloat(),
                    range = 1f..60f,
                    label = "${settings.reminderAdvanceMinutes} 分钟",
                    onChange = {
                        onSettingsChange(settings.copy(reminderAdvanceMinutes = it.toInt()))
                    },
                )
                // 彩蛋解锁后才出现：检查通知权限 + 发一条测试通知
                if (settings.eggUnlocked) {
                    ui.notifTick // 读一下刷新信号，权限变化后重新计算状态
                    val (ok, status) = Notifier.statusText(context)
                    BasicComponent(
                        title = "通知测试",
                        summary = status,
                        // ⚠️ 这里刻意**不传** BasicComponent 的 onClick，改用 combinedClickable：
                        // MiuiX 的 BasicComponent 没有 onLongPress 参数，而它的内部 clickable
                        // 会在 Main 阶段抢先处理 up 事件，外层再挂指针手势压不住它。
                        // 换成 combinedClickable 后，点击与长按由同一处统一裁决，不会互相打架。
                        modifier = Modifier.combinedClickable(
                            onClickLabel = "发送测试通知",
                            onLongClickLabel = "关闭通知测试",
                            onClick = {
                                when {
                                    !Notifier.hasPermission(context) ->
                                        notifPermissionLauncher.launch(
                                            android.Manifest.permission.POST_NOTIFICATIONS,
                                        )
                                    !Notifier.notificationsEnabled(context) ->
                                        openNotificationSettings(context)
                                    else -> {
                                        val sent = Notifier.sendTest(context, courses)
                                        if (sent) {
                                            toastLong(context, "测试通知已发送，请下拉通知栏查看")
                                        } else {
                                            toast(context, "发送失败，请检查系统通知设置")
                                        }
                                    }
                                }
                            },
                            // 长按关掉这一项（再连点「开源说明」7 下可重新解锁）
                            onLongClick = {
                                onSettingsChange(settings.copy(eggUnlocked = false))
                                toastLong(context, "已关闭「通知测试」，连点「开源说明」7 下可再次开启")
                            },
                        ),
                        endActions = {
                            Text(
                                text = if (ok) "发送" else "去开启",
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        },
                    )
                    Text(
                        text = "开启上课提醒后，建议先用它确认一下通知权限和渠道是否正常；" +
                            "测试通知会随机取一节课。长按本项可关闭。",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        // ================= 关于 =================
        SectionTitle("关于")
        Card {
            BasicComponent(
                title = "版本",
                summary = APP_VERSION,
                endActions = {
                    Text("更新日志", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
                },
                onClick = onOpenLog,
            )
            // 作者：连点 7 下进「运行日志」。
            // 判定规则与「开源说明」彩蛋完全一致（EGG_TAP_TARGET 次 + 超时归零），
            // 但用**独立计数器**，两个彩蛋互不干扰。
            BasicComponent(
                title = "作者",
                summary = AUTHOR_NAME,
                onClick = {
                    val now = System.currentTimeMillis()
                    if (now - ui.lastAuthorTapAt > TAP_WINDOW_MS) ui.authorTaps = 0
                    ui.lastAuthorTapAt = now
                    ui.authorTaps++
                    when {
                        ui.authorTaps >= EGG_TAP_TARGET -> {
                            ui.authorTaps = 0
                            onOpenRuntimeLog()
                        }
                        ui.authorTaps >= EGG_TAP_TARGET / 2 ->
                            toast(
                                context,
                                "还剩 ${EGG_TAP_TARGET - ui.authorTaps} 次查看运行日志",
                            )
                        else -> Unit
                    }
                },
            )
            // 反馈邮箱：点一次提示，再点一次复制到剪贴板
            BasicComponent(
                title = "反馈邮箱",
                summary = AUTHOR_EMAIL,
                endActions = {
                    Text(
                        text = "点击复制",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.primary,
                    )
                },
                onClick = {
                    val now = System.currentTimeMillis()
                    if (now - ui.lastMailTapAt > TAP_WINDOW_MS) ui.mailTaps = 0
                    ui.lastMailTapAt = now
                    ui.mailTaps++
                    if (ui.mailTaps >= 2) {
                        ui.mailTaps = 0
                        if (copyToClipboard(context, AUTHOR_EMAIL)) {
                            toastLong(context, "邮箱已复制：$AUTHOR_EMAIL")
                        } else {
                            toast(context, "复制失败，请手动记录：$AUTHOR_EMAIL")
                        }
                    } else {
                        toast(context, "再次点击即可复制邮箱至剪切板")
                    }
                },
            )
            BasicComponent(title = "数据来源", summary = "石河子大学教务一体化系统")
            // 开源说明：连续点击 7 下解锁彩蛋（通知测试）
            BasicComponent(
                title = "开源说明",
                summary = if (settings.eggUnlocked)
                    "本应用仅供个人学习使用，不收集、不上传任何账号信息（已解锁通知测试）"
                else "本应用仅供个人学习使用，不收集、不上传任何账号信息",
                onClick = {
                    val now = System.currentTimeMillis()
                    if (now - ui.lastEggTapAt > TAP_WINDOW_MS) ui.eggTaps = 0
                    ui.lastEggTapAt = now
                    ui.eggTaps++
                    when {
                        settings.eggUnlocked -> Unit
                        ui.eggTaps >= EGG_TAP_TARGET -> {
                            ui.eggTaps = 0
                            onSettingsChange(settings.copy(eggUnlocked = true))
                            toastLong(context, "已解锁「通知测试」，可在上方「上课提醒」中查看")
                        }
                        // 第 3 次连点起给进度反馈，免得用户以为没生效
                        ui.eggTaps >= EGG_TAP_TARGET / 2 ->
                            toast(
                                context,
                                "还剩 ${EGG_TAP_TARGET - ui.eggTaps} 次进入测试模式",
                            )
                        else -> Unit
                    }
                },
            )
        }
        Spacer(Modifier.height(28.dp))
    }

    if (showSemesterDialog) {
        SemesterDialog(
            semesters = semesters,
            current = settings.semester,
            onDismiss = { showSemesterDialog = false },
            onPick = {
                onSwitchSemester(it)
                showSemesterDialog = false
            },
        )
    }
}
