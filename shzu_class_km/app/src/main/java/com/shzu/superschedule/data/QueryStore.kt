package com.shzu.superschedule.data

import android.content.Context
import com.shzu.superschedule.model.QueryTable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 查询结果持久化（考试安排 / 课程成绩 / 等级考试成绩）。
 *
 * 存成单个 json（`files/queries.json`），内部就是一个 entries 数组，
 * 每项自带宽 [QueryTable.kind] 与 [QueryTable.semester]。
 *
 * 为什么用单文件而不是「每学期一个文件」：查询结果总量很小
 * （一学期几十行），单文件便于整体读取、整体替换，也少一套文件命名规则。
 */
object QueryStore {

    private const val FILE_NAME = "queries.json"
    private const val CURRENT_VERSION = 1

    @Serializable
    data class Archive(
        val version: Int = CURRENT_VERSION,
        val updatedAt: String = "",
        val entries: List<QueryTable> = emptyList(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun load(context: Context): Archive = runCatching {
        val f = file(context)
        if (!f.exists()) return Archive()
        json.decodeFromString<Archive>(f.readText())
    }.getOrElse {
        AppLog.w("QueryStore", "读取查询存档失败：${it.message}")
        Archive()
    }

    fun save(context: Context, archive: Archive) {
        runCatching {
            val f = file(context)
            f.parentFile?.takeIf { !it.exists() }?.mkdirs()
            f.writeText(json.encodeToString(archive))
            AppLog.i("QueryStore", "查询存档已保存：${archive.entries.size} 项")
        }.onFailure {
            AppLog.e("QueryStore", "保存查询存档失败", it)
        }
    }

    /** 当前时间戳，用于 [Archive.updatedAt] 与 [QueryTable.fetchedAt] */
    fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

    /**
     * 合并：新抓到的 [incoming] 覆盖同 (kind, semester) 的旧数据，其余保留。
     * 这样「只刷新某一个学期」不会把别的学期结果冲掉。
     */
    fun merge(existing: List<QueryTable>, incoming: List<QueryTable>): List<QueryTable> {
        val replaced = incoming.map { it.kind to it.semester }.toSet()
        return existing.filterNot { (it.kind to it.semester) in replaced } + incoming
    }
}
