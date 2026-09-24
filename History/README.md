# History — 项目历史记录归档

本目录保存这个工程从第一行代码到当前版本的**全部历史记录**：思考链、改动过程、
用户提示词、构建日志、设备日志、调试截图、分析脚本、以及已废弃的旧版源码。

> **这里的内容不参与构建，也不会被任何脚本读取。**
> 作用是：换人 / 换 Agent / 隔了很久回来看时，能完整还原「为什么这么做」，
> 而不是只看到最终代码。删掉它不影响 App 编译运行，但会丢掉所有决策依据。

## 目录结构

| 目录 | 内容 | 什么时候会想看 |
|------|------|----------------|
| `memory/` | **核心**。每日工作日志 + 项目长期笔记（思考链、根因分析、踩坑记录、用户偏好） | **第一个要看的**。所有技术决策的来龙去脉都在这 |
| `prompts/` | 用户提示词 / 需求指令序列，以及本项目的约束型提示词 | 想知道「某个功能当初是怎么提的」 |
| `thinking/` | 从 memory 中提炼的关键决策与复盘（模糊方案、小组件、构建环境等专题） | 想快速理解某个难点，不想翻长日志 |
| `logs/build/` | 构建日志 `km_build_*.txt`、`gradle_build*.txt`、各类编译错误 | 构建失败时对比历史错误 |
| `logs/device/` | logcat（`log_app` / `log_launcher` / `log_w*`）、小组件 dump | 排查设备端行为 |
| `logs/blur/` | 底栏模糊调试的诊断日志（`*_log.txt`、`diag*.txt`） | 复现模糊相关的验证过程 |
| `screenshots/` | 全部调试截图（含 `_shots/` 子目录的早期截图） | 对比视觉效果 |
| `dumps/` | `uiautomator dump` 出来的 UI 层级 xml | 查控件坐标 / 文本 |
| `scripts/` | 一次性分析脚本（`blurcmp.py`、`finalcheck.py`、`sharp.py` …） | 想复现某次像素级验证 |
| `misc/` | 环境探测、下载进度、依赖元数据等零散产物（133 项） | 基本不用看，留档以防万一 |
| `legacy/` | **已废弃技术栈的源码**：Flutter 版 `lib/`、早期 `app_src/` | 想找回旧版实现细节 |
| `legacy/教务爬虫/` | 早期 Python 版教务爬虫（已被 App 内抓取取代） | 想用脚本独立抓课表数据 |

## 阅读顺序建议

1. `memory/MEMORY.md` —— 项目长期笔记：技术栈、必须遵守的约束、构建命令、各模块模型。
2. `memory/2026-09-*.md` —— 按日期的工作日志，改动过程与根因分析。
3. `prompts/用户指令序列.md` —— 需求是怎么一步步提出来的。
4. `../Backups/README.md` —— 每个 release 版本做了什么。

## 归档规则（后续继续用）

- 每次出包：把构建日志丢进 `logs/build/`，把 APK 丢进 `../Backups/`。
- 每次排查：截图丢 `screenshots/`、dump 丢 `dumps/`、logcat 丢 `logs/device/`、
  一次性脚本丢 `scripts/`，并在当天 `memory/` 日志里写清结论。
- **工程根目录只留"活着的东西"**：构建日志、调试 dump、临时测试 APK 用完即归位或删除，
  不要堆在根目录（2026-09-25 清理时根目录已堆了 23 份日志 + 12 个临时 APK / 172 MB）。
- **`memory/` 里 `.workbuddy/memory/` 是活的工作区**，`History/memory/` 是它的快照。
  后续请以 `.workbuddy/memory/` 为准，重大节点再同步一份到这里
  （2026-09-25 同步：含 09-21、09-22 日志与 MEMORY.md）。

## 2026-09-25 整理动作留档

| 动作 | 内容 |
|------|------|
| 归入 `logs/build/` | 根目录 23 份 `km_build_v145…v166.txt`（现共 90 份） |
| 归入 `logs/device/` | `wv_frame_exam.html` / `wv_frame_cjcx_frm.html` / `wv_frame_xsdjks_list.html` / `wv_frame_xsksap_query.html` / `wv_wv.txt`（排查教务页面结构时 dump 的 iframe HTML） |
| 归入 `dumps/` | `uiautomator_根.xml` |
| 归入 `screenshots/` | `root_c.png` / `root_r1.png` |
| 归入 `legacy/教务爬虫/` | `tools/jwgl_spider.py` + `tools/requirements.txt` + `docs/教务爬虫使用说明.md` |
| **已删除** | 根目录 12 个 `石大超级课表_教务查询测试版_v*.apk`（172 MB，正式包在 `../Backups/`） |

## 注意

`misc/` 里有一批文件是构建环境搭建期（Gradle/AGP/NDK/Flutter 下载、反编译、内存排查）的
中间产物，名字很随意（`gb4_err.txt`、`ndk_progress2.txt` …）。它们只对「当时那次排查」有意义，
看不懂直接忽略即可，不要删——万一要复盘构建环境问题时是唯一的原始材料。
