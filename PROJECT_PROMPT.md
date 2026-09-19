# PROJECT_PROMPT — 石大超级课表

> **给接手的 Agent（或三个月后的自己）**：把这一段完整读一遍再动手。
> 它包含跑起来所需的全部环境信息，以及**几条会让编译直接失败的红线**。
> 本文件与 `History/prompts/交接提示词.md` 同源。

---

## 0. 三十秒上手

```powershell
# 构建（推荐，脚本已设好全部环境变量）
cd C:\Users\xutia\WorkBuddy\SHZUClassList
powershell -ExecutionPolicy Bypass -File tools\build_km_v145.ps1

# 看结果（⚠️ 日志是 UTF-16，必须这样看）
cat km_build_v145.txt | tr -d '\000' | sed 's/\r//' | grep -E "^e: |BUILD"

# 安装到设备
adb install -r 石大超级课表_BETA-v1.3.2.apk
```

源码在 `shzu_class_km/`，**唯一正式工程**。产物：
`shzu_class_km/app/build/outputs/apk/release/app-release.apk`

---

## 1. 项目是什么

面向石河子大学学生的课表 App。核心流程：

1. 内嵌 WebView 打开教务系统 → 用户自己在网页里登录；
2. 切到「学期理论课表」页 → 点 App 底部「导入当前页面课表」→ Jsoup 解析 HTML；
3. 课表以 JSON 持久化到本地，之后离线查看今日课程 / 本周课表；
4. 可选：桌面小组件、上课提醒通知。

- 包名 `com.shzu.superschedule`，当前 **BETA-v1.3.2**（versionCode 15）
- 版本定义在 `shzu_class_km/app/build.gradle.kts`
- **不是** Flutter 项目（早期是，已彻底废弃，旧源码在 `History/legacy/`）

---

## 2. 🚨 红线（违反必炸，按踩坑次数排序）

### ① Kotlin 字符串模板必须加花括号 —— 已踩 3 次

```kotlin
"$sections小节"    // ❌ 中文是合法 Kotlin 标识符字符，被解析成标识符 sections小节
"$startMinutes()"  // ❌ 编译错误：Function invocation 'startMinutes()' expected
"${sections}小节"   // ✅
"${startMinutes()}" // ✅
```

**凡是变量后面紧跟 `(`、中文、字母数字，一律写 `${...}`。**
改完代码必自检：

```bash
grep -rn '\$[a-zA-Z_][a-zA-Z0-9_]*()' shzu_class_km/app/src/main/java/
```

### ② 依赖版本不许动

AGP / Gradle / Kotlin / Compose Multiplatform / MiuiX 五者**强绑定**，
升任何一个都可能连锁崩。当前组合是唯一验证过的：

| 组件 | 版本 | 为什么不能动 |
|------|------|-------------|
| Gradle | 9.7.1 | AGP 9.4.0 要求 ≥ 9.6.0；8.x AGP 又与 9.6+ 不兼容 |
| AGP | 9.4.0 | 9.0+ **内置 Kotlin**，必须**移除** `org.jetbrains.kotlin.android` 插件 |
| compileSdk | 37 | miuix 0.9.x 要求 `minCompileSdk=37`；本机是复制 android-36 伪造的 |
| CMP | 1.11.1 | 1.12.0 对 compileSdk 37 的 compose 依赖更多；1.11.1 与 miuix 0.9.3 一致 |
| MiuiX | 0.9.3 | |
| activity-compose | 1.10.1 | 1.13.0 要求 compileSdk 37 的更多依赖 |

### ③ MiuiX 弹层必须手动提供 NavigationEventDispatcherOwner —— 否则闪退

MiuiX 下拉 / 弹层依赖 `LocalNavigationEventDispatcherOwner`，该 CompositionLocal
**只在 NavHost 中自动提供**。本项目没有导航库，必须在 `MainActivity` 的
`setContent` 外层**手动提供**，否则点开任何下拉菜单就 `IllegalStateException` 闪退。

