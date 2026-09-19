package com.shzu.superschedule.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.shzu.superschedule.MainActivity
import com.shzu.superschedule.R
import com.shzu.superschedule.data.AppRepository
import com.shzu.superschedule.model.ClassTime
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 石大超级课表 桌面小组件（显示今日课程）。
 * 支持 2x2 / 4x2 / 2x4 / 4x4 四种尺寸。
 */
class ScheduleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        android.util.Log.i(TAG, "onUpdate ids=${appWidgetIds.joinToString()}")
        appWidgetIds.forEach { id ->
            push(context, appWidgetManager, id)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        android.util.Log.i(TAG, "onAppWidgetOptionsChanged id=$appWidgetId")
        push(context, appWidgetManager, appWidgetId, newOptions)
    }

    /**
     * 推送一次 RemoteViews。
     *
     * 关键：**必须捕获异常**。早期版本在 `onUpdate` 里直接调用
     * `WidgetRenderer.build()`，一旦渲染抛异常（数据缺失 / 资源找不到 /
     * RemoteViews 不支持某个方法），异常会沿着广播回调向上抛，
     * 系统只记录一条 warning 就作罢 —— 结果是**这个小组件永远收不到
     * RemoteViews**，桌面端一直停留在 initialLayout，
     * 用户看到的就是「载入窗口小部件时出现问题」。
     *
     * 这里改为：渲染失败时降级推送一个「兜底 RemoteViews」，
     * 至少保证桌面能拿到一份合法布局，不会一直卡在载入中。
     */
    private fun push(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        options: Bundle? = null,
    ) {
        val opts = options ?: runCatching {
            appWidgetManager.getAppWidgetOptions(appWidgetId)
        }.getOrElse { Bundle() }

        val rv = runCatching { WidgetRenderer.build(context, opts) }
            .onFailure { android.util.Log.e(TAG, "build failed id=$appWidgetId", it) }
            .getOrElse { WidgetRenderer.fallback(context, it) }

        // 推送前先在本进程内**自检一次膨胀**。
        //
        // 桌面端拿到 RemoteViews 后是走受限膨胀器渲染的，一旦布局里含有
        // 白名单外的控件（如 android.view.View ），膨胀会抛
        // `Class not allowed to be inflated`，而**桌面会把异常静默吞掉**，
        // 用户只能看到永久灰块，日志里毫无线索（v1.3.1 踩过这个大坑）。
        //
        // 这里提前 apply 一次：同一套白名单校验会立刻在本进程暴露问题，
        // 日志里能直接看到具体是哪个控件不被允许，不必再去反推桌面行为。
        runCatching {
            rv.apply(context, android.widget.FrameLayout(context))
        }
            .onFailure { android.util.Log.e(TAG, "RemoteViews 自检膨胀失败 id=$appWidgetId", it) }
            .onSuccess { android.util.Log.i(TAG, "RemoteViews 自检通过 id=$appWidgetId") }

        runCatching { appWidgetManager.updateAppWidget(appWidgetId, rv) }
            .onFailure { android.util.Log.e(TAG, "updateAppWidget failed id=$appWidgetId", it) }
    }

    companion object {
        private const val TAG = "ShzuWidget"

        /**
         * 数据变化后刷新全部小组件。
         *
         * 必须遍历**所有** provider（四个尺寸档位各一个），
         * 否则只有 2×2 档会刷新，其他档位仍显示旧数据。
         */
        val ALL_PROVIDERS = listOf(
            ScheduleWidgetProvider::class.java,
            SmallWidgetProvider::class.java,
            WideWidgetProvider::class.java,
            TallWidgetProvider::class.java,
            BigWidgetProvider::class.java,
        )

        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            for (cls in ALL_PROVIDERS) {
                val ids = runCatching {
                    mgr.getAppWidgetIds(ComponentName(context, cls))
                }.getOrElse { continue }
                if (ids.isEmpty()) continue
                android.util.Log.i(TAG, "refreshAll ${cls.simpleName} ids=${ids.joinToString()}")
                ids.forEach { id ->
                    runCatching {
                        val opts = runCatching { mgr.getAppWidgetOptions(id) }
                            .getOrElse { Bundle() }
                        val rv = runCatching { WidgetRenderer.build(context, opts) }
                            .onFailure { android.util.Log.e(TAG, "build failed id=$id", it) }
                            .getOrElse { WidgetRenderer.fallback(context, it) }
                        runCatching { rv.apply(context, android.widget.FrameLayout(context)) }
                            .onFailure { android.util.Log.e(TAG, "RemoteViews 自检膨胀失败 id=$id", it) }
                        mgr.updateAppWidget(id, rv)
                    }.onFailure { android.util.Log.e(TAG, "refreshAll failed id=$id", it) }
                }
            }
        }
    }
}

