# 石大超级课表 — 项目长期笔记

## 技术栈
- Kotlin + Jetpack Compose（Compose Multiplatform 1.11.1）+ **MiuiX 0.9.3**（`top.yukonga.miuix.kmp`）
- AGP 9.4.0 + Gradle 9.7.1；`compileSdk = 37`、`minSdk = 24`、`targetSdk = 36`
- 包名 `com.shzu.superschedule`，应用名「石大超级课表」
- 当前版本 **`BETA-v1.3.2`（versionCode = 15）**，产物 `石大超级课表_BETA-v1.3.2.apk`
- 工程目录 `shzu_class_km/`，源码 `app/src/main/java/com/shzu/superschedule/`
- 工具链 `C:\Users\xutia\WorkBuddy\android-toolchain\`（jdk17 / android-sdk / gradle-9.7.1 / gradle-home）

## ⚠️ 必须遵守：Kotlin 字符串模板
`"$foo()"` 会编译失败（`Function invocation 'xxx()' expected`），
`"$foobar"` 里中文字符会被当成标识符的一部分（`"$sections小节"` 解析成 `sections小节`）。
**凡是变量后面紧跟 `(`、中文、字母数字，一律写 `${...}`。**
本项目已因此踩坑 3 次（`$startMinutes()`、`$sections小节`、`$androidVersionName()`）。
改完代码务必自检：
```bash
grep -rn '\$[a-zA-Z_][a-zA-Z0-9_]*()' app/src/main/java/
```

## 构建
```bash
powershell -ExecutionPolicy Bypass -File build_km.ps1
```
- 输出日志 `km_build_v<N>.txt`。**注意：PowerShell `Tee-Object` 写出的是 UTF-16**，
  `grep -q "BUILD SUCCESSFUL"` 会误判为失败。要这样看：
  `cat km_build_v13.txt | tr -d '\000' | sed 's/\r//' | grep -E "^e: |BUILD"`
- 只看编译错误：`grep -E "^e: " -A3`

## MiuiX API 备忘（0.9.3，实测可用）
- `top.yukonga.miuix.kmp.basic`：`BasicComponent` / `Card` / `Scaffold` / `NavigationBar` /
  `NavigationBarItem` / `Slider` / `Switch(checked, onCheckedChange, modifier, colors, enabled)` /
  `Text` / `Button` / `TextField` / `IconButton` / `Icon` / `SmallTitle(text, modifier, padding)`
- **没有** `SmallTitle` 之外的通用「分组标题」；`SmallTitle` 自带左缩进，要顶格需显式传 `PaddingValues`
- `top.yukonga.miuix.kmp.preference`：`OverlayDropdownPreference(items, selectedIndex, title,
  renderInRootScaffold, onSelectedIndexChange)` ← 下拉菜单用它（Overlay 渲染，无需独立窗口）
- `top.yukonga.miuix.kmp.icon.MiuixIcons` + `top.yukonga.miuix.kmp.icon.extended.Back` 等图标
- `top.yukonga.miuix.kmp.shader.isRenderEffectSupported()`；**MiuiX 没有内置 blur 修饰符**，需自己用 `RenderEffect`
- ⚠️ **MiuiX 弹层依赖 `LocalNavigationEventDispatcherOwner`**（v1.3.1 闪退根因）：
  该 CompositionLocal 只在 NavHost 中自动提供；本项目无导航库，必须在 MainActivity
  `setContent` 外层手动提供 `NavigationEventDispatcherOwner`，否则点开任何下拉/弹层即
  `IllegalStateException` 闪退。依赖：`androidx.navigationevent:navigationevent(-compose):1.1.2`
- ⚠️ **`NavigationBar(color: Color = MiuixTheme.colorScheme.surface)` 内部直接 `.background(color)`**：
  默认值是不透明 surface，会把底下自制的磨砂/模糊层整个盖住。要透出背景必须显式传
  `color = Color.Transparent` **且** `showDivider = false`，否则会像是「参数没生效」
- MiuiX `Scaffold` 绘制顺序：`bodyContentPlaceable.place(0,0)` **先于** `bottomBarPlaceable.place(...)`
  → 底栏绘制时内容层已是本帧内容，做「模糊底栏背后的内容」**零帧延迟**，不需额外延迟一帧

## 底栏真实背景模糊（BETA-v1.3.2，`ui/BlurBar.kt`）
Compose **没有**现成「背景模糊」修饰符；`RenderEffect` 只能糊自己这一层的绘制内容。
**唯一可行路 = 两层 GraphicsLayer「拷贝再模糊」**：
1. 内容层挂 `Modifier.recordBarBackdrop(state)`：`drawWithContent { graphicsLayer.record { drawContent() }; drawLayer(graphicsLayer) }`
2. `onGloballyPositioned { positionInWindow().y }` 存 `contentTopY` / `barTopY`
3. 底栏容器 `drawBehind` 里：`barLayer.renderEffect = BlurEffect(24f, 24f, TileMode.Clamp)`，
   `barLayer.record(size) { translate(top = -(barTopY - contentTopY)) { drawLayer(contentLayer) } }`，
   再 `drawLayer(barLayer)`；最后叠 `drawRect(Brush.verticalGradient(0.78f→0.93f))` + 顶部 1px 高光
- 模糊的是**内容层的拷贝**，所以内容本身保持清晰、只有底栏区域被糊
- `BlurEffect` 用 `remember(density)` 缓存，别每帧新建；容器记得 `clipToBounds()`
- API：`androidx.compose.ui.graphics.rememberGraphicsLayer()`（实际在 `GraphicsLayerScopeKt`）、
  `GraphicsLayer.record(density, layoutDirection, IntSize, DrawScope.()->Unit)` / `renderEffect` / `drawLayer()`；
  `androidx.compose.ui.graphics.BlurEffect(x, y, TileMode)`
- 降级：`RenderEffect` 需 **API 31+**（`blurSupported()`），低版本只留半透明底色
- 教训：只调 alpha / 叠半透明色**永远出不来磨砂效果**，别在这上面浪费时间

## 桌面小组件（v1.3.1 实战经验，5 provider 架构）
- **RemoteViews 白名单**：布局里**禁用 `<View>`**（android.view.View 不在白名单），
  色条/分隔线用 `ImageView`。违规时桌面膨胀抛异常但**被 MIUI 静默吞掉**，永久灰色
  「载入窗口小部件时出现问题」；而 `previewLayout` 走普通渲染路径看起来正常，极具迷惑性
- **尺寸档位靠多 `<receiver>`**：MIUI 选择器把每个 receiver 当一个条目；现有 5 个：
  ScheduleWidgetProvider(自适应) + Small/Wide/Tall/Big(2×2/4×2/2×4/4×4)，各配独立 xml info
- `initialLayout` 指向 `widget_schedule_loading`（加载态，小米官方规范），`previewLayout` 指向正式布局
- **卡片要填满格子**：根布局 `match_parent`，行高按 `OPTION_APPWIDGET_MIN/MAX_HEIGHT`
  真实 dp 扣除固定开销后均分（`setMinimumHeight`），不要用 wrap_content+minHeight
- 排查桌面问题：logcat 过滤 tag `flutter :`（hyper_launcher_app 是 Flutter 应用），
  关键行 `[AppWidgetHostView] INFLATE path / _buildWithLayoutParams`；
  「INFLATE path 后无 _buildWithLayoutParams」= 膨胀被吞 → 查白名单
- Provider 推送前自检：`rv.apply(context, FrameLayout(context))` 捕获异常打日志
- shell 无法广播 `APPWIDGET_UPDATE`（SecurityException）；触发更新用 monkey 拉起 app 或重启桌面

## 课表布局模型（v1.3.1 起）
- 整个课表 = **固定 10 行 × 7 列**网格；10 行对应 1-10 课时，**行距严格相等**
- 「课程高度」滑块（`AppSettings.rowHeight`，默认 52dp，34-96）v1.3.1 回归：统一调节所有课高度并决定网格行距；`rowStride = rowHeight + CELL_GAP*2`
- 一门课占 N 个课时就铺满 N 格（周视图用 `Layout`+`Constraints.fixed` 的 overlay 定位，多行格直接 Constraints.fixed 不被 clamp）
- 非本周课程：过滤掉与本周块重叠的；其余按 `from-to` 分组合并成一个块并标 `mergedWeeks`（如 "11,13 周"）；今日页只显示本周课程
- 空位由 `EmptyCell` 渲染并可点击加课；被长格覆盖的行由 `occupiedByDay` 标记后只留 `Spacer`
- 虚线网格 `Modifier.gridBackground(enabled)`：7 条竖线 + 每行 1 条横线，`PathEffect.dashPathEffect(10f, 8f)`
- 今日页节数按**科目（课程名去重）**统计，一天最多 5 门
- 文字默认两端对齐（alignment=2）；加粗默认 Bold 可切 Medium（useBoldWeight）
- **BETA-v1.3.2 默认值**：格子高度 `rowHeight=56dp`、字号 12、文字与边界间距 3；
  隐藏过长：课程名 >3 行省略、教师名 >2 行省略
- **非本周课程改为「向背景色混合」的不透明淡化**（BETA-v1.3.2），不再用半透明 ——
  否则会透出底层的网格线与格子边线
- 多教师课程：`Course.teacherDisplayIndex: Int = -1`（-1 表示默认第一位；越界 `coerceIn` 回退），
  `showAllTeachers=true` 时全列；UI 用单选圆点逐个列出「全部(N位)」+ 每位教师
- 桌面端配色板（BETA-v1.3.2）：预设只陈列「马卡龙」，**再点一次已选配色**才展开其余 6 套
  （含新增冷色调集「冰川冷调」）；另有**自定义配色合集**最多 3 套（新建复制马卡龙，15 槽位）
- 设置页分组顺序（BETA-v1.3.2）：**教务 → 显示 → 课表存档 → 系统与交互 → 关于**
- 课表 JSON 持久化：`ScheduleStore`（files/schedule/<学期>.json），配置页可导入/导出（导入严格校验）

## 导航
- `ui/PageStack.kt`：自研 `PageStack<T>`（`push`/`pop`/`canGoBack`/`forward`）+ `PageHost`（`AnimatedContent` 方向感知过渡）
- 预测性返回：`MainActivity` 走 `ComponentActivity`，manifest 加 `android:enableOnBackInvokedCallback="true"`，
  运行时用 `PredictiveBackHandler`；`predictiveBackSupported()` = `SDK_INT >= 33`
- **BETA-v1.3.2 预测性返回改成「划到位即播完」**（用户反馈松手才动/补播一段很割裂）：
  手势进度**实时驱动双层过渡** —— 当前页 `translationX = dragFraction * size.width`，
  底层上一级页从 `-25%`（`-(1-dragFraction)*size.width*0.25f`）归位到 0，progress=1 时上层已铺满；
  **提交手势时置 `skipAnim=true` 再 `stack.pop()`**，`transitionSpec` 对这次切页返回
  `EnterTransition.None` / `ExitTransition.None`（画面已到位，不重播），`LaunchedEffect(stack.depth)` 复位
- `PageStack` 提供 `peekBack()` 拿上一级页面用于底层渲染
- 二级页面用 `SubPageTopBar(title, onBack)`，返回按钮在**页面顶部**而非页尾

## 教务系统对接
- 入口 `https://jwgl.shzu.edu.cn`；课表页 `https://jwgl.shzu.edu.cn/jsxsd/xskb/xskb_list.do`
- WebView 双 UA：移动端 UA 命中移动版登录页；导入课表切桌面 UA 让宽表格完整显示
- 「学年学期」下拉框：`select#xnxq01id`，兜底 `#xnm`(学年) × `#xqm`(学期) 联级框
- 学期代码统一规范化为 `2026-2027-1`
- 静默抓取学期列表 = 读 `CookieManager.getCookie("https://jwgl.shzu.edu.cn")` → `HttpURLConnection` 拉 HTML → Jsoup 解析

## 用户偏好
- 界面用 MiuiX 风格，**不要橙色主题**；不要无意义的名句/引言
- 倾向紧凑排版：标题字号与留白要压，但大标题要保留
- 作者署名 `@Natsume`，反馈邮箱 `xu.tianhao@outlook.com`
  （早期版本误写成 `outkook.com`，BETA-v1.3.2 修正）
- 滑块/配色这类易误触的控件收进二级菜单
