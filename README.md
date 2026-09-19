# 石大超级课表（SHZUSuperSchedule）

面向**石河子大学**学生的主打**轻量化、快速启动、无广告**的课表综合App。
解决市面上课表对本校课表适配不佳、有广告、臃肿等等问题。
内嵌 WebView 打开教务系统，用户在网页里登录后一键导入当前页面课表
之后离线查看今日课程 / 本周课表，支持桌面小组件与上课提醒。

- 包名：`com.shzu.superschedule`
- 当前版本：**BETA-v1.3.2**（versionCode 15）
- 技术栈：**Kotlin + Jetpack Compose（Compose Multiplatform 1.11.1）+ MiuiX 0.9.3**

---

## 目录结构

```
SHZUClassList/
├── shzu_class_km/      ⭐ 唯一正式工程（Kotlin/Compose），改代码只动这里
├── Backups/            📦 每个 release 版本的 APK + 对应改动声明
├── History/            🗄️ 全部历史记录（思考链/提示词/日志/截图/旧源码）
├── docs/               📖 使用说明、示例数据、爬虫说明
├── tools/              🔧 构建脚本 + 教务爬虫
├── README.md           本文件
└── PROJECT_PROMPT.md   🤖 交接给其他 Agent 时先看这个
```

---

## 🗄️ History — 历史记录目录（位置与作用）

**位置：工程根目录下的 `History/`。**

这里保存了这个项目从第一行代码到当前版本的**全部历史记录**：
思考链、改动过程、用户提示词、构建日志、设备日志、调试截图、分析脚本，
以及已废弃技术栈的源码快照。

**作用**：换人 / 换 Agent / 隔很久回来看时，能完整还原「为什么这么做」，
而不只是看到最终代码。**它不参与构建，也不会被任何脚本读取**——
删掉不影响 App 编译运行，但会丢掉所有决策依据。

内部结构与阅读顺序详见 **[History/README.md](History/README.md)**，速查版：

| 想找什么 | 去哪 |
|----------|------|
| 技术决策的来龙去脉 | `History/memory/`（**先看这个**） |
| 某个功能当初怎么提的 | `History/prompts/用户指令序列.md` |
| 某次构建为什么失败 | `History/logs/build/` |
| 设备端行为 / logcat | `History/logs/device/` |
| 某次视觉验证怎么做的 | `History/screenshots/` + `History/scripts/` |
| 被废弃的 Flutter 版实现 | `History/legacy/` |

---

## 📦 Backups — release APK 备份

**位置：工程根目录下的 `Backups/`。**

存放**每一个曾经出过的 release APK**（共 8 个版本），以及每个版本对应的
**详细改动声明**。发版规范、版本索引、aapt 校验方法见
**[Backups/README.md](Backups/README.md)**。

> ⚠️ 包名历史上换过三次（`com.shzu.shzu_class` → `com.shzu.classlist` → `com.shzu.superschedule`），
> **跨包名的 APK 互不是升级关系**，需要卸载后安装。

---

## 功能路线（未上线）
### 1.加入教务系统常用查询页面
考试安排
成绩分数
专业排名（转专业申请中获取数据）
空闲教室

### 2.加入校园卡余额查询、充值接口
具体情况还需等待开发确定能否加入

### 3.加入更加现代化的UI界面
例如液态玻璃、Material、预测性返回手势、更精致的模糊、悬浮底栏、接入HyperOS灵动岛通知等等

---

## 构建

### 方式一：脚本（推荐）

```powershell
powershell -ExecutionPolicy Bypass -File tools\build_km_v145.ps1
```

脚本会设好 `JAVA_HOME` / `ANDROID_HOME` / `ANDROID_SDK_ROOT` / `GRADLE_USER_HOME`
并清掉代理环境变量，然后跑 `assembleRelease`。日志写到工程根目录 `km_build_v145.txt`。

> ⚠️ **PowerShell `Tee-Object` 写出来的是 UTF-16**，直接 `grep "BUILD SUCCESSFUL"` 会误判。
> 正确看法：
> ```bash
> cat km_build_v145.txt | tr -d '\000' | sed 's/\r//' | grep -E "^e: |BUILD"
> ```
> 只看编译错误：`grep -E "^e: " -A3`

产物：`shzu_class_km/app/build/outputs/apk/release/app-release.apk`

### 环境要求（版本强绑定，别乱升）

| 组件 | 版本 | 说明 |
|------|------|------|
| Gradle | **9.7.1** | AGP 9.4.0 要求 ≥ 9.6.0 |
| AGP | **9.4.0** | 与 Gradle 9.6+ 配套；**不要退回 8.x** |
| compileSdk | 37 | 由复制 android-36 而来（SDK 管理器当时还没有 37） |
| minSdk / targetSdk | 24 / 36 | |
| Compose Multiplatform | 1.11.1 | 与 miuix 0.9.3 依赖一致，别升 1.12.0 |
| MiuiX | 0.9.3 | |
| JDK | 17 | |

内存限制（机器可用内存常 < 4GB）：`org.gradle.jvmargs=-Xmx1536m`、
`kotlin.daemon.jvmargs=-Xmx768m`、`org.gradle.workers.max=1`。
**内存给太大会被 OOM 杀掉**（现象：日志无错误、约 2 分钟中断）。

---

## 其他工具

- `tools/jwgl_spider.py` —— 教务系统课表爬虫（Python，独立于 App）。
  依赖见 `tools/requirements.txt`，完整用法见 **[docs/教务爬虫使用说明.md](docs/教务爬虫使用说明.md)**，
  输出样例见 `docs/课表.json`。
  ⚠️ 该服务器 TLS 较旧，需用 `curl_cffi` 模拟 Chrome 指纹，标准 `requests` 会握手失败。
  
---

## 声明
**本项目使用AI辅助开发，本人参与思路构成和功能方向制定。项目整体以学习交流为目的，不以任何形式盈利。**

---

## 致谢

- **[MiuiX](https://github.com/compose-miuix-ui/miuix)** —— 本项目**整套 UI 组件与视觉风格**
  都建立在它之上（依赖 `top.yukonga.miuix.kmp:miuix-ui:0.9.3`）。
  衷心感谢原作者的开源工作。

  仓库里**不包含** MiuiX 的源码；需要查阅组件实现时请直接访问上游仓库。
  本地若有一份 `reference/` 副本，那是开发期临时参考用的，已在 `.gitignore` 中排除。

---

## 其他文档

- 用户使用说明：**[docs/APP使用说明.md](docs/APP使用说明.md)**
- 教务爬虫说明：**[docs/教务爬虫使用说明.md](docs/教务爬虫使用说明.md)**
- 交接给其他 Agent：**[PROJECT_PROMPT.md](PROJECT_PROMPT.md)**
- 版本历史与改动：**[Backups/README.md](Backups/README.md)**
- 全部历史记录：**[History/README.md](History/README.md)**
