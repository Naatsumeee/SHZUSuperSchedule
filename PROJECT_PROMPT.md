# PROJECT_PROMPT — 石大超级课表（SHZUSuperSchedule）

> **给接手的 Agent（或三个月后的自己）**：把这一段完整读一遍再动手。
> 它包含跑起来所需的全部环境信息，以及**几条会让编译直接失败、或让结果悄悄出错的红线**。
> 本文件与 `History/prompts/交接提示词.md` 同源，是那份的**当前生效版**。

---

## 0. 三十秒上手

```powershell
# 构建（唯一入口；脚本已设好全部环境变量）
cd C:\Users\xutia\WorkBuddy\SHZUClassList
powershell -ExecutionPolicy Bypass -File tools\build_km.ps1 -Version 166

# Debug 包
powershell -ExecutionPolicy Bypass -File tools\build_km.ps1 -Variant Debug
```

```bash
# 看结果：日志已是干净 UTF-8，直接 grep
grep -E "^e: |BUILD" km_build_v166.txt
```

```powershell
# 单元测试（纯 JVM，不需要设备，约 15 秒）
powershell -ExecutionPolicy Bypass -File tools\test_km.ps1
```

- 源码：`shzu_class_km/`（**唯一正式工程**，改代码只动这里）
- 测试：`shzu_class_km/app/src/test/`（32 个用例，覆盖解析器与周次计算，见 §10）
- 产物：`shzu_class_km/app/build/outputs/apk/release/app-release.apk`
- 装设备：`adb -s <serial> install -r <C:/... 的 ASCII 路径>`（见 §7）

---

## 1. 项目是什么

面向石河子大学学生的课表 App，主打**轻量、快启动、无广告**。

1. 内嵌 WebView 打开教务系统 → 用户在网页里自己登录；
2. 切到「学期理论课表」→ 点「导入当前页面课表」→ Jsoup 解析 HTML；
3. 课表 JSON 持久化到本地，之后离线查看今日课程 / 本周课表；
4. **导入的同时会在后台自动抓取**「考试安排 / 课程成绩 / 等级考试成绩」全部学期并落盘；
5. 可选：桌面小组件、上课提醒通知。

| 项 | 值 |
|---|---|
| 包名 | `com.shzu.superschedule` |
| 当前版本 | **BETA-v1.4**（versionCode 16） |
| 版本定义 | **只在** `app/build.gradle.kts` 的 `versionCode` / `versionName`（单一来源） |
| 仓库 | https://github.com/Naatsumeee/SHZUSuperSchedule |
| 教务入口 | `https://jwgl.shzu.edu.cn` |

**不是** Flutter 项目（早期是，已彻底废弃，旧源码在 `History/legacy/`）。

---

## 2. 🚨 红线（违反必炸，按踩坑次数排序）

### ① Kotlin 字符串模板必须加花括号 —— 已踩 3 次

```kotlin
"$sections小节"    // ❌ 中文是合法 Kotlin 标识符字符 → 被解析成标识符 sections小节
"$startMinutes()"  // ❌ 编译错误：Function invocation 'startMinutes()' expected
"${sections}小节"   // ✅
"${startMinutes()}" // ✅
```

**凡是变量后面紧跟 `(`、中文、字母数字，一律写 `${...}`。**
改完代码必自检（本项目已列为收尾动作）：

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
| compileSdk | 37 | MiuiX 0.9.x 要求 `minCompileSdk=37`；本机是复制 android-36 伪造的 |
| CMP | 1.11.1 | 1.12.0 对 compileSdk 37 的 compose 依赖更多；1.11.1 与 MiuiX 0.9.3 一致 |
| MiuiX | 0.9.3 | |
| activity-compose | 1.10.1 | 1.13.0 要求 compileSdk 37 的更多依赖 |

### ③ MiuiX 弹层必须手动提供 NavigationEventDispatcherOwner —— 否则闪退

MiuiX 下拉 / 弹层依赖 `LocalNavigationEventDispatcherOwner`，该 CompositionLocal
**只在 NavHost 中自动提供**。本项目没有导航库，必须在 `MainActivity` 的
`setContent` 外层**手动提供**，否则点开任何下拉菜单就 `IllegalStateException` 闪退。
依赖：`androidx.navigationevent:navigationevent(-compose):1.1.2`

### ④ 内存参数不许调大

