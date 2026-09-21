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
        sanitize(json.decodeFromString<Archive>(f.readText()))
    }.getOrElse {
        AppLog.w("QueryStore", "读取查询存档失败：${it.message}")
        Archive()
    }

    /**
     * 清掉存档里历史遗留的「未查询到数据」占位行。
     *
     * 为什么要在这里做：强智在结果为空时会给一张只有一行的表，那行写着
     * 「未查询到数据」。早期版本的解析器没滤它，于是「考试安排本来没有」
     * 被当成 1 条记录存了下来。清在**读取**这一步，老用户不点刷新也能立刻看到正确结果，
     * 不用等下一次抓取覆盖。
     *
     * 只删行、不删条目：条目留着（0 行），界面才会显示「未查询到数据」，
     * 而不是「尚未获取」——这两者对用户的意义不一样。
     */
    private fun sanitize(archive: Archive): Archive {
        var dropped = 0
        val cleaned = archive.entries.map { t ->
            val rows = t.rows.filterNot { JwglQueryParser.isPlaceholderRow(it) }
            if (rows.size == t.rows.size) {
                t
            } else {
                dropped += t.rows.size - rows.size
                t.copy(rows = rows)
            }
        }
        if (dropped > 0) {
            AppLog.i("QueryStore", "存档清理：移除 $dropped 行「未查询到数据」占位记录")
        }
        return archive.copy(entries = cleaned)
    }

    /**
     * 保存存档。
     *
     * ⚠️ **先写临时文件再重命名，不要直接 `writeText`。**
     * 直接写是「截断 + 覆盖」：一次批量抓取要跑两三分钟，期间用户很容易切走或锁屏，
     * 应用被系统回收是常事。若恰好在写盘那一瞬被杀，原文件已被截断成半截 JSON，
     * 下次加载解析失败 → 整个存档归零。临时文件 + `renameTo` 是原子的，
     * 最坏情况只是丢掉这一次的新数据，旧数据完好。
     *
     * 同理，读取时若 `renameTo` 失败退化成直写，也只是兜底，不影响原子性前提。
     */
    fun save(context: Context, archive: Archive) {
        runCatching {
            val f = file(context)
            f.parentFile?.takeIf { !it.exists() }?.mkdirs()
            val text = json.encodeToString(archive)
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                // 某些实现下目标已存在时 renameTo 会失败，退一步：删掉再重命名
                f.delete()
                if (!tmp.renameTo(f)) {
                    f.writeText(text)
                    tmp.delete()
                }
            }
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
