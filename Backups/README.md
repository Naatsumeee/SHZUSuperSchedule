# Backups — release APK 归档与版本声明

本目录存放**每个版本的详细改动声明**，以及**最近出过的 release APK**。
正式工程在 `../shzu_class_km/`，构建产物默认输出到
`shzu_class_km/app/build/outputs/apk/release/app-release.apk`。

## 归档规范

1. 声明文件命名：`版本说明_<版本>.md`，与 APK 一一对应。**声明永久保留、从不删除。**
2. APK 命名：`石大超级课表_<versionName>.apk`（早期非正式版本保留能说明其身份的名字）。
3. 新增版本时：复制 APK → 新增同名声明 → 在本文件「版本索引」表格里补一行。
4. **对外发布**：打 tag（`git tag -a BETA-v1.4 -m "..."`）→ `git push origin <tag>` →
   在 GitHub 上基于该 tag 创建 Release（标记为 **prerelease**），把 APK 作为附件上传。
   ⚠️ **Release 附件名只能用 ASCII**：GitHub 会**静默丢掉非 ASCII 字符**
   （实测 `石大超级课表_BETA-v1.3.2.apk` 被存成 `_BETA-v1.3.2.apk`），
   所以附件统一命名为 `SHZUSuperSchedule_<版本>.apk`。

## 🔴 APK 二进制的存放策略（2026-09-25 调整）

**规则：仓库内只保留最近 2 个 APK；更早的二进制以 GitHub Release 为归档处。**

为什么改：仓库 pack 一度涨到 208 MiB，其中本目录 9 个 APK 占 167 MB
（单是 Flutter 原型就 57 MB）。而 APK 是可重建的构建产物，
**真正的长期资产是版本说明 md 与 git 历史**，不是二进制本身。

⚠️ **删 APK 之前必须先确认它别处有副本。** 不能想当然地"以 Release 为归档"——
实测 9 个 APK 里**只有 v1.3.2 与 v1.4 真的在 Release 上有附件**。
核对方式（sha256 对比，不要只比字节数）：

```bash
sha256sum "Backups/石大超级课表_BETA-v1.4.apk"
curl -s "https://api.github.com/repos/Naatsumeee/SHZUSuperSchedule/releases?per_page=50" \
  | grep -o '"digest":"[^"]*"'
```

即使二进制不在了，**每个版本的体积与 sha256 都记在下表**里，
据此仍能判断"手上这个包是不是那一版"，也能验证从别处找回来的副本是否被篡改。

### 已移出仓库的 APK（二进制已删，指纹留档）

| 版本 | 字节数 | SHA-256 |
|------|--------|---------|
| 1.0（Flutter 原型） | 57,004,690 | `7c6d619682fc8678ab129563082d0ddf39d2fc998d7176806d53c478cae527aa` |
| 1.0（MiuiX 首版） | 14,571,731 | `f62ce5c12ea5aa78470fbe899875373f9b83e5e63154b910c55fc3928fd9bbd2` |
| 1.0（WebView 修复） | 14,571,731 | `89063e577f7eadf654d743fb23ceb59b9b36c16d121937eff1296f78a2c6708d` |
| 1.1 | 14,625,123 | `b16038de66b700c0417b0c90f4bb6f9b7748e40b903b4a84670b42d1afc59c30` |
| 1.2 | 14,658,023 | `cf0c15b3632a397ab5c99515b3812e6383fb632d5d19901e2265672a5b44dac4` |
| 1.3 | 14,674,447 | `74842b5ac34fd9104e7db9873b6df9ec049ee8f773e2d9fa7b116a0bf2d9a76d` |
| 1.3.1 | 14,727,039 | `034cb9f808f15c9c015d3b4bcb9f784862cebf0e69dda4693e308254849f65fa` |

### 仍在仓库 / 已上 Release 的 APK

| 版本 | 字节数 | SHA-256 | Release 附件 |
|------|--------|---------|--------------|
| 1.3.2 | 14,927,459 | `6de967e1cc71469be10fcdb6a2cba5a67dd132df563a0c7121c3e5cbd6cb2249` | `SHZUSuperSchedule_BETA-v1.3.2.apk`（Release BETA-v1.3.2） |
| 1.4 | 15,009,375 | `7455f2e60bfbb6c3c437347c7c5ab0e40be7ef01cce5f4341f9abc8c4b643fa0` | `SHZUSuperSchedule_BETA-v1.4.apk`（Release BETA-v1.4） |

## 版本索引

| # | 版本 | 包名 | versionCode | 大小 | 构建时间 | APK | 声明 |
|---|------|------|-------------|------|----------|-----|------|
| 1 | 1.0（Flutter 原型） | `com.shzu.shzu_class` | 1 | 57.0 MB | 2026-09-18 20:41 | *（已移出仓库）* | [声明](版本说明_Flutter原型.md) |
| 2 | 1.0（MiuiX 首版） | `com.shzu.classlist` | 1 | 14.57 MB | 2026-09-18 21:57 | *（已移出仓库）* | [声明](版本说明_MiuiX首版.md) |
| 3 | 1.0（WebView 修复） | `com.shzu.classlist` | 1 | 14.57 MB | 2026-09-18 22:08 | *（已移出仓库）* | [声明](版本说明_MiuiX首版_WebView修复.md) |
| 4 | 1.1 | `com.shzu.superschedule` | 11 | 14.63 MB | 2026-09-18 23:02 | *（已移出仓库）* | [声明](版本说明_BETA-v1.1.md) |
| 5 | 1.2 | `com.shzu.superschedule` | 12 | 14.66 MB | 2026-09-18 23:54 | *（已移出仓库）* | [声明](版本说明_BETA-v1.2.md) |
| 6 | 1.3 | `com.shzu.superschedule` | 13 | 14.67 MB | 2026-09-19 00:42 | *（已移出仓库）* | [声明](版本说明_BETA-v1.3.md) |
| 7 | 1.3.1 | `com.shzu.superschedule` | 14 | 14.73 MB | 2026-09-19 02:49 | *（已移出仓库）* | [声明](版本说明_BETA-v1.3.1.md) |
| 8 | 1.3.2 | `com.shzu.superschedule` | 15 | 14.78 MB | 2026-09-19 18:53 | `石大超级课表_BETA-v1.3.2.apk` | [声明](版本说明_BETA-v1.3.2.md) |
| 9 | **BETA-v1.4（当前）** | `com.shzu.superschedule` | 16 | 14.31 MB | 2026-09-22 15:19 | `石大超级课表_BETA-v1.4.apk` | [声明](版本说明_BETA-v1.4.md) |

> 想装历史版本：从对应 GitHub Release 下载 `SHZUSuperSchedule_<版本>.apk`；
> 没有 Release 的早期版本（#1–#7）只能凭借指纹从其他备份中辨认。

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

⚠️ **不要指望用 git 历史取回已删除的 APK 来缩体积** —— 二进制仍在 `.git` 的 pack 里，
`git rm` 不会让仓库变小（历史对象还在）。真要缩体积得重写历史（`git filter-repo`），
那会改变所有提交哈希、影响已发布的 tag，**本项目不打算这么做**：
体积只靠"不再往历史里堆二进制"来控制，已删的 7 个 APK 的 blob 仍留在历史中。
