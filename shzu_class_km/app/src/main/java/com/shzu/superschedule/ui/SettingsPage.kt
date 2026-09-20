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

const val APP_VERSION = "BETA-v1.3.2"
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

// ---------------- 二级页：自定义课表样式 ----------------

@Composable
private fun CustomStylePage(
    ui: SettingsUiState,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
    bottomInset: Dp = 0.dp,
) {
    var showReset by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(ui.customScroll)
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp + bottomInset),
    ) {
        SubPageTopBar(title = "自定义课表样式", onBack = onBack)

        SectionTitle("尺寸与间距")
        Card {
            // 课程高度标尺：决定 10 行等距表格中每一行的高度
            SliderRow(
                title = "课程高度",
                value = settings.rowHeight,
                range = 34f..96f,
                label = "${settings.rowHeight.toInt()} dp",
                onChange = { onSettingsChange(settings.copy(rowHeight = it)) },
            )
            Text(
                text = "以该值为唯一标尺，课表严格划分为 10 行等距表格；" +
                    "一门课占几个课时就渲染几格高（2 课时 = 2 格，3 课时 = 3 格）。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            SliderRow(
                title = "字体大小",
                value = settings.fontSize,
                range = 9f..18f,
                label = settings.fontSize.toInt().toString(),
                onChange = { onSettingsChange(settings.copy(fontSize = it)) },
            )
            SliderRow(
                title = "文字与边界间距",
                value = settings.cellPadding,
                range = 0f..12f,
                label = settings.cellPadding.toInt().toString(),
                onChange = { onSettingsChange(settings.copy(cellPadding = it)) },
            )
            MiuixDropdownRow(
                title = "文字对齐",
                options = listOf("居中", "居左", "两端对齐"),
                selected = settings.alignment,
                onChange = { onSettingsChange(settings.copy(alignment = it)) },
            )
        }
        Spacer(Modifier.height(4.dp))

        SectionTitle("网格与边框")
        Card {
            SwitchRow(
                title = "显示网格线",
                summary = "课表背景按 10 行 × 7 列绘制虚线网格（与星期列严格对齐）",
                checked = settings.showGridLines,
                onChange = { onSettingsChange(settings.copy(showGridLines = it)) },
            )
            SwitchRow(
                title = "显示课程格边框",
                summary = "为每个课程格绘制细边框（独立选项）",
                checked = settings.showCellBorder,
                onChange = { onSettingsChange(settings.copy(showCellBorder = it)) },
            )
        }
        Spacer(Modifier.height(4.dp))

        SectionTitle("文字样式")
        Card {
            SwitchRow(
                title = "加粗课程名字体",
                summary = "开启后课程名使用加粗字重，关闭使用常规字重",
                checked = settings.boldName,
                onChange = { onSettingsChange(settings.copy(boldName = it)) },
            )
            if (settings.boldName) {
                MiuixDropdownRow(
                    title = "加粗字重",
                    options = listOf("Bold（默认）", "Medium"),
                    selected = if (settings.useBoldWeight) 0 else 1,
                    onChange = {
                        onSettingsChange(settings.copy(useBoldWeight = it == 0))
                    },
                )
            }
            SwitchRow(
                title = "隐藏过长课程名",
                summary = "课程名超过 3 行时隐藏后面的内容并显示省略号",
                checked = settings.ellipsizeName,
                onChange = { onSettingsChange(settings.copy(ellipsizeName = it)) },
            )
            SwitchRow(
                title = "隐藏过长教师名",
                summary = "教师名超过 2 行时隐藏后面的内容并显示省略号",
                checked = settings.ellipsizeTeacher,
                onChange = { onSettingsChange(settings.copy(ellipsizeTeacher = it)) },
            )
        }
        Spacer(Modifier.height(4.dp))

        SectionTitle("非本周课程")
        Card {
            SliderRow(
                title = "淡化强度",
                value = settings.otherWeekAlpha.toFloat(),
                range = 5f..60f,
                label = "${settings.otherWeekAlpha}%",
                onChange = {
                    onSettingsChange(settings.copy(otherWeekAlpha = it.toInt()))
                },
            )
            Text(
                text = "非本周课程会向课表底色淡化，但**始终保持不透明**，" +
                    "因此不会漏出底层的网格线与格子边线。数值越小越淡。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(4.dp))

        SectionTitle("配色")
        Card {
            SwitchRow(
                title = "使用预设配色集",
                summary = "关闭后使用你自己的配色合集（最多 3 套）",
                checked = settings.useDefaultColors,
                onChange = { on ->
                    if (!on && settings.customPalettes.isEmpty()) {
                        // 首次选用自定义配色：自动创建一套，颜色复制马卡龙
                        val p = CustomPalette("自定义 1", ColorSchemes.macaron)
                        onSettingsChange(
                            settings.copy(
                                useDefaultColors = false,
                                customPalettes = listOf(p),
                                activeCustomPalette = 0,
                                customColors = p.colors,
                            ),
                        )
                    } else {
                        onSettingsChange(settings.copy(useDefaultColors = on))
                    }
                },
            )
            ColorSchemeList(
                settings = settings,
                expanded = ui.schemeExpanded,
                onToggleExpand = { ui.schemeExpanded = !ui.schemeExpanded },
                onPick = { index ->
                    onSettingsChange(
                        settings.copy(useDefaultColors = true, colorSchemeIndex = index),
                    )
                },
            )
            CustomPaletteSection(
                settings = settings,
                onSettingsChange = onSettingsChange,
            )
            SwitchRow(
                title = "自定义文字颜色",
                summary = "开启后统一使用指定文字颜色，并自动校验对比度",
                checked = settings.useCustomTextColor,
                onChange = { onSettingsChange(settings.copy(useCustomTextColor = it)) },
            )
            if (settings.useCustomTextColor) {
                TextColorRow(
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        SectionTitle("重置")
        Card {
            BasicComponent(
                title = "恢复默认选项",
                summary = "重置全部外观设置到默认值（格子高度 56dp / 字号 12 / 间距 3），不影响课表与自定义配色合集",
                onClick = { showReset = true },
            )
        }
        Spacer(Modifier.height(28.dp))
    }

    if (showReset) {
        ConfirmDialog(
            title = "恢复默认？",
            message = "将重置字体、间距、对齐、网格、配色等全部外观选项到默认值，" +
                "不会删除课程数据，也不会删除你的自定义配色合集。",
            confirmText = "恢复默认",
            onDismiss = { showReset = false },
            onConfirm = {
                onSettingsChange(settings.resetAppearance())
                showReset = false
            },
        )
    }
}

// ---------------- 二级页：更新日志 ----------------

private data class LogItem(val version: String, val summary: String)

/**
 * 版本历史。
 *
 * BETA-v1.3.2 起所有版本号统一加 `BETA-` 前缀，明示这是测试版。
 */
private val CHANGELOG = listOf(
    LogItem(
        "BETA-v1.3.2",
        "移除预测性返回手势功能（跟手预览体验不佳，回归标准返回处理与过渡动画）；\n" +
            "修复网格线纵轴与星期列完全错位的问题（改为按真实列布局计算）；\n" +
            "非本周课程改为「向底色淡化但不透明」，不再透出底层网格线与格子边线；\n" +
            "时间冲突不再叠字：占课时少的课程优先占据重叠课时（1-2 节有 A、1-4 节有 B 时，" +
            "1-2 节显示 A，第 3-4 节显示 B），并可在课程详情里直接指定优先显示哪一门；\n" +
            "冲突课程列表默认展开，无需二次点击；\n" +
            "多教师课程可在课程详情里切换周视图显示 1 位或全部教师；\n" +
            "省略过长内容改为「课程名超过 3 行」「教师名超过 2 行」；\n" +
            "默认值调整：格子高度 56dp、字体大小 12、文字与边界间距 3；\n" +
            "莫兰迪与石墨灰蓝整体提亮，新增冷色调配色集「冰川冷调」；\n" +
            "新增自定义配色合集（最多 3 套，选用自定义配色时自动创建并复制马卡龙）；\n" +
            "配色集默认只陈列「马卡龙」，再点一下当前项即可展开其余配色集；\n" +
            "自定义文字颜色时校验与底色的对比度，看不清时可选择自适应文字色；\n" +
            "设置页新增「通知测试」（连点「开源说明」7 下解锁、长按可再次关闭）用于检查通知权限，" +
            "测试通知会随机取一节课按真实提醒格式展示；\n" +
            "反馈邮箱点一次提示、点两次即可复制到剪贴板；\n" +
            "应用内提示（toast）显示时间统一为 1.2 秒；\n" +
            "应用图标替换为 Icon-256（此前一直使用系统默认图标）；\n" +
            "修正反馈邮箱拼写错误（outkook → outlook）；\n" +
            "底栏改为真实的背景高斯模糊（磨砂玻璃），且模糊半径沿栏高渐变（栏底最糊、栏顶最清）；\n" +
            "底栏底色改为顶边完全透明的纵向渐变，去掉溢出到栏外的模糊光晕与顶部硬边；\n" +
            "设置页分组顺序调整，「显示」紧随「教务」，「课表存档」移到「系统与交互」之前；\n" +
            "「今日课程」与「本周课表」的大标题字号与留白完全对齐；\n" +
            "设置页大标题改用 MiuiX 规范字号与留白；\n" +
            "切到「课表」页再切回设置时，自定义样式二级菜单与滚动位置都会被保留。",
    ),
    LogItem(
        "BETA-v1.3.1",
        "课表改为以「课程高度」滑块为唯一标尺的严格 10 行等距表格，彻底消除行距不等；\n" +
            "移除午休/晚休分隔条，忽略下课时间，所有课按同样高宽渲染；\n" +
            "连排课时合并长格，长度严格 = 课时数 × 格高（2 课时 2 格，3 课时 3 格）；\n" +
            "修复长格被父容器压成 1 行的问题（改用浮层精确按行定位）；\n" +
            "修复非本周课程与本周课程叠在同一格互相压字的问题；\n" +
            "底栏改为只模糊背景，图标与文字保持清晰；\n" +
            "课表以本地 json 文件持久化，下次打开直接读取，不再重复导入；\n" +
            "新增课表 JSON 导入/导出，导入前严格校验并给出失败原因；\n" +
            "修复刷新可读学期失效（根因：取 Cookie 的 URL 未带 /jsxsd 路径，拿不到登录会话）；\n" +
            "课程名默认使用 Bold 字重（可切 Medium），课程格文字默认两端对齐、字号 11；\n" +
            "「【非本周】」改为「[非本周]」；修复下拉菜单点击闪退；\n" +
            "今日页加回「今日课程」大标题，非本周课程不出现在当日课表；\n" +
            "修复桌面小组件行控件 id 重复导致显示错乱；\n" +
            "修复小组件「更多尺寸」档位缺失与桌面编辑页「载入窗口小部件时出现问题」；\n" +
            "修复桌面小组件内容区域不随所选尺寸变化的问题（改为按真实可用高度分配行高）。",
    ),
    LogItem(
        "BETA-v1.3",
            "修复二级页面返回逻辑（返回上一级而非退出），顶部新增返回按钮；\n" +
            "页面切换加入过渡动画；\n" +
            "课表改为固定 10 行 × 7 列网格，行高完全相等，课程按课时数铺满对应行数；\n" +
            "移除「课程格高度」自定义选项；网格线改为横竖均有的醒目虚线；\n" +
            "今日页按科目统计门数（一天最多 5 门）；加粗课程名选项真正生效；\n" +
            "下拉菜单改用 MiuiX 原生样式；分块小标题居左顶格；\n" +
            "冲突角标改为精致圆形图标；修复关闭「显示非本周课程」后仍然可见的问题；\n" +
            "刷新可读学期改为自动抓取；开学日期改用系统日期选择器并校验是否周一；\n" +
            "关于页删除「应用名称」栏；底部导航栏加入高斯模糊；\n" +
            "「课程数量」改为按课程名去重的学科门数。",
    ),
    LogItem(
        "BETA-v1.2",
        "修复冲突判断（仅本周内同课时才算冲突）；冲突角标改为「!」；\n" +
            "同一节课跨多个课时自动合并成长格；非本周课程文字同步淡化（默认 25%）；\n" +
            "自定义颜色改用标准 HSL 色盘；【非本周】后换行；\n" +
            "网格线改为课表背景绘制虚线，课程格边框另设独立开关；\n" +
            "自定义选项收进「自定义课表样式」二级菜单；新增加粗课程名开关、恢复默认选项；\n" +
            "关于页版本号可查看更新日志；学期切换可从教务读取更多学期；\n" +
            "修复桌面小部件无法显示、尺寸只剩 2×2 的问题。",
    ),
    LogItem(
        "BETA-v1.1",
        "今日页与周课表页标题、留白优化；周课表左侧改为 1-10 节并显示上下课时间点；\n" +
            "上午/下午/晚间之间标注午休晚休；非本周课程明显灰化并加【非本周】前缀；\n" +
            "课程信息完整显示、地点加 @ 前缀、文字顶对齐；点击课程格查看详情并可删除本周本节；\n" +
            "点击空白格添加课程；预设配色 + 自定义颜色与文字颜色；\n" +
            "配置开学日期自动计算教学周；支持切换学期；\n" +
            "新增 4×2 / 4×4 / 2×4 / 2×2 桌面小组件。",
    ),
    LogItem(
        "BETA-v1.0",
        "首个版本：内嵌 WebView 进入石河子大学教务系统，登录后一键抓取学期理论课表；\n" +
            "今日课程、周课表两个主页面；采用 MiuiX 设计风格。",
    ),
)

// ---------------- 二级页：运行日志 ----------------

/**
 * 运行日志页（设置 → 关于 → 连点「作者」7 下进入）。
 *
 * 展示 [AppLog] 的全部内容：既是当前进程的内存缓冲，也包含文件里
 * App 上次运行、乃至**崩溃那一刻**的记录 —— 出问题时把内容复制出来即可。
 */
@Composable
private fun RuntimeLogPage(
    scroll: ScrollState,
    onBack: () -> Unit,
    bottomInset: Dp = 0.dp,
) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(AppLog.readAll()) }
    var tick by remember { mutableIntStateOf(0) }

    // 首次进入与每次「刷新」都重新读一遍
    LaunchedEffect(tick) { lines = AppLog.readAll() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp + bottomInset),
    ) {
        SubPageTopBar(title = "运行日志", onBack = onBack)

        SectionTitle("操作")
        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "共 ${lines.size} 行。记录应用启动、教务请求、WebView 事件与崩溃异常。" +
                        "反馈问题时把内容复制出来即可。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { tick++ }, modifier = Modifier.weight(1f)) {
                        Text("刷新")
                    }
                    Button(
                        onClick = {
                            if (copyToClipboard(context, AppLog.asText())) {
                                toast(context, "日志已复制到剪贴板")
                            } else {
                                toast(context, "复制失败")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("复制")
                    }
                    Button(
                        onClick = {
                            AppLog.clear()
                            tick++
                            toast(context, "日志已清空")
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("清空")
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        SectionTitle("明细")
        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (lines.isEmpty()) {
                    Text(
                        text = "暂无日志",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    // 只渲染最近 400 行：日志可能有上千行，全量组合会明显卡顿
                    lines.takeLast(400).forEach { line ->
                        Text(
                            text = line,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangelogPage(scroll: ScrollState, onBack: () -> Unit, bottomInset: Dp = 0.dp) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp + bottomInset),
    ) {
        SubPageTopBar(title = "更新日志", onBack = onBack)

        SectionTitle("版本历史")
        Card {
            CHANGELOG.forEachIndexed { index, item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = item.version, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        if (index == 0) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "当前版本",
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = item.summary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

// ---------------- 通用行组件 ----------------

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(text = label, fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        enabled = enabled,
        endActions = {
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        },
        onClick = { if (enabled) onChange(!checked) },
    )
}

/**
 * 点击式下拉菜单。
 *
 * 用 `OverlayDropdownPreference`：它在同一窗口内的 overlay 层渲染，
 * `renderInRootScaffold = false` 时无需额外的 window token，不会闪退
 * （原先的 `WindowDropdownPreference` 会开独立系统窗口，无 token 时直接崩）。
 */
@Composable
private fun MiuixDropdownRow(
    title: String,
    options: List<String>,
    selected: Int,
    onChange: (Int) -> Unit,
) {
    OverlayDropdownPreference(
        items = options,
        selectedIndex = selected.coerceIn(0, (options.size - 1).coerceAtLeast(0)),
        title = title,
        renderInRootScaffold = false,
        onSelectedIndexChange = onChange,
    )
}

/** 通用确认弹窗 */
@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text(confirmText) }
                }
            }
        }
    }
}

// ---------------- 配色 ----------------

/**
 * 预设配色集列表。
 *
 * 默认**只陈列「马卡龙」**（`DEFAULT_VISIBLE_INDEX`）；
 * 再点一次当前已选中的那一套（或点右上角「展开全部」）才会铺开其余配色集。
 * 这样设置页首屏不会被 6 套色卡塞满。
 */
@Composable
private fun ColorSchemeList(
    settings: AppSettings,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val enabled = settings.useDefaultColors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "配色集", fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(
                text = if (expanded) "收起" else "展开全部（${ColorSchemes.all.size}）",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.clickable { onToggleExpand() },
            )
        }
        Text(
            text = if (enabled) "选中一套整体配色；再点一下已选中的配色即可展开其余配色集"
            else "已关闭（使用自定义配色合集）",
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(10.dp))
        val visible = if (expanded) {
            ColorSchemes.all.indices.toList()
        } else {
            listOf(ColorSchemes.DEFAULT_VISIBLE_INDEX)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            visible.forEach { index ->
                val (name, colors) = ColorSchemes.all[index]
                val selected = enabled && index == settings.colorSchemeIndex
                PaletteCardRow(
                    name = name,
                    colors = colors,
                    selected = selected,
                    enabled = enabled,
                    onClick = {
                        when {
                            // 「二次点击」：已选中的那一套再点一下就展开其余配色集
                            selected && !expanded -> onToggleExpand()
                            else -> onPick(index)
                        }
                    },
                )
            }
        }
    }
}

/** 一行色卡：名称 + 15 个色块 */
@Composable
private fun PaletteCardRow(
    name: String,
    colors: List<Long>,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (selected) Modifier.border(
                    1.dp,
                    MiuixTheme.colorScheme.primary,
                    RoundedCornerShape(8.dp),
                ) else Modifier,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            fontSize = 12.sp,
            modifier = Modifier.size(width = 66.dp, height = 18.dp),
            color = if (selected) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            colors.take(15).forEach { argb ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(CourseColors.argbToColor(argb)),
                )
            }
        }
    }
}

