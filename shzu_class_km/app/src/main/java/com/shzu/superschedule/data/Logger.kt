package com.shzu.superschedule.data

import android.util.Log

/**
 * 解析器等**纯逻辑模块**的日志出口。
 *
 * ## 为什么不让这些模块直接调 `android.util.Log`
 *
 * `JwglQueryParser` 里的逻辑（多层表头铺列、占位行过滤、杂散表头补名）
 * 全是无状态的字符串/HTML 处理，**与 Android 无关**，本该能在普通 JVM 上跑单元测试。
 * 但只要它 `import android.util.Log`，JVM 测试一调用就抛
 * `RuntimeException: Stub!`（android.jar 里的方法体全是占位实现），
 * 于是这些最容易出错、也最该被测的逻辑反倒无法测试 —— 历史上
 * `repairStrayHeader` 漏接调用点、`0` 占位值被当成分数，都是这么漏出去的。
 *
 * 所以把日志收口到这一个接口：生产环境用 [LogcatLogger]（行为与直接调 `Log` 一致），
 * 单元测试用 [NoopLogger]（或自定义实现来断言"该打的日志打了吗"）。
 *
 * ## 契约
 *
 * - **默认实现必须是 [NoopLogger]**：这样测试**不装 logger 也能直接跑**，
 *   少一个"忘了初始化导致测试报错"的坑。
 * - [install] 只应在 App 启动时调用一次（`MainActivity` / `Application`）。
 *
 * ⚠️ 这里**不能**改成直接转发给 [AppLog]：那个类会写文件、还会装全局崩溃处理器，
 * 解析过程中的高频 `Log.d` 会把日志文件迅速刷满，反而冲掉真正重要的记录。
 */
interface Logger {
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String)

    companion object {
        /** 当前生效的 logger。默认空实现，测试无需任何初始化。 */
        @Volatile
        private var current: Logger = NoopLogger

        /** 生产环境在 App 启动时调用一次（见 MainActivity） */
        fun install(logger: Logger) {
            current = logger
        }

        fun d(tag: String, msg: String) = current.d(tag, msg)

        fun i(tag: String, msg: String) = current.i(tag, msg)

        fun w(tag: String, msg: String) = current.w(tag, msg)
    }
}

/** 丢弃全部日志。JVM 测试与未初始化场景的默认实现。 */
object NoopLogger : Logger {
    override fun d(tag: String, msg: String) = Unit
    override fun i(tag: String, msg: String) = Unit
    override fun w(tag: String, msg: String) = Unit
}

/**
 * 真机实现：转发给 `android.util.Log`。
 *
 * 刻意**不**同时写进 [AppLog] 的文件日志 —— 见 [Logger] 的说明，
 * 解析日志量大且只在排查时有用，走 logcat 即可。
 */
object LogcatLogger : Logger {
    override fun d(tag: String, msg: String) {
        Log.d(tag, msg)
    }

    override fun i(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    override fun w(tag: String, msg: String) {
        Log.w(tag, msg)
    }
}
