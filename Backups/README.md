# Backups — release APK 备份与版本声明

本目录存放**每一个曾经出过的 release APK**，以及每个版本对应的**详细改动声明**。
正式工程在 `../shzu_class_km/`，构建产物默认输出到
`shzu_class_km/app/build/outputs/apk/release/app-release.apk`，**每次发版请把 APK 复制一份到这里并补一份声明**。

## 归档规范

1. APK 命名：`石大超级课表_<versionName>.apk`（早期非正式版本保留能说明其身份的名字）。
2. 声明文件命名：`版本说明_<版本>.md`，与 APK 一一对应。
3. 新增版本时：复制 APK → 新增同名声明 → 在本文件「版本索引」表格里补一行。
4. **不要删旧版本**。这里的价值就是能随时回退到任何一个历史状态。

## 版本索引

| # | 版本 | 包名 | versionCode | 大小 | 构建时间 | APK | 声明 |
|---|------|------|-------------|------|----------|-----|------|
| 1 | 1.0（Flutter 原型） | `com.shzu.shzu_class` | 1 | 57.0 MB | 2026-09-18 20:41 | `石大课表_Flutter原型.apk` | [声明](版本说明_Flutter原型.md) |
| 2 | 1.0（MiuiX 首版） | `com.shzu.classlist` | 1 | 14.57 MB | 2026-09-18 21:57 | `石大课表_MiuiX首版.apk` | [声明](版本说明_MiuiX首版.md) |
| 3 | 1.0（WebView 修复） | `com.shzu.classlist` | 1 | 14.57 MB | 2026-09-18 22:08 | `石大课表_MiuiX首版_WebView修复.apk` | [声明](版本说明_MiuiX首版_WebView修复.md) |
| 4 | 1.1 | `com.shzu.superschedule` | 11 | 14.63 MB | 2026-09-18 23:02 | `石大超级课表_BETA-v1.1.apk` | [声明](版本说明_BETA-v1.1.md) |
| 5 | 1.2 | `com.shzu.superschedule` | 12 | 14.66 MB | 2026-09-18 23:54 | `石大超级课表_BETA-v1.2.apk` | [声明](版本说明_BETA-v1.2.md) |
| 6 | 1.3 | `com.shzu.superschedule` | 13 | 14.67 MB | 2026-09-19 00:42 | `石大超级课表_BETA-v1.3.apk` | [声明](版本说明_BETA-v1.3.md) |
| 7 | 1.3.1 | `com.shzu.superschedule` | 14 | 14.73 MB | 2026-09-19 02:49 | `石大超级课表_BETA-v1.3.1.apk` | [声明](版本说明_BETA-v1.3.1.md) |
| 8 | **BETA-v1.3.2（当前）** | `com.shzu.superschedule` | 15 | 14.78 MB | 2026-09-19 18:53 | `石大超级课表_BETA-v1.3.2.apk` | [声明](版本说明_BETA-v1.3.2.md) |

## 三个关键节点的说明

- **包名换了三次**：`com.shzu.shzu_class`（Flutter）→ `com.shzu.classlist`（Kotlin 首版）→
  `com.shzu.superschedule`（v1.1 起，沿用至今）。因此**不同阶段的 APK 互不是升级关系**，
  从 Flutter 版或 `classlist` 版升级到 `superschedule` 版需要先卸载。
- **技术栈在 #2 彻底换了**：从 Flutter 换成 Kotlin + Jetpack Compose + MiuiX，
  课表获取方式从「App 内登录抓取」换成「内嵌 WebView，用户在网页里登录后点导入」。
- **BETA- 前缀从 v1.3.2 开始**：明确标注这是测试版，App 内「关于 → 更新日志」里也是同一套版本号。

## 校验版本信息

想确认某个 APK 的真实版本号/包名，用 aapt2：

```bash
AAPT="C:/Users/xutia/WorkBuddy/android-toolchain/android-sdk/build-tools/35.0.0/aapt2.exe"
"$AAPT" dump badging "Backups/石大超级课表_BETA-v1.3.2.apk" | grep -E "^package|application-label:|targetSdkVersion:"
```
