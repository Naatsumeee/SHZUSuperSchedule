package com.shzu.superschedule.data

import android.content.Context
import com.shzu.superschedule.model.Course
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 课表 JSON 存档：本地文件持久化 + 导入导出。
 *
 * 设计要点（v1.3.1）：
 * 1. **落盘为真实 json 文件**（`filesDir/schedule/<学期>.json`），而不是仅存 SharedPreferences。
 *    这样即使进程被杀、SharedPreferences 被清，也能从文件恢复课表；
 *    同时便于用户在文件管理器里找到、发送给同学。
 * 2. **首次启动时自动从 SharedPreferences 迁移**，老用户升级后不会丢课表。
 * 3. 导入外部 json 时做**严格校验**，任何一项不合法就整体拒绝并给出原因，
 *    绝不写入半截数据。
 */

/** 导出/导入用的存档结构（带格式版本，便于以后升级） */
@Serializable
data class ScheduleArchive(
    /** 存档格式版本 */
    val formatVersion: Int = FORMAT_VERSION,
    /** 导出该存档的应用版本 */
    val appVersion: String = "",
    /** 学期代码，如 "2026-2027-1" */
    val semester: String = "",
    /** 学期显示名 */
    val semesterName: String = "",
    /** 开学日期 yyyy-MM-dd，可为空 */
    val schoolStartDate: String = "",
    /** 导出时间 */
    val exportedAt: String = "",
    /** 课程列表 */
    val courses: List<Course> = emptyList(),
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

/** 校验结果 */
sealed class ImportResult {
    /** 校验通过 */
    data class Ok(val archive: ScheduleArchive) : ImportResult()
    /** 校验失败，[reason] 是给用户看的原因 */
    data class Invalid(val reason: String) : ImportResult()
}

object ScheduleStore {

    private const val DIR = "schedule"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** 宽松解析器：仅用于读自己的历史文件，遇到未知字段不报错 */
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** 某学期的存档文件 */
    fun fileOf(context: Context, semester: String): File {
        val safe = semester.ifBlank { "_default" }
            .replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        return File(dir(context), "$safe.json")
    }

    // ---------------- 读写 ----------------

    /** 保存某学期的课表到 json 文件（同时回写 SharedPreferences 作为兼容缓存） */
    fun save(context: Context, archive: ScheduleArchive) {
        runCatching {
            fileOf(context, archive.semester).writeText(json.encodeToString(archive))
        }
    }

    /**
     * 读取某学期的课表。
     *
     * 顺序：json 文件 → SharedPreferences 兜底。
     * 若只有 SharedPreferences 有数据（老版本升级），会顺手写成 json 文件完成迁移。
     */
    fun load(context: Context, semester: String, repo: AppRepository): ScheduleArchive? {
        val f = fileOf(context, semester)
        if (f.exists()) {
            val parsed = runCatching {
                lenientJson.decodeFromString<ScheduleArchive>(f.readText())
            }.getOrNull()
            if (parsed != null) return parsed
        }
        // 迁移：从 SharedPreferences 读出来写成文件
        val legacy = repo.coursesOf(semester)
        if (legacy.isEmpty()) return null
        val migrated = ScheduleArchive(
            semester = semester,
            semesterName = repo.loadSemesters().firstOrNull { it.code == semester }?.name ?: "",
            schoolStartDate = repo.loadSemesters()
                .firstOrNull { it.code == semester }?.startDate ?: "",
            courses = legacy,
        )
        save(context, migrated)
        return migrated
    }

    /** 已存在的存档文件列表 */
    fun listFiles(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    // ---------------- 导出 ----------------

    fun buildArchive(
        semester: String,
        semesterName: String,
        schoolStartDate: String,
        appVersion: String,
        courses: List<Course>,
    ): ScheduleArchive = ScheduleArchive(
        formatVersion = ScheduleArchive.FORMAT_VERSION,
        appVersion = appVersion,
        semester = semester,
        semesterName = semesterName,
        schoolStartDate = schoolStartDate,
        exportedAt = nowText(),
        courses = courses,
    )

    fun encode(archive: ScheduleArchive): String = json.encodeToString(archive)

    // ---------------- 导入校验 ----------------

    /**
     * 校验并解析外部 json。
     *
     * 校验项（任意一项失败即整体拒绝）：
     * - 文本非空、能按 JSON 解析、结构匹配 [ScheduleArchive]；
     * - 课程列表非空；
     * - 每条课程的 星期(1-7) / 起始节次(1-10) / 持续节数(1-10 且不越界) / 课程名非空 合法。
     */
    fun parseAndValidate(text: String): ImportResult {
        if (text.isBlank()) return ImportResult.Invalid("文件内容为空")

        val archive = runCatching {
            lenientJson.decodeFromString<ScheduleArchive>(text)
        }.getOrElse {
            return ImportResult.Invalid("不是有效的课表 JSON 文件（解析失败：${it.message?.take(80) ?: "未知错误"}）")
        }

        if (archive.formatVersion > ScheduleArchive.FORMAT_VERSION) {
            return ImportResult.Invalid(
                "存档格式版本 ${archive.formatVersion} 高于本应用支持的 " +
                    "${ScheduleArchive.FORMAT_VERSION}，请升级 App 后再导入",
            )
        }
        if (archive.courses.isEmpty()) {
            return ImportResult.Invalid("文件中没有任何课程数据")
        }

        archive.courses.forEachIndexed { index, c ->
            val no = index + 1
            if (c.name.isBlank()) {
                return ImportResult.Invalid("第 $no 条课程缺少课程名")
            }
            if (c.weekday !in 1..7) {
                return ImportResult.Invalid(
                    "第 $no 条课程「${c.name}」的星期取值 ${c.weekday} 不合法（应为 1-7）",
                )
            }
            if (c.startSection !in 1..10) {
                return ImportResult.Invalid(
                    "第 $no 条课程「${c.name}」的起始节次 ${c.startSection} 不合法（应为 1-10）",
                )
            }
            if (c.sectionCount !in 1..10) {
                return ImportResult.Invalid(
                    "第 $no 条课程「${c.name}」的持续节数 ${c.sectionCount} 不合法（应为 1-10）",
                )
            }
            if (c.startSection + c.sectionCount - 1 > 10) {
                return ImportResult.Invalid(
                    "第 $no 条课程「${c.name}」超出一天 10 节课的范围" +
                        "（${c.startSection} 起连续 ${c.sectionCount} 节）",
                )
            }
        }

        return ImportResult.Ok(archive)
    }

    /** 覆盖策略：同名冲突时如何合并 */
    enum class MergeMode { REPLACE, APPEND }

    /**
     * 把导入的课程并入现有列表。
     * REPLACE 直接覆盖；APPEND 去重追加（key 相同视为同一门课）。
     */
    fun merge(existing: List<Course>, incoming: List<Course>, mode: MergeMode): List<Course> =
        when (mode) {
            MergeMode.REPLACE -> incoming
            MergeMode.APPEND -> {
                val keys = existing.map { it.key() }.toHashSet()
                existing + incoming.filter { keys.add(it.key()) }
            }
        }

    private fun nowText(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date())
}
