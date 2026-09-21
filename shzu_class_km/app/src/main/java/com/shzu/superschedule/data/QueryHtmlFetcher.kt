package com.shzu.superschedule.data

/**
 * 「取一个教务页面 HTML」的能力抽象。
 *
 * ## 为什么要有这层抽象
 *
 * 一开始后台抓取是直接用 `HttpURLConnection` 带着 `CookieManager` 里读到的 Cookie 去请求的，
 * 结果在真机上**全部被判未登录**：拿到的永远是 164 字节、无 `<title>` 的空壳跳转页，
 * 而**同一个会话在 WebView 里明明完全有效**（能正常打开考试安排查询页）。
 * 诊断日志显示 CookieManager 里只有 `jsxsd` / `SERVERID` / `bzb_njw` 三项、
 * **没有 `JSESSIONID`** —— 请求层面的差异迟迟定位不到。
 *
 * 与其继续和 Cookie 传递死磕，不如**让 WebView 自己去请求**：
 * 由浏览器发起的 `fetch` 天然带着正确的 Cookie、UA、Referer，
 * 我们只管把返回的 HTML 拿回来解析。
 *
 * 于是把「取 HTML」抽成接口，`JwglQueryFetcher` 只依赖这个接口，
 * 具体实现（WebView / 其它方式）由 UI 层在启动时注入。
 */
interface QueryHtmlFetcher {

    /**
     * 请求一个教务页面并返回其 HTML。
     *
     * @param path 以 `/` 开头的站内路径（如 `/jsxsd/xsks/xsksap_query`）。
     * @param form 非 null 时改用 POST，并按 `application/x-www-form-urlencoded` 编码。
     *             查询学期成绩/考试安排都要走这一步（强智把学期放在表单里）。
     * @param menuCall 主框架里打开该页的 `kjcdShow(...)` 实参。
     *             **强智的查询页只能在主框架的子 iframe 里打开** —— 直接做顶层导航
     *             （`Sec-Fetch-Dest: document`）会被判「请先登录系统」。
     *             传了就优先用它来打开，比「找菜单项再点击」可靠（菜单是异步渲染的）。
     * @return 页面 HTML；请求失败或超时返回 null。
     */
    suspend fun html(
        path: String,
        form: Map<String, String>? = null,
        menuCall: List<String> = emptyList(),
    ): String?
}