机器 15GB 但可用常 < 4GB。当前 `gradle.properties`：`org.gradle.jvmargs=-Xmx1536m`、
`kotlin.daemon.jvmargs=-Xmx768m`、`org.gradle.workers.max=1`、`parallel=false`。
**调大会被 OOM 杀掉**，现象是日志无任何错误、约 2 分钟后中断。

### ⑤ Android Toast 不能指定毫秒

只有 `LENGTH_SHORT`(~2s) / `LENGTH_LONG`(~3.5s) 两档。本项目统一 1.2s，
实现在 `ui/FileActions.kt`：按 `LENGTH_SHORT` 弹出 + `Handler.postDelayed(1200)`
到点 `cancel()`。**改时长只改 `TOAST_DURATION_MS` 一个常量**，别去动调用点。

### ⑥ 构建脚本 `.ps1` 的三个静默杀手

Windows PowerShell 5.1 跑 `.ps1` 时，下面三点各自都能让脚本"成功退出但零输出"：

1. **必须存为 UTF-8 with BOM**。无 BOM 时 PS 5.1 按 GBK 解码中文注释，
   错位的多字节序列会吞掉 `{` `}` → 解析失败**且一行错误信息都不输出**。
   校验：`head -c 3 tools/build_km.ps1 | xxd` 应输出 `efbbbf`。
   ⚠️ **新建任何 `.ps1` 都要检查 BOM** —— `tools/test_km.ps1` 首次创建时就是漏了 BOM。
2. **不要写 `exit $code`** —— 会杀掉宿主进程，整个脚本的输出全部丢失。
3. **不要用 `Tee-Object` 落日志** —— 它没有 `-Encoding`，落盘固定 UTF-16，
   `grep "BUILD SUCCESSFUL"` 会永远匹配不到。用「捕获后 `Out-File -Encoding utf8`」。

### ⑦ 纯逻辑模块不要直接调 `android.util.Log`

`JwglQueryParser` 等解析逻辑本该能在 JVM 上跑单元测试，但只要它
`import android.util.Log`，测试一调用就抛 `RuntimeException: Stub!`
（android.jar 里的方法体全是占位实现）—— 于是最该被测的逻辑反而测不了。

**统一走 `data/Logger.kt` 的接口**（生产用 `LogcatLogger`，测试默认 `NoopLogger`）。
新增会被测试覆盖的纯逻辑模块时照这个来，不要再直接引 `android.util.Log`。

---

## 3. 目录地图

### 工程根（**只保留这些**）

```
SHZUClassList/
├── shzu_class_km/      ⭐ 唯一正式工程
├── Backups/            📦 每个 release 的**版本声明**（APK 二进制已移出仓库，见其 README）
├── History/            🗄️ 全部历史记录（思考链/提示词/日志/截图/旧源码/弃用爬虫）
├── docs/               📖 APP使用说明 + 示例课表数据
├── tools/              🔧 build_km.ps1（唯一构建入口）+ download_kotlin_deps.ps1
├── .workbuddy/memory/  🧠 当前生效的工作记忆（每日日志 + MEMORY.md）
├── README.md / PROJECT_PROMPT.md（本文件）
```

> ⚠️ **根目录不许堆东西**：构建日志、调试 dump、临时测试 APK 用完即归位到 `History/`
> 或直接删除。2026-09-25 清理时根目录已堆了 23 份日志 + 12 个临时 APK（172 MB）。
> 正式包**不入库**：以 GitHub Release 为归档处，见 §8。

### 源码