/**
 * 自定义配色合集（最多 3 套）。
 *
 * - 点某一行即选中它（同时把「使用预设配色集」关掉）；
 * - 「新建」复制马卡龙配色，方便改；
 * - 数量 > 1 时可删除。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomPaletteSection(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    val palettes = settings.customPalettes
    val max = CustomPalette.MAX_CUSTOM_PALETTES

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(text = "自定义配色合集", fontSize = 15.sp)
        Text(
            text = "最多 $max 套；新建时默认复制「马卡龙」，可逐格修改",
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(10.dp))

        if (palettes.isEmpty()) {
            Text(
                text = "还没有自定义配色集，关闭上方「使用预设配色集」会自动创建一套。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            palettes.forEachIndexed { index, palette ->
                val selected = !settings.useDefaultColors &&
                    index == settings.activeCustomPalette
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (selected) Modifier.border(
                                1.dp,
                                MiuixTheme.colorScheme.primary,
                                RoundedCornerShape(8.dp),
                            ) else Modifier,
                        )
                        .clickable {
                            onSettingsChange(
                                settings.copy(
                                    useDefaultColors = false,
                                    activeCustomPalette = index,
                                    customColors = palette.colors,
                                ),
                            )
                        }
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = palette.name.ifBlank { "自定义 ${index + 1}" },
                        fontSize = 12.sp,
                        modifier = Modifier.size(width = 66.dp, height = 18.dp),
                        color = if (selected) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurface,
                    )
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        val cols = if (palette.colors.size >= 3) palette.colors
                        else ColorSchemes.macaron
                        cols.take(15).forEach { argb ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(16.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(CourseColors.argbToColor(argb)),
                            )
                        }
                    }
                    if (palettes.size > 1) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "删除",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier
                                .clickable {
                                    val rest = palettes.toMutableList().also { it.removeAt(index) }
                                    val newActive = settings.activeCustomPalette
                                        .coerceAtMost(rest.size - 1).coerceAtLeast(0)
                                    onSettingsChange(
                                        settings.copy(
                                            customPalettes = rest,
                                            activeCustomPalette = newActive,
                                            customColors = rest.getOrNull(newActive)?.colors
                                                ?: emptyList(),
                                            useDefaultColors = if (rest.isEmpty()) true
                                            else settings.useDefaultColors,
                                        ),
                                    )
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }

        if (palettes.size < max) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "＋ 新建自定义配色集（复制马卡龙）",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        val p = CustomPalette("自定义 ${palettes.size + 1}", ColorSchemes.macaron)
                        val newList = palettes + p
                        onSettingsChange(
                            settings.copy(
                                customPalettes = newList,
                                activeCustomPalette = newList.lastIndex,
                                useDefaultColors = false,
                                customColors = p.colors,
                            ),
                        )
                    }
                    .padding(vertical = 6.dp),
            )
        }

        // 选中自定义合集时才显示 15 个槽位的编辑入口
        if (!settings.useDefaultColors && palettes.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            CustomColorSlotsRow(settings = settings, onSettingsChange = onSettingsChange)
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** 15 个颜色槽位的自定义（HSL 色盘逐个替换，作用于当前选中的自定义合集） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomColorSlotsRow(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    var editing by remember { mutableStateOf<Int?>(null) }
    val palettes = settings.customPalettes
    if (palettes.isEmpty()) return
    val index = settings.activeCustomPalette.coerceIn(0, palettes.lastIndex)
    val current = palettes[index]
    val colors = if (current.colors.size >= 3) current.colors else ColorSchemes.macaron
    val slots = remember(colors) {
        List(15) { i ->
            colors.getOrNull(i)?.takeIf { it != 0L }
                ?.let { CourseColors.argbToColor(it) } ?: CourseColors.palette[i]
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "「${current.name.ifBlank { "自定义 ${index + 1}" }}」的 15 个颜色槽位",
            fontSize = 13.sp,
        )
        Text(
            text = "点色块用 HSL 色盘替换该槽位颜色",
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            slots.forEachIndexed { i, color ->
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(color)
                        .clickable { editing = i },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }

    val editIndex = editing
    if (editIndex != null) {
        HslColorPickerDialog(
            initial = slots[editIndex],
            title = "自定义颜色 ${editIndex + 1}",
            onDismiss = { editing = null },
            onConfirm = { picked ->
                val list = slots.mapIndexed { i, c ->
                    if (i == editIndex) CourseColors.colorToArgb(picked)
                    else CourseColors.colorToArgb(c)
                }
                val newPalettes = palettes.toMutableList().also {
                    it[index] = current.copy(colors = list)
                }
                onSettingsChange(
                    settings.copy(customPalettes = newPalettes, customColors = list),
                )
                editing = null
            },
        )
    }
}

/**
 * 自定义文字颜色 + **对比度校验**。
 *
 * 选完颜色后立刻检查它与当前色板 15 个底色的对比度；
 * 只要有格子低于 3:1，就弹窗提醒，并让用户二选一：
 * 「对看不清的格子自动使用自适应文字色」或「保持当前设置」。
 */