### ④ 构建日志是 UTF-16

PowerShell `Tee-Object` 写出的日志是 UTF-16LE。直接 `grep "BUILD SUCCESSFUL"`
**会误判为失败**（因为字符间有 `\000`）。必须：

```bash
cat km_build_vXXX.txt | tr -d '\000' | sed 's/\r//' | grep -E "^e: |BUILD"
```

### ⑤ 内存参数不许调大

机器 15GB 但可用常 < 4GB。当前 `gradle.properties`：
`org.gradle.jvmargs=-Xmx1536m`、`kotlin.daemon.jvmargs=-Xmx768m`、
`org.gradle.workers.max=1`、`parallel=false`。
**调大会被 OOM 杀掉**，现象是日志无任何错误、约 2 分钟后中断。

### ⑥ Android Toast 不能指定毫秒

只有 `LENGTH_SHORT`(~2s) / `LENGTH_LONG`(~3.5s) 两档。本项目统一 1.2s，
实现在 `ui/FileActions.kt`：按 `LENGTH_SHORT` 弹出 + `Handler.postDelayed(1200)`
到点 `cancel()`。**改时长只改 `TOAST_DURATION_MS` 一个常量**，别去动调用点。

---

## 3. 目录地图

```
shzu_class_km/app/src/main/java/com/shzu/superschedule/
├── MainActivity.kt          入口；手动提供 NavigationEventDispatcherOwner
├── model/
│   ├── Course.kt            课程数据类
│   └── AppSettings.kt       全部设置项（含 rowHeight 课程高度）
├── data/
│   ├── AppRepository.kt     SharedPreferences + 序列化
│   ├── CourseParser.kt      Jsoup 解析强智教务页面
│   ├── ScheduleStore.kt     课表 JSON 持久化（files/schedule/<学期>.json）
│   └── Notifier.kt          上课提醒通知 + sendTest
└── ui/
    ├── AppRoot.kt           顶层：无数据→ImportPage，有数据→Scaffold+底栏
    ├── ImportPage.kt        WebView 导入（双 UA、移动端/桌面端切换）
    ├── TodayPage.kt         今日课程
    ├── WeekPage.kt          本周课表（HorizontalPager 切周）
    ├── SettingsPage.kt      设置页（含 App 内 CHANGELOG）
    ├── BlurBar.kt           ⭐ 底栏真实背景模糊（三层图层方案，别乱改）
    ├── PageStack.kt         自研页面栈 + AnimatedContent 过渡
    ├── PageHeader.kt        大标题 / SubPageTopBar
    ├── CourseColors.kt      配色集与色板
    ├── CourseDialogs.kt     课程详情 / 增删
    ├── HslColorPicker.kt    HSL 色盘
    ├── FileActions.kt       导入导出 + 统一 toast
    ├── Theme.kt             MiuixTheme
    └── WeekCalc.kt          教学周计算
└── widget/
    ├── ScheduleWidgetProvider.kt     自适应小组件
    └── FixedSizeWidgetProviders.kt   Small/Wide/Tall/Big 四档
```

---

## 4. 关键机制（改之前先读）

### 课表布局模型

- 整个课表 = **固定 10 行 × 7 列**网格，10 行对应 1-10 课时，**行距严格相等**。
- 「课程高度」滑块（`AppSettings.rowHeight`，默认 52dp，范围 34-96）统一调节所有课高度，
  并决定网格行距：`rowStride = rowHeight + CELL_GAP * 2`。
- 一门课占 N 个课时就铺满 N 格。周视图用 `Layout` + `Constraints.fixed` 的 overlay 定位，
  **多行格必须 `Constraints.fixed`，否则会被父容器 clamp 压成 1 行**。
- 非本周课程：过滤掉与本周块重叠的；其余按 `from-to` 分组合并成一个块并标 `mergedWeeks`。
  今日页只显示本周课程。