/** 小组件渲染：按尺寸档位构建 RemoteViews */
object WidgetRenderer {

    /**
     * 一个桌面单元格约 70dp。
     * 关键：`android:minWidth/minHeight` 单位是**单元格数**，
     * 而 `OPTION_APPWIDGET_MIN_WIDTH/HEIGHT` 单位是 **dp**。
     * 若把两者直接比较，尺寸判断会全部落到最小档 —— 这就是"只有 2x2"的原因。
     */
    private const val CELL_DP = 70

    /**
     * 尺寸档位。
     *
     * v1.3.1：配合 `widget_schedule_info.xml` 把可缩放区间放开到
     * **1 格 ~ 5 格**（minResizeW/H = 40dp = 1 格，maxResize = 320/390dp = 4~5 格），
     * 桌面长按拖动时就能拉到更多档位，不再只有 2×2。
     */
    enum class Size(val cols: Int, val rows: Int) {
        MICRO(1, 1),
        TINY(2, 2),
        WIDE(3, 2),
        TALL(2, 3),
        BIG(4, 4);

        /**
         * 标称高度（dp）。
         *
         * Android 官方换算：`n 格 = 70n − 30 dp`，所以
         * 1 格 = 40dp、2 格 = 110dp、3 格 = 180dp、4 格 = 250dp。
         * 仅在宿主没给 options（拿不到真实高度）时作为兜底。
         */
        fun nominalHeightDp(): Int = when (this) {
            MICRO -> 40
            TINY -> 110
            WIDE -> 110
            TALL -> 180
            BIG -> 250
        }
    }

    /**
     * 判断尺寸。
     *
     * 坑点：`OPTION_APPWIDGET_MIN_WIDTH/HEIGHT` 的单位是 **dp**，
     * 而 appwidget-provider 里的 `minWidth/minHeight` 单位是**单元格数**。
     * 早期版本直接拿 2 / 4 这样的单元格数与 dp 比较，导致所有小组件
     * 都落到最小档 —— 用户看到的现象就是"只有 2×2 一个尺寸"。
     *
     * 这里对 <=20 的小值按"单元格数 × 70dp"换算，并对 options 缺失时
     * 给出更宽松的兜底（部分启动器不会写全 options）。
     */
    fun sizeOf(options: Bundle): Size {
        val rawW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val rawH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        // options 缺失：宁可给大一点，也不要错误地锁死在 2×2
        if (rawW <= 0 && rawH <= 0) return Size.BIG

        // 记录拉长方向的最大值，作为另一维的兜底
        val w = normalize(rawW)
        val h = normalize(rawH)

        return when {
            w >= 300 && h >= 300 -> Size.BIG      // ≥4×4
            w >= 170 && h >= 170 -> Size.BIG      // ≥3×3
            w >= 170 -> Size.WIDE                 // 宽扁 ≥3 格
            h >= 170 -> Size.TALL                 // 高瘦 ≥3 格
            w >= 100 || h >= 100 -> Size.TINY     // ≥2 格
            else -> Size.MICRO                    // 1 格
        }
    }

