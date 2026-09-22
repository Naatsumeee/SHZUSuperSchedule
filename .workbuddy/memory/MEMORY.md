# 石大超级课表 — 项目长期笔记

## 工程概况
- Kotlin + Compose Multiplatform 1.11.1 + **MiuiX 0.9.3**（`top.yukonga.miuix.kmp`）
- AGP 9.4.0 / Gradle 9.7.1 / JDK 17；`compileSdk 37`、`minSdk 24`、`targetSdk 36`
- 包名 `com.shzu.superschedule`；版本 **`BETA-v1.3.2`（versionCode 15）**
- 工程目录 `shzu_class_km/`，源码 `app/src/main/java/com/shzu/superschedule/`；
  **git 仓库根是上级目录 `SHZUClassList/`**（`tools/`、`.gitignore` 都在这一层）
- 工具链 `C:\Users\xutia\WorkBuddy\android-toolchain\`（jdk17 / android-sdk / gradle-9.7.1）
- 真机 APK 字节数参考：v160 = 14,992,995；v162 = 15,009,379

## ⚠️ Kotlin 字符串模板（已踩 3 次）
变量后面紧跟 `(`、中文、字母数字，**一律写 `${...}`**：
`"$startMinutes()"` 报 `Function invocation expected`；`"$sections小节"` 被解析成一个标识符。
每次改完必跑：`grep -rn '\$[a-zA-Z_][a-zA-Z0-9_]*()' app/src/main/java/`

## 构建
**唯一入口** `tools/build_km.ps1`（2026-09-21 由 27 个逐字节相同的脚本合并而来）：
```bash
powershell -ExecutionPolicy Bypass -File tools/build_km.ps1 -Version 162   # Release
powershell -ExecutionPolicy Bypass -File tools/build_km.ps1 -Variant Debug
```
`-Version` 可写 `160`/`v160`，省略用时间戳；日志 `km_build_v<tag>[_debug].txt`（干净 UTF-8，可直接 grep）。
- **判断构建结果一律看日志文件**（`grep -E "^e: |BUILD" km_build_v<N>.txt`）——
  经 PowerShell 工具调用时**控制台回显是空的**，但日志正常，别被空 stdout 骗了。
- ⚠️ 改这个 `.ps1` 必须保持 **UTF-8 with BOM**：无 BOM 时 PS 5.1 按 GBK 解码中文注释，
  错位多字节序列吞掉 `{`/`}` → 解析失败**且零输出**，极难定位。校验 `head -c 3 | xxd` 应为 `efbbbf`。
- ⚠️ 脚本里**不要写 `exit $code`**：会杀掉宿主进程，整个脚本输出丢失。
- 日志用「捕获后 `Out-File -Encoding utf8`」，**不要用 `Tee-Object`**（无 `-Encoding`，落盘 UTF-16）。
- 结尾打印 APK 路径与字节数，装机时拿它核对 base.apk 是否真被替换。
- `tools/build_apk.ps1`（Flutter 时代失效脚本）**2026-09-22 已删除**。

## 页面大标题对齐（2026-09-22 定稿）
`PageHeader(title, subtitle, fontSize, horizontalPadding, miuixDefault)`：
- `miuixDefault = true` → MiuiX 规范 `title2`（**24sp**）+ `start/end 16dp, top 16dp, bottom 8dp`。
- 否则紧凑档：`fontSize=22sp` + `top 8dp, bottom 4dp`，水平由 `horizontalPadding` 控制。

实测落点（外层容器内边距 + 组件内边距）：

| 页面 | 容器 | 水平 | 垂直 |
|---|---|---|---|
| 设置 | `padding(12,12,4,…)` | 12+16 = **28** | 4+16 = **20** |
| 查询 | 同上 | 12+16 = **28** | 4+16 = **20** |
| 今日课程 | LazyColumn `contentPadding(10,10,top=4)` | 10+6 = **16** | 4+8 = **12** |
| 本周课表 | 无额外内边距 | 0+16 = **16** | 4+8 = **12** |

- 今日课程 vs 本周课表：水平本来就对齐，**垂直曾差 4dp**（今日页叠了 LazyColumn 的 `top=4dp`）
  → 本周课表页标题前补 `Spacer(Modifier.height(4.dp))`。
- 查询页与设置页走同一档（`miuixDefault = true`）**且不能有 subtitle** ——
  多一行小字会把标题整体下移，正是"割裂感"的来源。
- ⚠️ 未统一：课表组（16dp）与查询/设置组（28dp）仍相差 12dp，用户只要求组内一致。

## 查询页（`ui/QueryPage.kt` + `ui/QueryCards.kt`）
- 三类结果**分类型排版**（`QueryResultCard` 按 `QueryKind` 分派），**取值一律按表头关键词查找，不写死列序号**：
  - **考试安排**：`考试时间`/`考场` 加粗置顶，下面细分隔线再接课程名等；
    时间经 `formatExamTime()` 重排为 `2026年11月28日（第13周周六） 10:00-11:30`
    （`Regex.replace` 逐段替换日期，一格多场也各自带周次；周次用 `WeekCalc.weekOf()`，
    算不出时**不标**而不是兜底成第 1 周）。
  - **课程成绩**：课程名当标题（Bold 16sp），成绩右对齐放大 22sp 加粗，**不及格标红**
    （数值 <60；文字制只在明确写「不及格/不合格/未通过」时标红，别把"合格"标红）；
    `开课学期 / 课程编号` 收进底部 11sp 小字脚注。
  - **等级考试**：考级课程当标题加粗；`分数类成绩 / 等级类成绩` **每组只留一条有数据的**，
    其余（多为空子列）整组排除；排序见 `gradeRowsSorted()`
    = **CET-4 → CET-6 → NCRE一级 → NCRE二级 → 其他** 为主序，
    同种类内按 `考级开始时间` **倒序**（与成绩页学期 `sortedDescending()` 一致）。
- 查询页大标题 = 设置页同款（`miuixDefault = true`，无 subtitle）。

## 周视图横向翻页（`ui/WeekPage.kt`，2026-09-22）
- `WeekStrip`（日期条）与星期表头**必须放在 `HorizontalPager` 的页内容里**，不能与 pager 平级 ——
  平级时翻周只有下面的格子动、上面两条纹丝不动，割裂感很强。
  放进去后由 pager 统一驱动，像素级同步，**不需要手算 `currentPageOffsetFraction`**
  （正负号约定易搞反，且很难离线验证）。
- 页内容结构：`Column { WeekStrip; WeekdayHeader; Box(weight 1f){ WeekGrid } }`。
- 蓝色的「本周」胶囊在「第 N 周」**之前**。

## MiuiX 备忘（0.9.3 实测）
- `basic`：`BasicComponent` / `Card` / `Scaffold` / `NavigationBar(Item)` / `Slider` / `Switch` /
  `Text` / `Button` / `TextField` / `IconButton` / `Icon` / `SmallTitle` / `LinearProgressIndicator`
- `preference`：`OverlayDropdownPreference(items, selectedIndex, title, renderInRootScaffold, onSelectedIndexChange)`
  —— 下拉菜单用它（Overlay 渲染，无需独立窗口）
- **没有内置 blur 修饰符**；`shader.isRenderEffectSupported()` 可探测
- ⚠️ **弹层依赖 `LocalNavigationEventDispatcherOwner`**（v1.3.1 闪退根因）：该 CompositionLocal
  只在 NavHost 中自动提供，本项目无导航库，必须在 `MainActivity.setContent` 外层手动提供。
  依赖 `androidx.navigationevent:navigationevent(-compose):1.1.2`
- ⚠️ `NavigationBar` 内部硬编码 `.background(color)`，默认是不透明 `surface`，会盖住自制磨砂层；
  要透出背景必须显式传 `color = Color.Transparent` **且** `showDivider = false`
- `Scaffold` 绘制顺序：body 先于 bottomBar → 模糊底栏背后的内容**零帧延迟**

## 底栏真实背景模糊（`ui/BlurBar.kt`，BETA-v1.3.2）
Compose 没有「背景模糊」修饰符，`RenderEffect` 只糊自己这层 → **唯一可行路 = 两层 GraphicsLayer「拷贝再模糊」**：
内容层挂 `Modifier.recordBarBackdrop(state)`（`drawWithContent { graphicsLayer.record { drawContent() }; drawLayer(...) }`），
底栏 `drawBehind` 里把内容层按 `-(barTopY - contentTopY)` 平移录进 `barLayer`、设 `BlurEffect(24f,24f,TileMode.Clamp)` 再 `drawLayer`，
最后叠渐变 + 顶部 1px 高光。`BlurEffect` 用 `remember(density)` 缓存；容器 `clipToBounds()`。
需 **API 31+**，低版本只留半透明底色。教训：只调 alpha / 叠半透明色**永远出不来磨砂效果**。

## 桌面小组件（5 provider 架构）
- **RemoteViews 白名单**：布局里**禁用 `<View>`**，色条/分隔线用 `ImageView`。违规时膨胀异常
  被 MIUI **静默吞掉** → 永久灰色「载入窗口小部件时出现问题」，而 `previewLayout` 看起来正常，极具迷惑性
- 尺寸档位靠多 `<receiver>`（自适应 + 2×2 / 4×2 / 2×4 / 4×4），各配独立 xml info
- `initialLayout` 指 `widget_schedule_loading`，`previewLayout` 指正式布局
- 卡片要填满格子：根布局 `match_parent`，行高按 `OPTION_APPWIDGET_MIN/MAX_HEIGHT` 真实 dp 均分
- 排查桌面问题：logcat 过滤 tag `flutter :`，看 `[AppWidgetHostView] INFLATE path`；
  「INFLATE 后无 `_buildWithLayoutParams`」= 膨胀被吞 → 查白名单
- shell 无法广播 `APPWIDGET_UPDATE`；触发更新用 monkey 拉起 app 或重启桌面

## 课表布局模型
- 整个课表 = 固定 **10 行 × 7 列**，行距严格相等；行高 = `AppSettings.rowHeight`（默认 56dp，34–96），`rowStride = rowHeight + CELL_GAP*2`
- 一门课占 N 课时铺满 N 格（周视图用 `Layout` + `Constraints.fixed` 的 overlay 定位，多行格不被 clamp）
- 非本周课程：过滤与本周块重叠的；其余按 `from-to` 分组合并并标 `mergedWeeks`；今日页只显示本周课程
- 空位 `EmptyCell` 可点加课；被长格覆盖的行由 `occupiedByDay` 标记后只留 `Spacer`
- 虚线网格 `Modifier.gridBackground(enabled)`：7 竖 + 每行 1 横，`dashPathEffect(10f,8f)`
- 今日页节数按**科目（课程名去重）**统计，一天最多 5 门
- 多教师：`Course.teacherDisplayIndex: Int = -1`（-1 用第一位，越界 `coerceIn`）；`showAllTeachers` 时全列
- 桌面端配色板：预设只陈列「马卡龙」，**再点一次已选配色**才展开其余 6 套（含「冰川冷调」）；
  自定义配色合集最多 3 套（新建复制马卡龙，15 槽位）
- 设置页分组顺序：**教务 → 显示 → 课表存档 → 系统与交互 → 关于**
- 课表持久化 `ScheduleStore`（`files/schedule/<学期>.json`），可导入/导出（导入严格校验）

## 导航
- `ui/PageStack.kt`：自研 `PageStack<T>`（`push`/`pop`/`canGoBack`/`forward`/`peekBack`）+ `PageHost`（`AnimatedContent` 方向感知）
- 预测性返回：`ComponentActivity` + manifest `enableOnBackInvokedCallback="true"` + 运行时 `PredictiveBackHandler`（需 `SDK_INT >= 33`）
- **「划到位即播完」**：手势进度实时驱动双层过渡（当前页 `translationX = dragFraction * size.width`，
  底层上一级从 `-25%` 归位到 0）；**提交手势时置 `skipAnim` 再 `pop()`**，
  `transitionSpec` 对这次切页返回 `EnterTransition.None` / `ExitTransition.None`，`LaunchedEffect(stack.depth)` 复位
- 二级页统一用 `SubPageTopBar(title, onBack)`，返回按钮在**页面顶部**

## 教务系统
- 入口 `https://jwgl.shzu.edu.cn`；课表页 `/jsxsd/xskb/xskb_list.do`
- 查询页（`QueryKind`）：考试安排 `/jsxsd/xsks/xsksap_query`、课程成绩 `/jsxsd/kscj/cjcx_frm`、
  等级考试成绩 `/jsxsd/kscj/djkscj_list`
- WebView 双 UA：移动端 UA 命中移动版登录页；导入课表切桌面 UA 让宽表格完整显示
- 「学年学期」下拉框 `select#xnxq01id`，兜底 `#xnm` × `#xqm`；学期代码规范化为 `2026-2027-1`
- **教务是 iframe 框架布局**：主框架地址不变，内容在子 iframe 里，只抓 `outerHTML` 只能拿到空壳
- 后台抓取必须走 **WebView 内 JS fetch**（`QueryHtmlFetcher`）——
  `HttpURLConnection` 拿不到 `JSESSIONID`，返回 164 字节空壳页
- 教务会话极脆：**连续快速请求会被判爬虫踢掉会话**（第 1 个正常、第 2 个起全空壳）。
  `JwglQueryFetcher.STEP_DELAY_MS = 1200` 不是可选项
- `kjcdShow(yjcode, ejcode, sjcode, url, name)` **忽略 url**，真正决定打开哪页的是 `sjcode`

## 强智表格解析（`JwglQueryParser`）
- 三类查询表**不做逐字段建模**，统一收敛成「表头 + 行」；按 iframe `src` 锁目标页 +
  表头关键词做第二道保险（三类关键词必须互相排斥，见 `QueryKind.headerKeywords`）
- **表头可能是多行的**：等级考试成绩表第一行 6 列、数据行 11 格。必须按列号铺开**全部 thead 行**
  （处理 `colspan` / `rowspan`，rowspan 用 `carry` 挂起），否则值从第 7 列起全错位
- 两种"不是数据"的行必须滤掉：
  1. **占位行**：跨列写着「未查询到数据」（非空单元格 ≤1）
  2. **杂散表头行**（2026-09-22 新增）：第二层表头被写在 `<tbody>` 里，被当成数据行收进来。
     它前两列空、第一个非空单元格是「笔试」，于是界面上多出一条标题为「笔试」、
     内容全是「机试/总成绩」的空卡片。判据 `isStrayHeaderRow`：非空格 ≥2 **且整行无数字**
     **且**（行内有复读值 或 每个值都能在表头里找到）
- 改解析器救不了**老存档**里的脏行 → `QueryStore.sanitize` 在**读取**时一并清掉
  （日志 `存档清理：移除 N 行…`），用户不刷新也能立刻看到正确结果

## 查询存档（`QueryStore`）
- `merge` 是**按 (kind, semester) 覆盖式**：不在 `incoming` 里的项**保留旧值**。
  → 任何「现在没数据」的结论都必须**带一张 0 行的表**写回去，否则旧数据永远清不掉
  （曾表现为「考试安排 · 共 6 条」这种假数据）
- 落盘必须**原子**（`.tmp` + `renameTo`）。批量抓取跑 2~3 分钟，中途被回收而直接 `writeText`
  → 半截 JSON → 下次加载整个存档归零

## 真机调试铁律
- **先 `adb devices -l` 看序列号**。曾中途换机（`b18b0725` → `e87a3fe4`／2410DPN6CC 1440×3200），
  两台机的版本/登录态/存档完全不同，日志混着推理会得出完全错误的根因
- **MIUI 下 `adb install -r` 不保证立刻替换 APK**：装完核对设备 `base.apk` 字节数 == 本地产物
- 查日志先 `adb logcat -G 16M`（默认 2 MiB 会被系统噪声冲掉）

## 用户偏好
- MiuiX 风格，**不要橙色主题**；不要无意义的名句/引言
- 倾向紧凑排版：标题字号与留白要压，但大标题要保留
- 作者署名 `@Natsume`，反馈邮箱 `xu.tianhao@outlook.com`（早期误写 `outkook.com`）
- 滑块/配色这类易误触的控件收进二级菜单
- **会自己真机验收并推翻参数**；说"我自己测"时就是「交付并停止」