- 空位由 `EmptyCell` 渲染并可点击加课；被长格覆盖的行由 `occupiedByDay` 标记后只留 `Spacer`。
- 冲突判定只看**本周内同课时**；冲突时占课时少的课程优先占位。
- 虚线网格 `Modifier.gridBackground(enabled)`：`PathEffect.dashPathEffect(10f, 8f)`。
- 「课程数量」/ 今日页节数都按**课程名去重的学科门数**统计，不是节数。

### 底栏模糊（`ui/BlurBar.kt`）—— 本轮最难的坑，改动前务必读完

目标是模糊**底栏背后**的页面内容。有两个连环陷阱：

1. **必须用 `DrawScope.record(size) { drawContent() }`** 这个成员扩展。
   `GraphicsLayer.record(density, layoutDirection, size, block)` 会另建 `CanvasDrawScope`，
   导致 `drawContent()` 画到屏幕上而不是录进图层。
2. **带 `renderEffect` 的节点会把内容裁在自身边界内**（哪怕 `clip = false`）。
   所以不能把整页图层 `translate` 进底栏画布——内容全在边界外，会一像素不画。

**正确做法（三层图层）**：

1. `contentLayer`：整页内容（`drawContent()` 必须是第一个绘制调用）；
2. `sliceLayer`：**尺寸 = 底栏尺寸**，内容 = `translate(top = contentTopY - barTopY) { drawLayer(contentLayer) }`
   —— **先切好再模糊**；
3. 5 个 band Canvas：先 `graphicsLayer { renderEffect = BlurEffect(r, r, TileMode.Clamp) }`，
   再 `drawLayer(sliceLayer)`。半径自下而上递减（165/134/103/72/41 px），
   每层用 `CompositingStrategy.Offscreen` + `DstIn` 渐变淡出，底色顶边完全透明。

⚠️ `Modifier.blur` 内部 `clip = true`，要模糊栏外内容必须改用显式 `RenderEffect`。

### 桌面小组件

- **RemoteViews 白名单**：布局里**禁用 `<View>`**（`android.view.View` 不在白名单），
  色条 / 分隔线用 `ImageView`。违规时膨胀异常**被 MIUI 静默吞掉**，
  表现为永久灰色「载入窗口小部件时出现问题」；而 `previewLayout` 看起来正常，极具迷惑性。
- **尺寸档位靠多个 `<receiver>`**：共 5 个（自适应 + 2×2 / 4×2 / 2×4 / 4×4），各配独立 xml info。
- 卡片根布局 `match_parent`，行高按 `OPTION_APPWIDGET_MIN/MAX_HEIGHT` 的真实 dp
  扣除固定开销后均分（`setMinimumHeight`），不要用 `wrap_content + minHeight`。
- 排查：logcat 过滤 tag `flutter :`，关键行 `[AppWidgetHostView] INFLATE path` /
  `_buildWithLayoutParams`。**有 INFLATE 但没有 `_buildWithLayoutParams` = 膨胀被吞 → 查白名单**。

### 教务系统对接

- 入口 `https://jwgl.shzu.edu.cn`，课表页 `https://jwgl.shzu.edu.cn/jsxsd/xskb/xskb_list.do`
- WebView **双 UA**：移动端 UA 命中移动版登录页；**导入课表时切桌面 UA**让宽表格完整显示。
- 学期下拉 `select#xnxq01id`，兜底 `#xnm`(学年) × `#xqm`(学期) 联级框。
- 学期代码统一规范化为 `2026-2027-1`。
- 静默抓学期列表：读 `CookieManager.getCookie("https://jwgl.shzu.edu.cn")` →
  `HttpURLConnection` 拉 HTML → Jsoup 解析。
  ⚠️ **取 Cookie 的 URL 必须带 `/jsxsd` 路径**，否则拿不到登录会话（v1.3.1 修过这个 bug）。

### 导航

- `ui/PageStack.kt`：自研 `PageStack<T>`（`push`/`pop`/`canGoBack`）+ `PageHost`（方向感知过渡）。
- **预测性返回已移除**（跟手预览体验不佳），回归标准返回处理。
- 二级页用 `SubPageTopBar(title, onBack)`，**返回按钮在页面顶部**。