@Composable
private fun TextColorRow(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var warningFor by remember { mutableStateOf<Color?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "文字颜色", fontSize = 15.sp)
            Text(
                text = if (settings.autoTextColorFallback)
                    "对比度不足的格子会自动改用深/浅自适应色"
                else "所有格子统一使用该颜色",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CourseColors.argbToColor(settings.customTextColor))
                .border(
                    0.5.dp,
                    MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f),
                    RoundedCornerShape(8.dp),
                )
                .clickable { editing = true },
        )
    }

    if (editing) {
        HslColorPickerDialog(
            initial = CourseColors.argbToColor(settings.customTextColor),
            title = "选择文字颜色",
            onDismiss = { editing = false },
            onConfirm = { picked ->
                val updated = settings.copy(customTextColor = CourseColors.colorToArgb(picked))
                onSettingsChange(updated)
                editing = false
                if (CourseColors.lowContrastSlots(updated, picked).isNotEmpty()) {
                    warningFor = picked
                }
            },
        )
    }

    val pending = warningFor
    if (pending != null) {
        TextColorContrastDialog(
            textColor = pending,
            settings = settings,
            onAdaptive = {
                onSettingsChange(settings.copy(autoTextColorFallback = true))
                warningFor = null
            },
            onKeep = {
                onSettingsChange(settings.copy(autoTextColorFallback = false))
                warningFor = null
            },
        )
    }
}