    /** 小值视为单元格数并换算为 dp；同时参考 MAX 值避免误判 */
    private fun normalize(value: Int): Int = when {
        value <= 0 -> 0
        value in 1..20 -> value * CELL_DP
        else -> value
    }

    /** 综合 MIN/MAX 选项推断尺寸（更稳） */
    fun sizeOfDetailed(options: Bundle): Size {
        val minW = normalize(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH))
        val minH = normalize(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT))
        val maxW = normalize(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH))
        val maxH = normalize(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
        val w = maxOf(minW, if (maxW > 0 && maxW < 2000) maxW else 0)
        val h = maxOf(minH, if (maxH > 0 && maxH < 2000) maxH else 0)
        if (w <= 0 && h <= 0) return Size.BIG
        return when {
            w >= 300 && h >= 300 -> Size.BIG
            w >= 170 && h >= 170 -> Size.BIG
            w >= 170 -> Size.WIDE
            h >= 170 -> Size.TALL
            w >= 100 || h >= 100 -> Size.TINY
            else -> Size.MICRO
        }
    }

    /**
     * 行控件 id 表（顺序固定）。
     *
     * 关键：**每一行的 4 个控件 id 必须各不相同**。
     * RemoteViews 通过 id 定位控件，如果 5 行共用同一个 `widget_row_name`，
     * 那么 `setTextViewText(R.id.widget_row_name, ...)` 每次都会写到最后一行，
     * 表现就是"小组件显示错乱/只有一行有内容/完全不可用"。
     * 所以这里按行号逐一定位布局里展开后的唯一 id。
     */
    private val ROW_CONTAINER_IDS = intArrayOf(
        R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3,
        R.id.widget_row_4, R.id.widget_row_5, R.id.widget_row_6,
    )
    private val ROW_BAR_IDS = intArrayOf(
        R.id.widget_bar_1, R.id.widget_bar_2, R.id.widget_bar_3,
        R.id.widget_bar_4, R.id.widget_bar_5, R.id.widget_bar_6,
    )
    private val ROW_TIME_IDS = intArrayOf(
        R.id.widget_time_1, R.id.widget_time_2, R.id.widget_time_3,
        R.id.widget_time_4, R.id.widget_time_5, R.id.widget_time_6,
    )
    private val ROW_NAME_IDS = intArrayOf(
        R.id.widget_name_1, R.id.widget_name_2, R.id.widget_name_3,
        R.id.widget_name_4, R.id.widget_name_5, R.id.widget_name_6,
    )
    private val ROW_LOC_IDS = intArrayOf(
        R.id.widget_loc_1, R.id.widget_loc_2, R.id.widget_loc_3,
        R.id.widget_loc_4, R.id.widget_loc_5, R.id.widget_loc_6,
    )

    const val MAX_ROWS = 6

    // ---------- 布局固定开销（dp），用于把可用高度换算成行高 ----------
    /** 根布局 padding（上下各一份，见 widget_schedule.xml 的 padding="10dp"） */
    private const val ROOT_PADDING_DP = 10
    /** 标题一行 + 它与分隔线之间的间距 */
    private const val TITLE_BLOCK_DP = 22
    /** 分隔线自身（1dp）+ 上下 margin（各 6dp） */
    private const val DIVIDER_BLOCK_DP = 13
    /** 「今天没有课」占位高度，见 widget_schedule.xml 的 48dp */
    private const val EMPTY_MIN_DP = 0
    /** 单行高度上下限，避免极端尺寸下高得离谱或挤成一条线 */
    private const val ROW_MIN_DP = 26
    private const val ROW_MAX_DP = 72

    fun build(context: Context, options: Bundle): RemoteViews =
        buildInternal(context, sizeOfDetailed(options), availableHeightDp(options))

    /**
     * 按**固定尺寸**构建 RemoteViews。
     *
     * 供 [FixedSizeWidgetProvider] 的四个尺寸档位使用：
     * 每个 provider 已经知道自己是什么尺寸，不需要再从 options 推断，
     * 避免桌面重启时 options 尚未写入导致误判成最小档。
     */
    fun buildFixed(context: Context, size: Size, options: Bundle? = null): RemoteViews =
        buildInternal(context, size, options?.let { availableHeightDp(it) } ?: size.nominalHeightDp())

    /**
     * 从 options 里取宿主给出的**可用高度（dp）**。
     *
     * 这是「小组件高度不随尺寸变化」的修复关键：早期版本根布局写死
     * `wrap_content` + `minHeight=110dp`，无论用户拉多大，卡片都只按内容
     * 高度渲染成一个矮条，看起来就是「尺寸档位修好了但高度不对」。
     * 现在按这个真实高度去分配每一行的高度，卡片就能填满用户选的格子。
     */
    private fun availableHeightDp(options: Bundle): Int {
        val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        val h = maxOf(normalize(minH), if (maxH in 1..2000) maxH else 0)
        return if (h > 0) h else 0
    }

    private fun buildInternal(context: Context, size: Size, availHeightDp: Int): RemoteViews {
        val repo = AppRepository(context)
        val settings = repo.loadSettings()

        val today = LocalDate.now()
        val week = currentWeek(settings.schoolStartDate, today)
        // 小组件只显示**本周实际开课**的课程
        val todayCourses = allCourses(context)
            .filter { it.weekday == today.dayOfWeek.value && it.activeInWeek(week) }
            .sortedBy { if (it.startSection > 0) it.startSection else it.startMinutes() }

        val rv = RemoteViews(context.packageName, R.layout.widget_schedule)

        rv.setTextViewText(
            R.id.widget_title,
            "${today.monthValue}/${today.dayOfMonth} 周${cn(today)} · 第 $week 周",
        )

        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        rv.setOnClickPendingIntent(R.id.widget_root, pi)

        // 按尺寸决定可容纳行数：1×1 放 1 行；2 格放 2~3 行；3~4 格放满 6 行
        val maxRows = when (size) {
            Size.MICRO -> 1
            Size.TINY -> 2
            Size.WIDE, Size.TALL -> 3
            Size.BIG -> MAX_ROWS
        }

        val nowMin = nowMinutes()
        val rows = todayCourses.take(maxRows)

        // ---------- 让卡片真正填满用户选的格子（v1.3.2 修复） ----------
        //
        // 早期根布局是 `wrap_content` + `minHeight=110dp`：宿主给多大都没用，
        // 卡片永远只按「内容高度」渲染，用户拉到 4×4 也只有一条矮卡片，
        // 表现就是「尺寸档位修好了、但高度不跟着变」。
        //
        // 现在按宿主给出的真实高度来分配：先扣掉标题 / 分隔线 / 内边距等
        // 固定开销，剩余高度**均分给可见行**，每行显式 setInt("setHeight", px)。
        // 这样无论 2×2 还是 4×4，卡片都会填满，行高随尺寸自然变化。
        val density = context.resources.displayMetrics.density
        val fixedOverheadDp =
            ROOT_PADDING_DP * 2 + TITLE_BLOCK_DP + DIVIDER_BLOCK_DP + EMPTY_MIN_DP
        val bodyDp = (availHeightDp - fixedOverheadDp).coerceAtLeast(0)
        val rowDp = if (rows.isEmpty()) 0
        else (bodyDp / rows.size).coerceIn(ROW_MIN_DP, ROW_MAX_DP)
        val rowPx = (rowDp * density).toInt()

        for (i in 0 until MAX_ROWS) {
            val c = rows.getOrNull(i)
            val rowId = ROW_CONTAINER_IDS[i]
            if (c == null) {
                rv.setViewVisibility(rowId, View.GONE)
                continue
            }
            rv.setViewVisibility(rowId, View.VISIBLE)
            // 显式设定行高，让行随可用高度伸缩
            rv.setInt(rowId, "setMinimumHeight", rowPx)
            val timeText = if (c.timeRange.isNotBlank()) c.timeRange
            else ClassTime.rangeOf(c.startSection, c.sectionCount)
            rv.setTextViewText(ROW_TIME_IDS[i], timeText)
            rv.setTextViewText(ROW_NAME_IDS[i], c.name)
            rv.setTextViewText(ROW_LOC_IDS[i], c.location.ifBlank { "" })

            // 进行中高亮（颜色条更饱和）
            val started = c.startMinutes() > 0 && c.startMinutes() <= nowMin
            val ended = c.endMinutes() > 0 && c.endMinutes() < nowMin
            val bg = if (started && !ended) "#FF6FB7E8" else "#FFB3D9F2"
            rv.setInt(ROW_BAR_IDS[i], "setBackgroundColor", Color.parseColor(bg))
            // 色条高度也跟着行高走，视觉上更协调
            rv.setInt(ROW_BAR_IDS[i], "setMinimumHeight",
                (rowDp * 0.55 * density).toInt().coerceAtLeast((14 * density).toInt()))
        }

        if (todayCourses.isEmpty()) {
            rv.setTextViewText(R.id.widget_empty, "今天没有课")
            rv.setViewVisibility(R.id.widget_empty, View.VISIBLE)
        } else {
            rv.setViewVisibility(R.id.widget_empty, View.GONE)
        }

        return rv
    }

    /**
     * 渲染失败时的兜底布局。
     *
     * 绝不能返回 `null` 或抛出异常 —— 那样桌面会永远停在「载入中」。
     * 这里退回最朴素的 `widget_schedule` 布局，只写标题与提示文字，
     * 所有行隐藏，保证桌面拿到一份**合法可渲染**的 RemoteViews。
     */
    fun fallback(context: Context, cause: Throwable?): RemoteViews {
        android.util.Log.e("ShzuWidget", "使用兜底布局", cause)
        return runCatching {
            RemoteViews(context.packageName, R.layout.widget_schedule).apply {
                setTextViewText(R.id.widget_title, "石大超级课表")
                for (id in ROW_CONTAINER_IDS) setViewVisibility(id, View.GONE)
                setTextViewText(R.id.widget_empty, "暂无数据")
                setViewVisibility(R.id.widget_empty, View.VISIBLE)
            }
        }.getOrElse {
            // 连兜底布局都构造不出来（理论上不会），退回系统最简单的占位
            RemoteViews(context.packageName, android.R.layout.simple_list_item_1).apply {
                setTextViewText(android.R.id.text1, "石大超级课表")
            }
        }
    }

    /**
     * 读取课表：优先读 settings.semester 对应的桶；
     * 若该桶为空（例如刚切换过学期），回退到任意一个有数据的学期桶，
     * 避免小组件因为桶名为空而显示"今天没有课"。
     */
    private fun allCourses(context: Context): List<com.shzu.superschedule.model.Course> {
        val repo = AppRepository(context)
        val settings = repo.loadSettings()
        val primary = repo.coursesOf(settings.semester)
        if (primary.isNotEmpty()) return primary
        // 回退：遍历学期列表找第一个有数据的
        val fallback = repo.loadSemesters().firstOrNull { repo.coursesOf(it.code).isNotEmpty() }
        return if (fallback != null) repo.coursesOf(fallback.code) else repo.loadCourses()
    }

    fun currentWeek(schoolStart: String, date: LocalDate): Int {
        if (schoolStart.isBlank()) return 1
        return try {
            val start = LocalDate.parse(schoolStart)
            val days = ChronoUnit.DAYS.between(start, date)
            if (days < 0) 1 else (days / 7).toInt() + 1
        } catch (_: Exception) {
            1
        }
    }

    private fun nowMinutes(): Int {
        val n = java.time.LocalTime.now()
        return n.hour * 60 + n.minute
    }

    private fun cn(d: LocalDate): String =
        listOf("一", "二", "三", "四", "五", "六", "日")[d.dayOfWeek.value - 1]
}
