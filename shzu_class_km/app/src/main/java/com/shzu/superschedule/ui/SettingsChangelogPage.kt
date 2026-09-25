package com.shzu.superschedule.ui

/**
 * 设置 → 关于 里的「更新日志」与「运行日志」两个二级页。
 *
 * ⚠️ **发版时要改的 `CHANGELOG` 在这个文件里**（在列表顶部加一条本版本）。
 * 版本号本身不在这里 —— 它取自 `build.gradle.kts` 的 `versionName`
 * （经 `BuildConfig.VERSION_NAME`，见 `APP_VERSION`）。
 *
 * ⚠️ 从 `SettingsPage.kt` **原样搬移**，函数体一行未改。
 */

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

// ---------------- 二级页：更新日志 ----------------

private data class LogItem(val version: String, val summary: String)

/**
 * 版本历史。
 *
 * BETA-v1.3.2 起所有版本号统一加 `BETA-` 前缀，明示这是测试版。
 */
private val CHANGELOG = listOf(
    LogItem(
        "BETA-v1.4",
        "新增「查询」页：考试安排 / 课程成绩 / 等级考试成绩三类结果，导入课表后自动在后台" +
            "抓取全部学期，无需手动操作（数据走内嵌浏览器内的请求，绕开此前会话丢失的问题）；\n" +
            "考试安排：考试时间与考场加粗置顶，日期后自动标出「第N周周X」（如 2026年11月28日（第13周周六） 10:00-11:30）；\n" +
            "课程成绩：课程名作标题加粗，成绩放大加粗，不及格标红；\n" +
            "开课学期与课程编号收进卡片底部小字，不再与成绩抢位置；\n" +
            "等级考试成绩：考级课程作标题加粗，同一门只显示真正有分数的成绩，" +
            "不再出现「分数类成绩 0」；按 CET-4 → CET-6 → NCRE 一级 → NCRE 二级 → 其他 排序；\n" +
            "修复等级考试成绩的列错位与「笔试」空记录（教务把第二层表头写进了数据区）；\n" +
            "查询页大标题改用与设置页完全相同的字号与留白；\n" +
            "左右翻动周视图时，上方的日期条与星期表头跟随课表一起滚动；\n" +
            "「本周」标签移到「第 N 周」之前；\n" +
            "「今日课程」与「本周课表」两个大标题严格对齐；\n" +
            "构建脚本合并为单一入口（原 27 个逐字节相同的脚本）。",
    ),
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
internal fun RuntimeLogPage(
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
internal fun ChangelogPage(scroll: ScrollState, onBack: () -> Unit, bottomInset: Dp = 0.dp) {
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