/** 文字颜色对比度提醒弹窗 */
@Composable
private fun TextColorContrastDialog(
    textColor: Color,
    settings: AppSettings,
    onAdaptive: () -> Unit,
    onKeep: () -> Unit,
) {
    val badSlots = remember(textColor, settings) {
        CourseColors.lowContrastSlots(settings, textColor)
    }
    Dialog(onDismissRequest = onKeep) {
        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text("文字可能看不清", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "所选文字颜色与 ${badSlots.size} 个格子底色的对比度低于 3:1，" +
                        "这些格子上的字会难以辨认。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )

                // 预览：把有问题的底色连同文字一起画出来
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val palette = CourseColors.activePalette(settings)
                    badSlots.take(6).forEach { idx ->
                        val bg = palette.getOrNull(idx) ?: return@forEach
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(bg),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "课程",
                                fontSize = 11.sp,
                                color = textColor,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Button(onClick = onAdaptive, modifier = Modifier.fillMaxWidth()) {
                    Text("对看不清的格子使用自适应文字色（推荐）")
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onKeep, modifier = Modifier.fillMaxWidth()) {
                    Text("保持当前设置")
                }
            }
        }
    }
}

// ---------------- 系统日期选择器 ----------------

/**
 * 调用系统 `DatePickerDialog` 选择开学日期。
 * 校验：日期必须能解析、且**必须是周一**；否则给出警告不写入。
 */