```
shzu_class_km/app/src/main/java/com/shzu/superschedule/
├── MainActivity.kt          入口；手动提供 NavigationEventDispatcherOwner
├── model/
│   ├── Course.kt            课程数据类
│   ├── QueryTable.kt        教务查询结果（表头+行）+ QueryKind 三个查询入口
│   └── AppSettings.kt       全部设置项（含 rowHeight 课程高度）
├── data/
│   ├── AppRepository.kt     SharedPreferences + 序列化
│   ├── AppLog.kt            应用内日志（内存 800 行 + files/app.log）
│   ├── CourseParser.kt      Jsoup 解析强智教务课表页
│   ├── JwglQueryParser.kt   ⭐ 三类查询的通用表格解析器（含双层表头修复）
│   ├── JwglQueryFetcher.kt  后台批量抓取三类查询（步骤间必须延时）
│   ├── JwglSession.kt       取教务 Cookie（URL 必须带 /jsxsd 路径）
│   ├── QueryHtmlFetcher.kt  WebView 内 JS fetch 通道
│   ├── QueryStore.kt        ⭐ 查询结果落盘（原子写 + 读取时清洗）
│   ├── ScheduleStore.kt     课表 JSON 持久化（files/schedule/<学期>.json）
│   └── Notifier.kt          上课提醒通知 + sendTest
├── ui/
│   ├── AppRoot.kt           顶层：无数据→ImportPage，有数据→Scaffold+底栏+四个 tab
│   ├── QueryPage.kt         查询页（一级列表 / 二级详情 / WebView 手动兜底）
│   ├── QueryCards.kt        ⭐ 三类结果的卡片排版（按 kind 分派）
│   ├── ImportPage.kt        WebView 导入（双 UA、移动端/桌面端切换）
│   ├── WebViewFetcher.kt    1×1 不可见 WebView，供后台抓取发请求
│   ├── TodayPage.kt         今日课程
│   ├── WeekPage.kt          本周课表（HorizontalPager 切周，日期条在页内）
│   ├── SettingsPage.kt      设置页（含 App 内 CHANGELOG）
│   ├── BlurBar.kt           ⭐ 底栏真实背景模糊（三层图层方案，别乱改）
│   ├── PageStack.kt         自研页面栈 + AnimatedContent 过渡
│   ├── PageHeader.kt        大标题 / SectionTitle / SubPageTopBar
│   ├── CourseColors.kt      配色集与色板
│   ├── CourseDialogs.kt     课程详情 / 增删
│   ├── HslColorPicker.kt    HSL 色盘
│   ├── FileActions.kt       导入导出 + 统一 toast（1.2s）
│   ├── Theme.kt             MiuixTheme
│   └── WeekCalc.kt          教学周计算（含 weekOf：算不出返回 null）
└── widget/
    ├── ScheduleWidgetProvider.kt     自适应小组件
    └── FixedSizeWidgetProviders.kt   Small/Wide/Tall/Big 四档
```

---

## 4. 关键机制（改之前先读）

### 课表布局模型

- 整个课表 = **固定 10 行 × 7 列**网格，10 行对应 1-10 课时，**行距严格相等**。
- 「课程高度」滑块（`AppSettings.rowHeight`，默认 56dp，范围 34-96）统一调节所有课高度，
  并决定网格行距：`rowStride = rowHeight + CELL_GAP * 2`。
- 一门课占 N 个课时就铺满 N 格。周视图用 `Layout` + `Constraints.fixed` 的 overlay 定位，
  **多行格必须 `Constraints.fixed`，否则会被父容器 clamp 压成 1 行**。
- 非本周课程：过滤掉与本周块重叠的；其余按 `from-to` 分组合并成一个块并标 `mergedWeeks`。
  今日页只显示本周课程。
- 空位由 `EmptyCell` 渲染并可点击加课；被长格覆盖的行由 `occupiedByDay` 标记后只留 `Spacer`。
- 冲突判定只看**本周内同课时**；冲突时占课时少的课程优先占位。
- 虚线网格 `Modifier.gridBackground(enabled)`：`PathEffect.dashPathEffect(10f, 8f)`。
- 「课程数量」/ 今日页节数都按**课程名去重的学科门数**统计，不是节数。

### 周视图翻页（2026-09-22 改）

`WeekStrip`（日期条）与星期表头**必须放在 `HorizontalPager` 的页内容里**，不能与 pager 平级。
平级时翻周只有下面的格子动、上面两条纹丝不动，割裂感很强。
放进去由 pager 统一驱动，像素级同步，**不需要手算 `currentPageOffsetFraction`**
（那个 API 的正负号约定容易搞反，手算的偏移量还很难离线验证）。

### 页面大标题对齐（2026-09-22 定稿）

`PageHeader(title, subtitle, fontSize, horizontalPadding, miuixDefault)`：
`miuixDefault = true` 走 MiuiX 规范 `title2`（**24sp**）+ `start/end 16dp, top 16dp, bottom 8dp`；
否则是紧凑档（`fontSize=22sp` + `top 8dp, bottom 4dp`）。

实测落点（外层容器内边距 + 组件内边距）：