---

## 5. 用户偏好（必须遵守）

- 界面用 **MiuiX 风格**，**不要橙色主题**；**不要无意义的名句 / 引言 / 标语**。
- **倾向紧凑排版**：标题字号与留白要压，但大标题要保留。
- **滑块 / 配色这类易误触的控件收进二级菜单**。
- 作者署名 **`@Natsume`**；反馈邮箱 **`xu.tianhao@outlook.com`**（标准 outlook 拼写）。
- 版本号带 **`BETA-`** 前缀。
- 导入课表前**严格校验**，失败给出具体原因。
- 课表**本地优先**：JSON 持久化，下次打开直接读，不重复导入。

### 协作方式

- **每次改完必须编译验证**，不能只写代码不构建。
- 用户说自己会测试时，**交付并停止**，不要自作主张继续改。
- 用户反馈非常具体（例如「划到位但还没松手时动画应当已播完」），
  按字面实现，别自己 reinterpret。

---

## 6. 调试与验证

```bash
ADB="C:/Users/xutia/WorkBuddy/android-toolchain/android-sdk/platform-tools/adb.exe"
"$ADB" devices                      # 设备 e87a3fe4
"$ADB" install -r <apk>
"$ADB" shell monkey -p com.shzu.superschedule -c android.intent.category.LAUNCHER 1  # 拉起
"$ADB" shell screencap -p /sdcard/s.png && "$ADB" pull /sdcard/s.png
"$ADB" shell uiautomator dump /sdcard/ui.xml && "$ADB" pull /sdcard/ui.xml
```

设备参数：屏 1440×3200，density 600；底栏 315px（y = 2885~3200）。

- **toast 不进 `uiautomator dump`**，验证用
  `adb shell dumpsys window windows | grep -ci toast` 高频采样计数。
- **测滚动帧率要用设置页**，课表页不需要纵向滚动（swipe 不产生重绘）。
  页面滑到底后 `input swipe` 不产生新帧，必须重启 App 复位。
- 像素级验证脚本在 `History/scripts/`（`blurcmp.py` / `finalcheck.py` / `sharp.py`）。
  ⚠️ **跨运行对比会骗人**：`input swipe` 滚动落点每次可能不同，
  必须先确认两次运行页面统计一致，或用同一张图内「栏上方 vs 栏内」自比。

---

## 7. 发版流程

1. 改 `shzu_class_km/app/build.gradle.kts` 的 `versionCode` / `versionName`。
2. 同时更新 `ui/SettingsPage.kt` 里的 `CHANGELOG`（App 内「关于 → 更新日志」读的就是它）。
3. 构建并确认 `BUILD SUCCESSFUL`。
4. APK 复制到 **`Backups/`**，命名 `石大超级课表_<versionName>.apk`。
5. 在 `Backups/` 新增 `版本说明_<版本>.md`（照已有 8 份的格式写），
   并在 `Backups/README.md` 索引表里补一行。
6. 视情况更新 `docs/APP使用说明.md`。

---

## 8. 接手后第一件事

1. 读 `History/memory/MEMORY.md` —— 项目长期笔记，所有决策依据。
2. 读 `History/memory/2026-09-19.md` —— 最近一天的完整改动过程。
3. 读 `Backups/版本说明_BETA-v1.3.2.md` —— 当前版本做了什么。
4. **先跑一次构建确认环境正常**，再改代码。

需要找历史资料时去 `History/`，索引见 `History/README.md`。

---

## 9. 上游依赖

- **[MiuiX](https://github.com/compose-miuix-ui/miuix)** —— 本项目整套 UI 组件与视觉风格的基础
  （`top.yukonga.miuix.kmp:miuix-ui:0.9.3`）。**仓库内不含其源码**；
  查组件实现 / API 直接去上游仓库，不要往仓库里复制第三方源码。

想本地留一份参考副本也可以，放在 `reference/`（已在 `.gitignore` 中排除，不会入库）。