private fun showSystemDatePicker(
    context: Context,
    initial: String,
    onResult: (date: String?, warning: String?) -> Unit,
) {
    val base = runCatching { LocalDate.parse(initial) }.getOrNull() ?: WeekCalc.today()
    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            val picked = runCatching { LocalDate.of(year, month + 1, dayOfMonth) }.getOrNull()
            when {
                picked == null -> onResult(null, "日期无效，请重新选择")
                picked.dayOfWeek.value != 1 -> onResult(
                    null,
                    "开学日期应为第一周的周一，你选的 ${picked.monthValue}月${picked.dayOfMonth}日 是" +
                        "周${WeekCalc.weekdayCn(picked)}。已忽略本次选择。",
                )
                else -> onResult(picked.toString(), null)
            }
        },
        base.year,
        base.monthValue - 1,
        base.dayOfMonth,
    ).apply {
        setTitle("选择开学日期（第一周周一）")
    }.show()
}

/** 打开本应用的通知设置页（用户在系统层面关掉通知时用） */
private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { toast(context, "无法打开系统通知设置，请手动前往设置里开启") }
}

/** 复制文本到剪贴板 */
private fun copyToClipboard(context: Context, text: String): Boolean = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("邮箱", text))
    true
}.getOrDefault(false)

// ---------------- 弹窗 ----------------

/** 学期切换：无数据的学期也可点 —— 点击后会自动去教务抓取该学期课表 */
@Composable
private fun SemesterDialog(
    semesters: List<SemesterEntry>,
    current: String,
    onDismiss: () -> Unit,
    onPick: (SemesterEntry) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text(text = "切换学期", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "选择任意学期，App 会自动去教务抓取该学期课表；" +
                        "标「本地已有」的可直接离线切换",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))

                if (semesters.isEmpty()) {
                    Text(
                        text = "暂无学期数据，请先导入一次课表",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                semesters.forEach { s ->
                    val isCurrent = s.code == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isCurrent) MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)
                                else Color.Transparent,
                            )
                            .clickable { onPick(s) }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = s.name.ifBlank { WeekCalc.semesterNameOf(s.code) },
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = buildString {
                                    append(s.code)
                                    if (s.startDate.isNotBlank()) append("  · 开学 ${s.startDate}")
                                    if (s.finished) append("  · 已结束")
                                },
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        when {
                            isCurrent -> Text(
                                text = "当前",
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.primary,
                            )
                            s.hasData -> Text(
                                text = "本地已有",
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            else -> Text(
                                text = "需抓取",
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.primary.copy(alpha = 0.8f),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
            }
        }
    }
}
