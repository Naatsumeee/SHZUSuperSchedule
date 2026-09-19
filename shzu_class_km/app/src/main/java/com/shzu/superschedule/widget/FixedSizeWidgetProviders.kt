package com.shzu.superschedule.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.shzu.superschedule.MainActivity

/**
 * 小组件尺寸档位（多个 provider 共用同一套渲染逻辑）。
 *
 * ## 为什么需要好几个 Provider 类
 *
 * MIUI 的小部件选择器把「每个 `<receiver>`」当作一个独立条目展示。
 * 系统自带的小爱课程表就是用 4 个 provider 提供 4 个固定尺寸档位的。
 * 因此本应用也拆成 4 个档位，让用户在列表里直接看到并选择：
 *
 * | Provider            | 尺寸 | 最多显示 |
 * |---------------------|------|----------|
 * | [ScheduleWidgetProvider] | 2×2 | 2 行 |
 * | [WideWidgetProvider]     | 4×2 | 3 行 |
 * | [TallWidgetProvider]     | 2×4 | 3 行 |
 * | [BigWidgetProvider]      | 4×4 | 6 行 |
 *
 * 每个子类只需要提供一个**固定尺寸**（不依赖 options 推断），
 * 渲染逻辑完全复用 [WidgetRenderer.buildFixed]。
 */

/** 公共基类：把固定尺寸注入渲染流程 */
abstract class FixedSizeWidgetProvider : AppWidgetProvider() {

    /** 该 provider 对应的固定尺寸档位 */
    protected abstract val fixedSize: WidgetRenderer.Size

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { push(context, appWidgetManager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        push(context, appWidgetManager, appWidgetId)
    }

    /**
     * 推送一次 RemoteViews。
     * 渲染异常时退回兜底布局，避免桌面永远停在「载入中」（详见 provider 类的注释）。
     */
    protected fun push(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
    ) {
        // 取一次 options：尺寸档位虽然固定，但**高度**要按宿主实际给的
        // 格子大小来分配，才能让卡片填满（否则永远是一条矮卡片）。
        val opts = runCatching { appWidgetManager.getAppWidgetOptions(appWidgetId) }
            .getOrElse { android.os.Bundle() }

        val rv = runCatching { WidgetRenderer.buildFixed(context, fixedSize, opts) }
            .onFailure { android.util.Log.e("ShzuWidget", "buildFixed failed id=$appWidgetId", it) }
            .getOrElse { WidgetRenderer.fallback(context, it) }

        // 与 ScheduleWidgetProvider.push 一致：推送前先自检膨胀，
        // 让「白名单外控件」这类问题在本进程日志里直接暴露（详见那边的注释）。
        runCatching { rv.apply(context, android.widget.FrameLayout(context)) }
            .onFailure { android.util.Log.e("ShzuWidget", "RemoteViews 自检膨胀失败 id=$appWidgetId", it) }
            .onSuccess { android.util.Log.i("ShzuWidget", "RemoteViews 自检通过 id=$appWidgetId") }

        runCatching { appWidgetManager.updateAppWidget(appWidgetId, rv) }
            .onFailure { android.util.Log.e("ShzuWidget", "update failed id=$appWidgetId", it) }
    }
}

/** 2×2 小尺寸 */
class SmallWidgetProvider : FixedSizeWidgetProvider() {
    override val fixedSize = WidgetRenderer.Size.TINY
}

/** 4×2 宽扁 */
class WideWidgetProvider : FixedSizeWidgetProvider() {
    override val fixedSize = WidgetRenderer.Size.WIDE
}

/** 2×4 高瘦 */
class TallWidgetProvider : FixedSizeWidgetProvider() {
    override val fixedSize = WidgetRenderer.Size.TALL
}

/** 4×4 大尺寸 */
class BigWidgetProvider : FixedSizeWidgetProvider() {
    override val fixedSize = WidgetRenderer.Size.BIG
}