| 页面 | 容器 | 水平 | 垂直 |
|---|---|---|---|
| 设置 | `padding(12,12,4,…)` | 12+16 = **28** | 4+16 = **20** |
| 查询 | 同上 | 12+16 = **28** | 4+16 = **20** |
| 今日课程 | LazyColumn `contentPadding(10,10,top=4)` | 10+6 = **16** | 4+8 = **12** |
| 本周课表 | 无额外内边距 | 0+16 = **16** | 4+8 = **12** |

- 今日 vs 本周：水平本就对齐，**垂直曾差 4dp**（今日页叠了 LazyColumn 的 `top=4dp`）
  → 本周课表页标题前补 `Spacer(Modifier.height(4.dp))`。
- 查询与设置同档，**且不能有 subtitle**（多一行小字会把标题整体下移，正是"割裂感"的来源）。
- ⚠️ 课表组（16dp）与查询/设置组（28dp）之间仍有 12dp 落差，用户只要求**组内**一致。

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
⚠️ MiuiX `NavigationBar` 内部硬编码 `.background(color)`，默认是不透明 `surface`，
会把磨砂层整个盖住 —— 必须显式传 `color = Color.Transparent` **且** `showDivider = false`。

### 桌面小组件

- **RemoteViews 白名单**：布局里**禁用 `<View>`**（`android.view.View` 不在白名单），
  色条 / 分隔线用 `ImageView`。违规时膨胀异常**被 MIUI 静默吞掉**，
  表现为永久灰色「载入窗口小部件时出现问题」；而 `previewLayout` 看起来正常，极具迷惑性。
- **尺寸档位靠多个 `<receiver>`**：共 5 个（自适应 + 2×2 / 4×2 / 2×4 / 4×4），各配独立 xml info。
- 卡片根布局 `match_parent`，行高按 `OPTION_APPWIDGET_MIN/MAX_HEIGHT` 的真实 dp
  扣除固定开销后均分（`setMinimumHeight`），不要用 `wrap_content + minHeight`。
- 排查：logcat 过滤 tag `flutter :`，关键行 `[AppWidgetHostView] INFLATE path` /
  `_buildWithLayoutParams`。**有 INFLATE 但没有 `_buildWithLayoutParams` = 膨胀被吞 → 查白名单**。

### 导航

- 底部导航**四个 tab**：**今日(0) / 课表(1) / 查询(2) / 设置(3)**。
  ⚠️ 加 tab 时索引会整体后移，`MainScaffold` 的 `when (selectedTab)` 与
  `BlurredBottomBar` 里的 `NavigationBarItem` 必须**同步改**，漏一处就会串页。
- `ui/PageStack.kt`：自研 `PageStack<T>`（`push`/`pop`/`canGoBack`）+ `PageHost`（方向感知过渡）。
- 各页的页面栈与滚动状态由 **`MainScaffold` 持有**（`SettingsUiState` / `QueryUiState`），
  这样切 tab 再回来时二级页与滚动位置都还在。**新页面照这个模式做**。
- **预测性返回已移除**（跟手预览体验不佳），回归标准返回处理。
- 二级页用 `SubPageTopBar(title, onBack)`（定义在 `PageHeader.kt`，internal，
  设置页与查询页共用），**返回按钮在页面顶部**。

---

## 5. 教务查询（考试安排 / 课程成绩 / 等级考试成绩）⭐ 改这块前必读

### 数据怎么来的

- `QueryKind` 枚举定义三条查询：**label / menuPath / paths（候选地址）/ menuCall / headerKeywords**。
  教务各校路径不同，`paths` 只取第一个当「直达」，**打不开就让用户在网页里手动点菜单**，
  抓取逻辑不依赖具体地址。
- **后台抓取必须走 WebView 内 JS fetch**（`QueryHtmlFetcher` + `WebViewFetcher`）。
  ⚠️ `HttpURLConnection` 复用 `CookieManager` 的 Cookie **拿不到 `JSESSIONID`**
  （教务是多机负载均衡，Cookie 里只有 `SERVERID` / `bzb_njw` / `jsxsd`），
  请求回来是 164 字节的无 title 空壳页。
- 教务会话极脆：**连续快速请求会被判定爬虫并踢掉会话**
  （第 1 个正常、第 2 个起全空壳）。`JwglQueryFetcher.STEP_DELAY_MS = 1200` **不是可选项**。
- 结果**落盘**在 `files/queries.json`（`QueryStore`），不是只存内存。

### 🔑 三类表的真实表头（2026-09-22 从真机日志实测）

