# 1.0 — Kotlin + Compose + MiuiX 首版（技术栈重写）

| 项目 | 值 |
|------|-----|
| APK | `石大课表_MiuiX首版.apk`（14.57 MB） |
| 包名 | `com.shzu.classlist` |
| versionName / versionCode | `1.0` / `1` |
| 应用名 | 石大课表 |
| 构建时间 | 2026-09-18 21:57 |
| compileSdk / targetSdk | 37 / 36 |
| 技术栈 | Kotlin + Jetpack Compose（Compose Multiplatform 1.11.1）+ MiuiX 0.9.3 + Jsoup |

## 定位

**整个项目最重要的一次重写**：从 Flutter 换成 Kotlin/Compose，UI 换成 MiuiX 风格。
课表获取方式也彻底变了——**不再在 App 内登录，改为内嵌 WebView 打开教务系统，
用户在网页里登录完点「导入」抓当前页面**。

体积从 57 MB 降到 14.6 MB，去掉了 Flutter 引擎与 App 内登录的整套逻辑。

## 需求来源（用户原话要点）

- 去掉 App 内登录 UI；
- 内嵌 WebView 打开教务系统，用户自己在网页里登录后点「导入」抓取当前页面课表；
- UI 换成 MiuiX 风格（https://github.com/compose-miuix-ui/miuix）；
- 去掉页面标语。

## 本版本做了什么

### 工程

新建 `shzu_class_km/`（Kotlin/Compose 工程，此后一直是唯一正式工程）：

- `MainActivity` → `AppRoot`：无数据时显示 `ImportPage`，有数据时 `Scaffold` + `NavigationBar` 三页。
- `ui/ImportPage.kt`：WebView 打开 `jwgl.shzu.edu.cn`（忽略 SSL 错误），
  底部「导入当前页面课表」按钮 → `evaluateJavascript("AndroidBridge.sendHtml(document.documentElement.outerHTML)")`
  → `addJavascriptInterface` 回传 HTML → 解析。
- `ui/TodayPage.kt` / `ui/WeekPage.kt`（`HorizontalPager` 左右滑动切周）/ `ui/SettingsPage.kt`。
- `data/CourseParser.kt`（Jsoup，沿用强智系统解析规则）、
  `data/AppRepository.kt`（SharedPreferences + kotlinx.serialization）。
- `ui/CourseColors.kt`（15 色低饱和可区分色板）、`ui/Theme.kt`（MiuixTheme + ThemeController）。

### 构建环境（这一段是本项目后面所有构建的底座，踩坑全在这里）

- **Gradle 9.7.1**（`android-toolchain/gradle/gradle-9.7.1`）：AGP 9.4.0 要求 Gradle ≥ 9.6.0，
  本地 9.3.1 不够；而 AGP 8.13.2 又与 Gradle 9.6+ 不兼容（用了已移除的内部 API）。
  二者只能配 **AGP 9.x + Gradle 9.6+**。
- **AGP 9.0+ 内置 Kotlin**：必须**移除** `org.jetbrains.kotlin.android` 插件，否则报错。
  保留 `org.jetbrains.kotlin.plugin.compose` / `org.jetbrains.compose` / `serialization`。
- **compileSdk 37**：miuix 0.9.x 的 aar-metadata 里 `minCompileSdk=37`，但当时
  **android-37 在 sdkmanager 里尚不存在**。解法：复制 `platforms/android-36` 为
  `platforms/android-37`，并改 `source.properties` 里的 `AndroidVersion.ApiLevel=37`
  （代码不使用 37 新 API，可正常编译）。
- **CMP 1.11.1**（不用 1.12.0：它对 compileSdk 37 的 compose 依赖更多；
  1.11.1 与 miuix 0.9.3 的依赖一致）；`androidx.activity:activity-compose:1.10.1`（1.13.0 要求 37）。
- **网络极慢（~100 KB/s）**：`gradle.properties` 加
  `systemProp.org.gradle.internal.http.connectionTimeout/socketTimeout=180000`；
  缺的 jar 用 `curl -C -` 断点续传手动下载后放进
  `gradle-home/caches/modules-2/files-2.1/<group>/<artifact>/<version>/<sha1>/`
  （sha1 用 `Get-FileHash -Algorithm SHA1` 算）。
- **内存紧张（机器 15GB，可用常 <4GB）**：`org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m`、
  `kotlin.daemon.jvmargs=-Xmx768m`、`org.gradle.workers.max=1`、`parallel=false`。
  ⚠️ 内存给太大会导致构建中途被 OOM 杀掉（现象：日志无任何错误、约 2 分钟中断）。

### 代码坑（首次遇到，后面反复踩）

- Kotlin 字符串模板里 `"$sections小节"` 会把 `sections小节` 当成一个标识符
  （**中文是合法 Kotlin 标识符字符**），必须写 `"${sections}小节"`。
- 不要 `import androidx.compose.foundation.layout.weight`（internal API）。
- Compose Multiplatform 的库要用 **CMP 插件**（不是纯 androidx.compose），
  且 AGP / Kotlin / CMP 三者版本强绑定，先对齐官方 example 的版本组合再动手。

## 已知问题（下一个版本修）

WebView 显示不正常、无法进入「学期理论课表」页 → 见 [版本说明_MiuiX首版_WebView修复.md](版本说明_MiuiX首版_WebView修复.md)。
