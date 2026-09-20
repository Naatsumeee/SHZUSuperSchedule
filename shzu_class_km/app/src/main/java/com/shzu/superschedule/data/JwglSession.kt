package com.shzu.superschedule.data

import android.webkit.CookieManager

/**
 * 教务系统会话工具（地址常量 + Cookie 读取）。
 *
 * ## 为什么 Cookie 要这样取（踩过的坑）
 *
 * `CookieManager.getCookie(url)` **只返回 path 与传入 url 匹配的 Cookie**。
 * 教务的登录会话是 `JSESSIONID`，其 path 是 **`/jsxsd`**（不是 `/`），
 * 所以必须用带 `/jsxsd` 前缀的完整 URL 去取，否则拿到的是空串 ——
 * 请求会以「未登录」身份发出、被服务端重定向到登录页，
 * 解析出来的结果当然是空。
 *
 * 这里把所有可能承载会话的域/路径都取一遍再合并去重，保证不漏。
 */
object JwglSession {

    const val BASE = "https://jwgl.shzu.edu.cn"

    /** 「学期理论课表」页面 */
    const val TIMETABLE_URL = "$BASE/jsxsd/xskb/xskb_list.do"

    /** 教务主框架页（用作 Referer） */
    const val MAIN_FRAME_URL = "$BASE/jsxsd/framework/xsMain.jsp"

    /**
     * 桌面端 UA。
     * 查询结果是宽表格，桌面版才显示得完整；后台抓取也用它，
     * 保证拿到的 HTML 与用户在「电脑版」下看到的一致。
     */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

    /** 合并去重后的 Cookie 头；未登录时返回 null */
    fun cookie(): String? {
        val cm = CookieManager.getInstance()
        val candidates = listOf(
            TIMETABLE_URL,
            "$BASE/jsxsd/",
            "$BASE/",
        )
        val merged = LinkedHashMap<String, String>()
        for (url in candidates) {
            val raw = runCatching { cm.getCookie(url) }.getOrNull().orEmpty()
            raw.split(';').forEach { part ->
                val kv = part.trim()
                if (kv.isEmpty()) return@forEach
                val name = kv.substringBefore('=').trim()
                if (name.isEmpty()) return@forEach
                if (!merged.containsKey(name)) merged[name] = kv
            }
        }
        return merged.values.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }
}