| 表 | 列数 | 表头 |
|---|---|---|
| 考试安排 | 13 | 序号 \| 校区 \| 考试校区 \| 考试场次 \| 课程编号 \| 课程名称 \| 授课教师 \| 考试时间 \| 考场 \| 座位号 \| 准考证号 \| 备注 \| 操作 |
| 课程成绩 | 17 | 序号 \| 开课学期 \| 课程编号 \| 课程名称 \| 成绩 \| 成绩标识 \| 学分 \| 总学时 \| 绩点 \| 补重学期 \| 考核方式 \| 考试性质 \| 课程属性 \| 课程性质 \| 通选课类别 \| 及格重修 \| 说明 |
| 等级考试 | 11 | 序号 \| 考级课程(等级) \| 分数类成绩 ×4 \| 等级类成绩 ×3 \| 考级开始时间 \| 考级结束时间 |

**取值一律按表头关键词查找，绝不写死列序号** —— 与「不做逐字段建模」的设计一致。

### 两个大坑

#### 坑一：教务用 `0` 表示「这项没有成绩」

不是留空。四六级 0–710、计算机等级 0–100 **不会出现真实的 0 分**，
所以取「分数类成绩」时必须把 `0` / `-` / `无` / `N/A` 当空跳过（`QueryCards.isRealValue`），
否则会挑中占位值而真正的分数被丢掉 —— 这就是用户反馈的「分数类成绩 0」。

#### 坑二：等级考试表的第二层表头被写进了 `<tbody>`

该表 thead 只有一行、父列用 `colspan` 铺开（`1+1+4+3+1+1 = 11` 列），
**真正的子列名（笔试 / 机试 / 总成绩 …）单独占了 `<tbody>` 的第一行**。后果有两个：

1. 那行前两列是空的，第一个非空单元格就是「笔试」→ 界面上凭空多出一条
   **标题为「笔试」、内容全是「机试 / 总成绩」的空卡片**；
2. 4 列「分数类成绩」全部同名 → 无法分辨哪一列才有分数 → 退化成"取第一个非空值"
   → 挑中教务的 `0` 占位。

修法 `JwglQueryParser.repairStrayHeader(headers, rows)`：把那一行**当第二层表头用** ——
按列号把子名拼到父名后面（`分数类成绩` + `总成绩` → `分数类成绩 / 总成绩`），
再把它从数据里剔除。**仅在表头存在重名时补名**（那是"子列名丢了"的确定信号），幂等。

⚠️ **这个函数有两个调用点，必须都接上**：`JwglQueryParser.readTable`（新抓取路径）
和 `QueryStore.sanitize`（读老存档路径）。只接一处会导致"老存档不用刷新也能修好"
这条承诺落空 —— 2026-09-25 就是因为漏了后者返工了一版。

判据 `isStrayHeaderRow`：非空格 ≥2 **且整行无数字** **且**（行内有复读值 或
每个值都能在表头里找到）。真数据行几乎必然带序号 / 日期 / 分数，所以"无数字"这条很稳。

### QueryStore 的语义（容易踩）

- `merge` 是**按 (kind, semester) 覆盖式**：不在 `incoming` 里的项**保留旧值**。
  → 任何「现在没数据」的结论都必须**带一张 0 行的表**写回去，否则旧数据永远清不掉。
- 落盘必须**原子**（`.tmp` + `renameTo`）。批量抓取跑 2~3 分钟，中途被回收而直接
  `writeText` → 半截 JSON → 下次加载整个存档归零。
- `sanitize` 在**读取**时清洗（占位行 + 杂散表头行 + 补名），改了解析器也要改这里，
  用户不刷新就能看到正确结果。

### 三类结果的排版（`ui/QueryCards.kt`）

- **考试安排**：`考试时间` / `考场` **加粗置顶**；时间经 `formatExamTime()` 重排成
  `2026年11月28日（第13周周六） 10:00-11:30`（正则逐段替换，一格多场也各自带周次；
  周次用 `WeekCalc.weekOf()`，**算不出就不标**，不兜底成第 1 周）。
- **课程成绩**：课程名当标题（Bold 16sp），成绩右对齐放大 22sp 加粗，**不及格标红**
  （数值 <60；文字制只在明确写「不及格/不合格/未通过」时标红，**别把"合格"标红**）；
  `开课学期 / 课程编号` 收进底部 11sp 小字脚注。
