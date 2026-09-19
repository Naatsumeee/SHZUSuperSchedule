package com.shzu.superschedule.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.shzu.superschedule.R
import com.shzu.superschedule.model.Course

/**
 * 通知相关的小工具。
 *
 * 只依赖平台 API（不引入 androidx.core），因为 `minSdk = 24`，
 * API 26 以下的机型用旧版 `Notification.Builder` 构造器即可。
 */
object Notifier {

    /** 上课提醒的通知渠道（Android 8.0+ 必须建渠道，否则通知会被静默丢弃） */
    const val CHANNEL_REMINDER = "shzu_reminder"

    /** 测试通知固定 id，重复点击只覆盖同一条 */
    private const val TEST_NOTIFICATION_ID = 9001

    /**
     * 确保通知渠道存在。
     * 幂等，可以每次发通知前都调。
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (nm.getNotificationChannel(CHANNEL_REMINDER) != null) return
        val channel = NotificationChannel(
            CHANNEL_REMINDER,
            "上课提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "在课程开始前推送提醒"
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }

    /** 系统层面的通知开关是否打开（API 24+ 平台方法，无需 androidx） */
    fun notificationsEnabled(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        return nm.areNotificationsEnabled()
    }

    /** Android 13+ 需要动态授予 POST_NOTIFICATIONS 权限 */
    fun needsPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** 运行时通知权限是否已授予（13 以下恒为 true） */
    fun hasPermission(context: Context): Boolean {
        if (!needsPermission()) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 是否可以真正发出通知 */
    fun canNotify(context: Context): Boolean =
        hasPermission(context) && notificationsEnabled(context)

    /**
     * 权限状态的可读描述。
     * @return (是否可以发通知, 给用户看的说明)
     */
    fun statusText(context: Context): Pair<Boolean, String> = when {
        !hasPermission(context) ->
            false to "尚未授予通知权限，上课提醒无法生效"
        !notificationsEnabled(context) ->
            false to "系统已关闭本应用的通知，请到系统设置里开启"
        else ->
            true to "通知权限正常，上课提醒可以正常工作"
    }

    /**
     * 发送一条测试通知。
     *
     * 传入课表时**随机挑一节课**，按真实上课提醒的格式（课名 / 周几 / 地点 / 节次 / 时间）
     * 填充内容，方便直接检查通知在通知栏里的实际观感；课表为空时退回通用文案。
     *
     * @return true 表示已提交给系统
     */
    fun sendTest(context: Context, courses: List<Course> = emptyList()): Boolean {
        if (!canNotify(context)) return false
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_REMINDER)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val course = courses.randomOrNull()
        val title = if (course == null) "上课提醒 · 通知测试" else "上课提醒 · ${course.name}"
        val text = if (course == null) {
            GENERIC_TEST_TEXT
        } else {
            listOf(
                weekdayText(course.weekday),
                course.location,
                course.sectionName,
                course.timeRange,
            ).filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { GENERIC_TEST_TEXT }
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            // 通知栏右上角会显示「通知测试」，提醒用户这不是真的课前提醒
            .setSubText("通知测试")
            .setAutoCancel(true)
            .build()
        return runCatching {
            nm.notify(TEST_NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    private const val GENERIC_TEST_TEXT = "看到这条通知说明权限正常，课前提醒可以正常工作。"

    private val WEEKDAY_NAMES =
        arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    private fun weekdayText(weekday: Int): String =
        WEEKDAY_NAMES.getOrNull(weekday - 1).orEmpty()
}
