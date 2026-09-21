package com.shzu.superschedule.data

/**
 * 「取一个教务页面 HTML」的能力抽象。
 *
 * ## 为什么要有这层抽象
 *
 * 一开始后台抓取是直接用 `HttpURLConnection` 带着 `CookieManager` 里读到的 Cookie 去请求的，
 * 结果在真机上**全部被判未登录**：拿到的永远是 164 字节、无 `<title>` 的空壳跳转页，
 * 而**同一个会话在 WebView 里明明完全有效**（能正常打开考试安排查询页）。
 * 诊断日志显示 CookieManager 里只有 `jsxsd` / `SERVERID` / `bznjw` 三项、
 * **没有 `JSESSIONID`** —— 请求层面的差异迟迟定位不到。
 *
 * 与其继续和 Cookie 传递死磕，不如**让 WebView 自己去请求**：
 * 由浏览器发起的请求天然带着正确的 Cookie、UA、Referer，
 * 我们只管把返回的 HTML 拿回来解析。
 *
 * 于是把「取 HTML」抽成接口，`JwglQueryFetcher` 只依赖这个接口，
 * 具体实现（WebView / 其它方式）由 UI 层在启动时注入。
 */
interface QueryHtmlFetcher {

    /**
     * 打开一个教务查询页并返回其 HTML。
     *
     * ## 为什么用 `semester` 而不是「表单字段表」
     *
     * 早先这里收的是一张 `Map<String, String>` 表单字段表，由 Kotlin 侧
     * 把**所有 iframe 的 HTML 拼起来**用 Jsoup 解析得到。实测这是错的：
     * 主框架里常驻着「修改个人信息」等 iframe，`doc.select("form").first{}`
     * 抓到的往往是它们的表单，提交了当然什么也不会发生
     * （日志里 GET 与 POST 返回长度一字不差，就是铁证）。
     *
     * 现在改成只传**目标学期**，由实现方在**正确的那个 iframe 内部**
     * 找到学期控件、写入、再点「查询」。定位 iframe 的依据是 [path]。
     *
     * @param path 以 `/` 开头的站内路径（如 `/jsxsd/xsks/xsksap_query`）。
     *             **路径最后一段同时用作子 iframe 的识别特征** ——
     *             强智每个查询页都独占一个 iframe，其 `src` 一定包含这个段。
     * @param semester 目标学期代码（如 `2026-2027-1`）。传 null 表示这次不选学期、
     *            只打开页面（等级考试这类与学期无关的查询）。
     *            若目标学期在该页下拉框里**不存在**，实现方不得提交，
     *            直接返回打开后的页面（调用方会判成「未查询到数据」）。
     * @param menuCall 主框架里打开该页的 `kjcdShow(...)` 实参。
     *             **强智的查询页只能在主框架的子 iframe 里打开** —— 直接做顶层导航
     *             （`Sec-Fetch-Dest: document`）会被判「请先登录系统」。
     *             传了就优先用它来打开，比「找菜单项再点击」可靠（菜单是异步渲染的）。
     * @return 页面 HTML；请求失败或超时返回 null。
     */
    suspend fun html(
        path: String,
        semester: String? = null,
        menuCall: List<String> = emptyList(),
    ): String?

    /**
     * 诊断用：把教务主框架的菜单结构（文本 / href / onclick）写进日志。
     *
     * 默认空实现 —— 只有 WebView 实现能拿到菜单，其它实现忽略即可。
     */
    suspend fun dumpMenu() {}
}