- **等级考试**：考级课程当标题加粗；`分数类成绩` / `等级类成绩` **每组只留一条有数据的**；
  排序 `gradeRowsSorted()` = **CET-4 → CET-6 → NCRE一级 → NCRE二级 → 其他** 为主序，
  同种类内按 `考级开始时间` **倒序**（与课程成绩页学期 `sortedDescending()` 一致）。

### 看不见用户数据时的诊断技巧

release 包 `run-as` 报 `package not debuggable`，`adb root` 也不可用，
换成 debug 包签名不同、覆盖安装会**清掉你要查的数据**。

有效办法：`QueryStore.logShape()` 在**读取存档时**把每张表的「形状 + 表头名」
打一行到 logcat（**只打表头，不打单元格值**；走 `android.util.Log` 不占界面缓冲）。
用户什么都不用做，**App 一启动**就能读到 —— 解析器的日志只在抓取时打印，
不刷新就永远是空的。

---

## 6. 用户偏好（必须遵守）

- 界面用 **MiuiX 风格**，**不要橙色主题**；**不要无意义的名句 / 引言 / 标语**。
- **倾向紧凑排版**：标题字号与留白要压，但大标题要保留。
- **滑块 / 配色这类易误触的控件收进二级菜单**。
- 作者署名 **`@Natsume`**；反馈邮箱 **`xu.tianhao@outlook.com`**（标准 outlook 拼写）。
- 版本号带 **`BETA-`** 前缀。
- 导入课表前**严格校验**，失败给出具体原因。
- 课表**本地优先**：JSON 持久化，下次打开直接读，不重复导入。

### 协作方式

- **每次改完必须编译验证**（`BUILD SUCCESSFUL` 才算数），不能只写代码不构建。
- 用户说自己会测试时，**交付并停止**，不要自作主张继续改。
- 反馈非常具体（「划到位但还没松手时动画应当已播完」），**按字面实现**，别 reinterpret。
- **会自己验收并推翻参数**（toast 0.5s → 1.2s 就是这样）——别跟测量值争论。
- 会一次性提一批需求（例如 7 条排版），期望**一次性全部落地**，然后自己逐条验收。
- 反馈常自带判据（「分数要选有具体分数的，分数一般不为 0」），判据直接可用。

---

## 7. 构建 / 装机 / 验证

```bash
ADB="C:/Users/xutia/WorkBuddy/android-toolchain/android-sdk/platform-tools/adb.exe"
"$ADB" devices -l                    # ⚠️ 先确认序列号：本机 e87a3fe4（2410DPN6CC 1440×3200）
"$ADB" -s <serial> install -r "C:/.../app.apk"
"$ADB" -s <serial> shell monkey -p com.shzu.superschedule -c android.intent.category.LAUNCHER 1
"$ADB" -s <serial> logcat -G 16M     # 默认 2 MiB 会被系统噪声冲掉
"$ADB" -s <serial> logcat -d -v time | grep -E "QueryStore|JwglQueryParser"
```

### 🔴 装机后必须用 md5 核对，不要用字节数

MIUI 下 `adb install -r` **不保证立刻替换**。核对：

```bash
P=$("$ADB" -s <serial> shell pm path com.shzu.superschedule | sed 's/^package://' | tr -d '\r')
"$ADB" -s <serial> shell md5sum "$P"     # 与本地产物比对
```

⚠️ **别比字节数**：v162 / v164 / v165 三个包分别是 15,009,379 / 15,009,375 / 15,009,375，
**只差 0~4 字节**，字节核对完全失效。想确认"改了代码但包有没有真变"，到 dex 里找新字符串。

### 其它坑

- `adb install` **不认 MSYS 形式路径**（`/c/Users/...` → `failed to stat`），必须写 `C:/Users/...`。
- **中文文件名**先 `cp` 到纯 ASCII 临时路径再装。
- 设备**会反复掉线又自己回来**（`device not found`），装机前用重试循环。
- 设备参数：屏 1440×3200，density 600；底栏 315px（y = 2885~3200）。
- **toast 不进 `uiautomator dump`**，验证用
  `adb shell dumpsys window windows | grep -ci toast` 高频采样计数。
- 像素级验证脚本在 `History/scripts/`（`blurcmp.py` / `finalcheck.py` / `sharp.py`）。
  ⚠️ **跨运行对比会骗人**：`input swipe` 滚动落点每次可能不同。

