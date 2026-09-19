# 1.0 — WebView 修复版

| 项目 | 值 |
|------|-----|
| APK | `石大课表_MiuiX首版_WebView修复.apk`（14.57 MB） |
| 包名 | `com.shzu.classlist` |
| versionName / versionCode | `1.0` / `1` |
| 应用名 | 石大课表 |
| 构建时间 | 2026-09-18 22:08 |
| compileSdk / targetSdk | 37 / 36 |
| 相对上一版 | 只改 `ui/ImportPage.kt`，BUILD SUCCESSFUL in 40s |

## 定位

MiuiX 首版的**紧急补丁**。首版出来后 WebView 表现不正常：
登录页显示不对、点不进「学期理论课表」页。本版只重写了 `ui/ImportPage.kt`，
其余不动，构建脚本同步落盘为 `build_km.ps1`（后面所有 `build_km_v*.ps1` 的模板）。

## 修复清单（`ui/ImportPage.kt`）

1. **UA**：显式 `settings.userAgentString = MOBILE_UA`
   （Android SM-G9910 / Chrome 127 Mobile）→ 命中移动版登录页。
2. **缩放与视口**：`useWideViewPort=true`、`loadWithOverviewMode=true`、
   `setSupportZoom(true)`、`builtInZoomControls=true`、`displayZoomControls=false`、
   `textZoom=100`。
3. **跨域 Cookie**：`CookieManager.setAcceptThirdPartyCookies(webView, true)` +
   `setAcceptCookie(true)` → 保证 `authserver` ↔ `jwgl` 的 CAS 登录态能串起来。
4. **多窗口**：`javaScriptCanOpenWindowsAutomatically=true` +
   `setSupportMultipleWindows(true)` + `onCreateWindow` 复用当前 WebView
   （解决 `target=_blank` 的链接点了没反应）。
5. **缓存与文件访问**：`cacheMode = LOAD_NO_CACHE`，开 `allowFileAccess` / `allowContentAccess`。
6. **老页面兜底**：`onPageFinished` 时注入 viewport meta，给没有做响应式的老页面兜底。
7. **UI 新增**：
   - `① 打开「学期理论课表」`——直达 `https://jwgl.shzu.edu.cn/jsxsd/xskb/xskb_list.do`；
   - `② 导入当前页面课表`；
   - **电脑版 / 手机版 UA 一键切换** + 刷新按钮；
   - 顶部显示 WebView 内核的 Chrome 版本号（排查时很有用）。

## 遗留经验

- 强智教务这套老页面**同时存在桌面版和移动版两套模板**，靠 UA 分流。
  后面「导入课表要切桌面 UA 让宽表格完整显示」就是从这里来的经验。
- 跨域 SSO（authserver → jwgl）必须开第三方 Cookie，否则登录态串不起来。
