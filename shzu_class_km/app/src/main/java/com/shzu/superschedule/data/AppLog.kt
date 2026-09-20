package com.shzu.superschedule.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 应用运行日志。
 *
 * ## 为什么需要它
 *
 * 真机上出的问题（尤其**闪退**）没有任何现场 —— 用户只能说「它崩了」，
 * 而教务系统返回的是什么、WebView 报了什么错、异常栈在哪一行，全都看不到。
 * 这个类把所有关键动作落成文本，崩溃后重启 App 仍能读到。
 *
 * ## 两条通道
 *
 * 1. **内存环形缓冲**（最近 [MAX_MEMORY] 条）—— 日志页即时展示，不碰磁盘；
 * 2. **文件** `files/logs/app.log` —— 崩溃、被杀进程后依然保留。
 *
 * ## 崩溃捕获
 *
 * 接管 `Thread.setDefaultUncaughtExceptionHandler`，把异常类名、消息、
 * 完整栈写进日志，然后再交给系统默认处理器（保证系统仍能弹「应用已停止」）。
 *
 * 入口：设置 → 关于 → **连点「作者」7 下**。
 */
object AppLog {

    private const val MAX_MEMORY = 800
    private const val FILE_NAME = "app.log"
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val KEEP_LINES_ON_ROTATE = 600

    private val buffer = ConcurrentLinkedDeque<String>()
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var installed = false

    /** 日志文件；未 [init] 时返回 null */
    val logFile: File?
        get() = appContext?.let { File(File(it.filesDir, "logs"), FILE_NAME) }

    /** 在 Activity/Application 启动时调用一次（重复调用无副作用） */
    fun init(context: Context) {
        if (installed) return
        installed = true
        appContext = context.applicationContext
        installCrashHandler()

        // 只取 versionName：versionCode 在 Java 侧已废弃，为它引入 compat 不值当
        val ver = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"

        i("AppLog", "===== 日志启动 · 版本 $ver =====")
    }

    // ---------------- 写入 ----------------

    fun d(tag: String, msg: String) = append("D", tag, msg)

    fun i(tag: String, msg: String) = append("I", tag, msg)

    fun w(tag: String, msg: String) = append("W", tag, msg)

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        append("E", tag, if (tr == null) msg else "$msg\n${Log.getStackTraceString(tr)}")
    }

    // ---------------- 读取 ----------------

    /** 内存里的最近若干条（日志页默认读这个，最快） */
    fun recent(limit: Int = MAX_MEMORY): List<String> = buffer.toList().takeLast(limit)

    /** 完整文件内容（包含 App 重启前的记录）；文件不可用时退回内存 */
    fun readAll(): List<String> {
        val f = logFile ?: return recent()
        return runCatching {
            if (f.exists()) f.readLines() else recent()
        }.getOrElse { recent() }
    }

    /** 供「复制」按钮使用的纯文本 */
    fun asText(): String = readAll().joinToString("\n")

    fun clear() {
        buffer.clear()
        runCatching { logFile?.takeIf { it.exists() }?.writeText("") }
        i("AppLog", "日志已清空")
    }

    /** 是否有实质性内容（日志页用来区分「空」与「有内容」） */
    fun isEmpty(): Boolean = buffer.isEmpty() && (logFile?.length() ?: 0L) == 0L

    // ---------------- 内部 ----------------

    private fun append(level: String, tag: String, msg: String) {
        val line = "${timeFmt.format(Date())} $level/$tag: $msg"
        runCatching {
            when (level) {
                "E" -> Log.e(tag, msg)
                "W" -> Log.w(tag, msg)
                "D" -> Log.d(tag, msg)
                else -> Log.i(tag, msg)
            }
        }
        buffer.addLast(line)
        while (buffer.size > MAX_MEMORY) {
            buffer.pollFirst() ?: break
        }
        writeFile(line)
    }

    /**
     * 同步写文件。
     *
     * 刻意不用异步线程：日志量很小（内部存储追加一行 < 1ms），
     * 而**崩溃时进程马上要退出，异步队列里的内容会直接丢掉** ——
     * 那恰恰是最需要留下的那一条。
     */
    private fun writeFile(line: String) {
        val f = logFile ?: return
        runCatching {
            f.parentFile?.takeIf { !it.exists() }?.mkdirs()
            if (f.length() > MAX_FILE_BYTES) {
                val keep = f.readLines().takeLast(KEEP_LINES_ON_ROTATE)
                f.writeText(keep.joinToString("\n") + "\n")
            }
            f.appendText(line + "\n")
        }
    }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, ex ->
            runCatching {
                append(
                    "E",
                    "CRASH",
                    "线程「${t.name}」未捕获异常：${ex.javaClass.name}: ${ex.message}\n" +
                        Log.getStackTraceString(ex),
                )
            }
            // 交回系统默认处理，保证「应用已停止」仍会弹出
            prev?.uncaughtException(t, ex)
        }
    }
}