---

## 8. 发版流程

1. 改 `shzu_class_km/app/build.gradle.kts` 的 `versionCode` / `versionName`
   —— **这是版本号的唯一真值**。「关于」页显示的版本号经 `BuildConfig.VERSION_NAME`
   自动取自 `versionName`（2026-09-25 起），不必也不能再手改 `SettingsPage`。
2. 在 `ui/SettingsPage.kt` 的 `CHANGELOG` 列表**顶部加一条本版本**（列表第一个
   必须是最新版本，界面按顺序渲染）。**只有这一处需要手动更新**。
3. 构建并确认 `BUILD SUCCESSFUL`。
4. 新增 `Backups/版本说明_<版本>.md`（照已有格式写，**含字节数与 sha256**），
   并在 `Backups/README.md` 索引表里补一行。
5. 视情况更新 `docs/APP使用说明.md`（用户可见的功能说明）。
6. 打 annotated tag：`git tag -a BETA-v1.4 -m "..."` → `git push origin <tag>`。
7. GitHub Release（标 **prerelease**）+ 上传 APK 附件。

### APK 二进制不入库（2026-09-25 起）

**`Backups/` 只存版本声明 md，不存 APK。** 安装包以 **GitHub Release 为归档处**。

理由：仓库 pack 一度到 208 MiB，其中 Backups 的 9 个历史 APK 占 167 MB
（单是 Flutter 原型就 57 MB）。APK 是可随时重建的构建产物，
**真正的长期资产是版本说明 md 与 git 历史**。

⚠️ **删任何 APK 前先确认别处有副本** —— 不要想当然地"以 Release 为归档"：
实测 9 个包里**只有 v1.3.2 与 v1.4 真的在 Release 上有附件**。
核对用 **sha256**（不要只比字节数，历史上多个包只差 0~4 字节）：

```bash
sha256sum "Backups/xxx.apk"
curl -s "https://api.github.com/repos/Naatsumeee/SHZUSuperSchedule/releases?per_page=50" \
  | grep -o '"digest":"[^"]*"'
```

`Backups/README.md` 里记着**每个版本（含已移出的）的字节数与 sha256**，
据此仍能辨认"手上这个包是不是那一版"，也能验证找回的副本有没有被改动。

⚠️ `git rm` **不会**让仓库变小 —— 已删 APK 的 blob 仍留在 `.git` 的 pack 里。
要真正缩体积必须重写历史（`git filter-repo`），那会改变所有提交哈希、
影响已发布的 tag，**本项目不做**。所以体积只靠"不再往历史里堆二进制"控制。

### 发版的两个坑

- 🔴 **Release 附件名只能用 ASCII**：GitHub 会**静默丢掉非 ASCII 字符**
  （实测 `石大超级课表_BETA-v1.3.2.apk` 被存成 `_BETA-v1.3.2.apk`）。
  附件统一命名 `SHZUSuperSchedule_<版本>.apk`。仓库内文件的中文名不受影响。
- **本机没有 `gh` CLI**：只能走 GitHub REST API。token 用
  `printf "protocol=https\nhost=github.com\n\n" | git credential fill` 现取，
  只在进程内使用、不落盘不打印。
  ⚠️ 未认证的 `api.github.com` 一律 **403**，那是**速率限制**不是网络问题。
  ⚠️ 走 urllib 时要显式设代理 `http://127.0.0.1:7897`；
  代理软件没开时用 `ProxyHandler({})` 显式关闭代理（否则会读不到任何代理配置而连接被拒）。

---

## 9. 接手后第一件事

1. 读 **`.workbuddy/memory/MEMORY.md`** —— 项目长期笔记（技术栈、红线、各模块模型、
   教务表头、发版流程）。⚠️ 是 `.workbuddy/` 不是 `History/`（`History/memory/` 只是快照）。
2. 读最近一篇 `.workbuddy/memory/2026-XX-XX.md` —— 按日期的改动过程与根因分析。
3. 读 `Backups/版本说明_BETA-v1.4.md` —— 当前版本做了什么。
4. **先跑一次构建 + 单元测试确认环境正常**，再改代码。

### ⚠️ 本机有两份工作副本，先确认改的是哪一份

