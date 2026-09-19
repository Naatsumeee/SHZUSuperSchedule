package com.shzu.superschedule.data

import android.content.Context
import com.shzu.superschedule.model.AppSettings
import com.shzu.superschedule.model.Course
import com.shzu.superschedule.model.SemesterEntry
import kotlinx.serialization.json.Json

/** 本地存储：课表（按学期分桶）+ 设置 */
class AppRepository(context: Context) {

    private val sp = context.getSharedPreferences("shzu_class", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ---------- 学期列表 ----------

    fun loadSemesters(): List<SemesterEntry> {
        val raw = sp.getString(KEY_SEMESTERS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<SemesterEntry>>(raw) }
            .getOrDefault(emptyList())
    }

    fun saveSemesters(list: List<SemesterEntry>) {
        // 重新计算 hasData 再落盘
        val fixed = list.map { it.copy(hasData = coursesOf(it.code).isNotEmpty()) }
        sp.edit().putString(KEY_SEMESTERS, json.encodeToString(fixed)).apply()
    }

    /** 合并新学期（保留已有数据标记） */
    fun mergeSemesters(incoming: List<SemesterEntry>) {
        val existing = loadSemesters().associateBy { it.code }
        val merged = incoming.map { inc ->
            val old = existing[inc.code]
            inc.copy(
                hasData = old?.hasData == true || coursesOf(inc.code).isNotEmpty(),
                startDate = inc.startDate.ifBlank { old?.startDate ?: "" },
            )
        }.sortedByDescending { it.code }
        sp.edit().putString(KEY_SEMESTERS, json.encodeToString(merged)).apply()
    }

    // ---------- 课表（按学期） ----------

    /** 读取某学期课表；semester 为空则读"当前"桶 */
    fun coursesOf(semester: String): List<Course> {
        val raw = sp.getString(coursesKey(semester), null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Course>>(raw) }.getOrDefault(emptyList())
    }

    fun saveCourses(semester: String, courses: List<Course>) {
        sp.edit().putString(coursesKey(semester), json.encodeToString(courses)).apply()
        // 同步刷新该学期的 hasData 标记
        val list = loadSemesters().map {
            if (it.code == semester) it.copy(hasData = courses.isNotEmpty()) else it
        }
        sp.edit().putString(KEY_SEMESTERS, json.encodeToString(list)).apply()
    }

    /** 兼容旧接口：读写默认桶 */
    fun loadCourses(): List<Course> = coursesOf("")
    fun saveCourses(courses: List<Course>) = saveCourses("", courses)

    // ---------- 设置 ----------

    fun loadSettings(): AppSettings {
        val raw = sp.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return runCatching { json.decodeFromString<AppSettings>(raw) }.getOrDefault(AppSettings())
    }

    fun saveSettings(settings: AppSettings) {
        sp.edit().putString(KEY_SETTINGS, json.encodeToString(settings)).apply()
    }

    fun clear() {
        sp.edit().clear().apply()
    }

    private fun coursesKey(semester: String): String =
        if (semester.isBlank()) KEY_COURSES_LEGACY else "$KEY_COURSES_PREFIX$semester"

    private companion object {
        const val KEY_COURSES_LEGACY = "courses"
        const val KEY_COURSES_PREFIX = "courses_"
        const val KEY_SETTINGS = "settings"
        const val KEY_SEMESTERS = "semesters"
    }
}