| 位置 | 说明 |
|---|---|
| `C:\Users\xutia\WorkBuddy\SHZUClassList` | 历史默认路径（`PROJECT_PROMPT` 旧版与构建脚本原先硬编码这个） |
| `E:\Projects\SHZUClassList` | 当前接管使用的工作区 |

两者是**各自独立的 git 克隆**（不是同一目录的链接），容易分叉。
`tools/build_km.ps1` 与 `tools/test_km.ps1` 现在都**由脚本自身位置推工程根**，
所以在哪一份里调用就作用于哪一份，不会再张冠李戴。

需要找历史资料时去 `History/`，索引见 [History/README.md](History/README.md)：

| 想找什么 | 去哪 |
|----------|------|
| 技术决策的来龙去脉 | `History/memory/` + `.workbuddy/memory/` |
| 某个功能当初怎么提的 | `History/prompts/用户指令序列.md` |
| 某次构建为什么失败 | `History/logs/build/`（90 份） |
| 设备端行为 / logcat / 教务页面 dump | `History/logs/device/` |
| 某次视觉验证怎么做的 | `History/screenshots/` + `History/scripts/` |
| 被废弃的 Flutter 版 / Python 爬虫 | `History/legacy/` |

---

## 10. 单元测试（`app/src/test/`）

```powershell
powershell -ExecutionPolicy Bypass -File tools\test_km.ps1   # 32 个用例，约 15 秒
```

**为什么值得跑**：解析器与周次计算是历史上 bug 最集中的地方，而它们
**编译永远是通过的** —— 只能靠跑一遍才发现。有了测试就不用每次
「改代码 → 2 分钟构建 → adb 装机 → 登教务点查询」地验证（一轮十几分钟、还要联网）。

| 测试类 | 覆盖 |
|--------|------|
| `JwglQueryParserTest` | 占位行过滤、第二层表头剔除+补名及幂等、挑表不串台、登录页/空页安全阀、多行表头 rowspan 列对齐、`labeled()` 回退 |
| `WeekCalcTest` | 教学周计算、`currentWeek` 与 `weekOf` 的**兜底差异**（前者出错给 1、后者给 null）、学期代码推算 |

**夹具**在 `app/src/test/resources/jwgl/`：

- `wv_frame_*.html` —— 从真机抓包留档的四份教务页面（`History/logs/device/` 拷来）；
- `exam_grade_reconstructed.html` —— **结构复刻**（当时等级考试的结果 iframe 没落盘），
  严格按 MEMORY.md 与 `History/thinking/教务查询三类结果.md` 记录的真实结构写成。
  **日后拿到真实抓包应直接替换该文件**；结构若有出入，测试失败即是有用信号。

### 🔴 关键词必须互不重叠（该约束已由测试锁住）

`grade` 的关键词一度含 `考级课程`，而「**社会考试报名**」页的表头里有
`考级课程名称` —— 子串命中，解析器会把那个页面当成「等级考试成绩」返回，
字段全是「报名金额 / 报名时间 / 审核状态」。

生产上一度没爆，是因为 `JwglQueryFetcher` 会先按 iframe 的 `src` 锁定目标页
（`/jsxsd/kscj/djkscj_list`），关键词只是第二道保险 ——**但第二道保险自己是漏的**：
一旦 iframe 锁定失效退回「全收」，这一层挡不住。

**2026-09-25 已修**：GRADE 关键词收紧为 `等级类成绩` / `分数类成绩`
（这两个词由该表独有的父列展开而来，别的表不会这么写），去掉 `考级课程`。

两条测试守住这一步：`社会考试报名页不会被当成等级考试成绩页`（不该中的不中）、
`收紧关键词后真实的等级考试成绩表仍能认出`（该中的还得中）。
外加一条**不变式锁** `三类查询的表头关键词互不为子串` ——
以后谁改关键词，只要造成互相包含就会被立刻拦下，不用再靠人记。

---

## 11. 上游依赖

- **[MiuiX](https://github.com/compose-miuix-ui/miuix)** —— 本项目**整套 UI 组件与视觉风格**
  都建立在它之上（依赖 `top.yukonga.miuix.kmp:miuix-ui:0.9.3`）。衷心感谢原作者的开源工作。

  仓库里**不包含** MiuiX 的源码（`.gitignore` 已排除 `reference/`）；
  需要查阅组件实现 / API 时直接访问上游仓库，不要往仓库里复制第三方源码。
  本地若有一份 `reference/` 副本，那是开发期临时参考，不参与构建。
