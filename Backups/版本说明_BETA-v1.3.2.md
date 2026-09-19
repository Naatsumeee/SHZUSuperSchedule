# BETA-v1.3.2 — 当前版本

| 项目 | 值 |
|------|-----|
| APK | `石大超级课表_BETA-v1.3.2.apk`（14.78 MB） |
| 包名 | `com.shzu.superschedule` |
| versionName / versionCode | **`BETA-v1.3.2`** / `15` |
| 构建时间 | 2026-09-19 18:53（构建序号 v145） |
| compileSdk / targetSdk / minSdk | 37 / 36 / 24 |
| 技术栈 | Kotlin + Compose Multiplatform 1.11.1 + MiuiX 0.9.3 + AGP 9.4.0 + Gradle 9.7.1 |

> **本版起所有版本号统一加 `BETA-` 前缀**，明示这是测试版。
> App 内「关于 → 更新日志」里显示的也是 `BETA-v1.3.2`。

## 定位

v1.3.2 是本轮迭代的主版本，改动量最大，集中在四块：
**课表排布与配色的精细化**、**底栏真实背景模糊（本轮最难的一块）**、**通知测试能力**、
**交互细节（返回手势、toast 时长）**。

## 改动明细（App 内更新日志原文）

- 移除预测性返回手势功能（跟手预览体验不佳，回归标准返回处理与过渡动画）；
- 修复网格线纵轴与星期列完全错位的问题（改为按真实列布局计算）；
- 非本周课程改为「向底色淡化但不透明」，不再透出底层网格线与格子边线；
- 时间冲突不再叠字：占课时少的课程优先占据重叠课时（1-2 节有 A、1-4 节有 B 时，
  1-2 节显示 A，第 3-4 节显示 B），并可在课程详情里直接指定优先显示哪一门；
- 冲突课程列表默认展开，无需二次点击；
- 多教师课程可在课程详情里切换周视图显示 1 位或全部教师；
- 省略过长内容改为「课程名超过 3 行」「教师名超过 2 行」；
- 默认值调整：格子高度 56dp、字体大小 12、文字与边界间距 3；
- 莫兰迪与石墨灰蓝整体提亮，新增冷色调配色集「冰川冷调」；
- 新增自定义配色合集（最多 3 套，选用自定义配色时自动创建并复制马卡龙）；
- 配色集默认只陈列「马卡龙」，再点一下当前项即可展开其余配色集；
- 自定义文字颜色时校验与底色的对比度，看不清时可选择自适应文字色；
- 设置页新增「通知测试」（连点「开源说明」7 下解锁、长按可再次关闭）用于检查通知权限，
  测试通知会随机取一节课按真实提醒格式展示；
- 反馈邮箱点一次提示、点两次即可复制到剪贴板；
- 应用内提示（toast）显示时间统一为 1.2 秒；
- **应用图标替换为 Icon-256**（此前一直使用系统默认图标）；
- **修正反馈邮箱拼写错误**（`outkook.com` → `outlook.com`）；
- 底栏改为**真实的背景高斯模糊**（磨砂玻璃），且模糊半径沿栏高渐变（栏底最糊、栏顶最清）；
- 底栏底色改为顶边完全透明的纵向渐变，去掉溢出到栏外的模糊光晕与顶部硬边；
- 设置页分组顺序调整，「显示」紧随「教务」，「课表存档」移到「系统与交互」之前；
- 「今日课程」与「本周课表」的大标题字号与留白完全对齐；
- 设置页大标题改用 MiuiX 规范字号与留白；
- 切到「课表」页再切回设置时，自定义样式二级菜单与滚动位置都会被保留。

## 底栏模糊：本轮最难的一块（完整复盘）

目标是「底栏毛玻璃 = 模糊的确实是底栏**背后**的页面内容」，期间踩了两个连环坑。

### 坑 1：`GraphicsLayer.record` 的重载选错

`GraphicsLayer.record(density, layoutDirection, size, block)` 会**另建一个 `CanvasDrawScope`**，
导致 block 里的 `drawContent()` 画到屏幕上而不是录进图层。
正确用法是 **`DrawScope.record(size) { drawContent() }`** 这个成员扩展——
它才会把本节点的 canvas 换成图层画布。
（定位手段：反编译 `LayoutNodeDrawScope` 字节码，里面有自描述的错误字符串。）

### 坑 2（决定性根因）：带 `renderEffect` 的节点会把内容裁在自身边界内

带 `renderEffect`（含 `Modifier.blur`）的节点会被渲染进一张**按节点自身尺寸分配**的离屏缓冲，
**边界外的内容整块丢弃**（哪怕 `clip = false`）。
当时的写法是在底栏的 band Canvas 里 `translate(top = -2885)` 把整页图层拖进 315px 高的底栏画布
→ 内容全在边界外 → **一像素不画**，底栏退回「透明栏直接透出实时页面」。

### 最终解法：三层图层（`ui/BlurBar.kt` 已按此重写）

1. `contentLayer`：整页内容（`record(size) { drawContent() }`，**`drawContent()` 必须是第一个绘制调用**）；
2. `sliceLayer`：**尺寸 = 底栏尺寸**，内容 = `translate(top = contentTopY - barTopY) { drawLayer(contentLayer) }`
   —— **先切好再模糊**，内容自然落在自己的边界内；
3. 5 个 band Canvas：先 `graphicsLayer { renderEffect = BlurEffect(r, r, TileMode.Clamp) }`，
   再 `drawLayer(sliceLayer)`。

### 渐变模糊（用户：「模糊过于生硬，请叠上一层渐变底色」）

- 半径自下而上递减：`radius = BLUR_MAX_DP * (BAND_TOP_RATIO + (1 - ratio) * i / (n - 1))`，
  实测 44dp 起点 → **165 / 134 / 103 / 72 / 41 px**（density 3.75）。
- 每层用 `CompositingStrategy.Offscreen` + `drawRect(verticalGradient, blendMode = DstIn)` 淡出，
  淡出跨度取 **1.6 个档位**使相邻层交叉 → 消除「一级一级跳」。最底层整条不透明垫底。
- 底色改为**顶边完全透明** → 0.45 → 0.65 的纵向渐变，高光也用渐变（1px 实线太生硬）。
  实测顶边跨界差仅 **1.012**（栏上方基线差 0.000），几乎无硬边。

### 附带修正

- `Modifier.blur` 内部其实是 `clip = true`（`BlurredEdgeTreatment.Rectangle` 等价 `RectangleShape`），
  所以要模糊"栏外内容"必须改用**显式 `RenderEffect`**（`graphicsLayer` 默认 `clip = false`）。

### 实测数据

- 决定性信号（同一滚动位置）：`blurDp = 0` → `44`，底栏 `dx` 2.635 → 0.754、`dy` 3.440 → 1.227；
  洋红诊断边框行 `mean = 170.00 / std = 0.00`（不模糊）→ `mean = 201.6 / min = 180 / max = 203`（被糊开）。
- 性能（设置页 6 次 swipe）：模糊开 494 帧 / **0 Janky** / p95 10ms / p99 12ms；
  直通 512 帧 / 0 Janky / p95 5ms / p99 6ms ⇒ **约 +4~5ms 每帧，60Hz 无卡顿**。

## 通知测试（三项）

1. **`Notifier.sendTest(context, courses)` 随机取一节课**：标题 `上课提醒 · <课名>`，
   正文 `周几 · 地点 · 节次 · 时间`，`setSubText("通知测试")` 以便区分；课表为空时回退通用文案。
2. **长按「通知测试」可关闭**（`eggUnlocked = false`），toast 提示可再连点「开源说明」7 下恢复。
   实现：MiuiX `BasicComponent` **没有 `onLongPress` 参数**，且其内部 clickable 会在 Main 阶段
   抢先吃掉 up，外层再挂指针手势压不住 → 改用 `Modifier.combinedClickable(onClick, onLongClick)`
   统一裁决（`Card` 内部也是这个写法，此版本无需 `@OptIn`）。
3. 连点「开源说明」**第 3 次起** toast 改为「还剩 x 次进入测试模式」（x = 7 - 已点次数，2.5s 超时归零）。

## toast 时长

- 初版按要求压到 **0.5s**，用户实测偏短，最终定为 **1.2s**。
- Android `Toast` 只有 `LENGTH_SHORT`(~2s) / `LENGTH_LONG`(~3.5s) 两档，**无法直接指定毫秒**。
  解法：仍按 `LENGTH_SHORT` 弹出，再用 `Handler(Looper.getMainLooper())`
  `postDelayed(dismiss, 1200L)` 到点 `cancel()`；连续弹时先 `removeCallbacks` + `cancel()` 上一条防堆积。
- **必须用 `context.applicationContext`**，否则持 Activity 引用易泄漏。
- `toast()` / `toastLong()` 签名不变，统一走私有 `showToast()`，调用点零改动。
- ⚠️ toast **不进 `uiautomator dump`**，验证要用
  `dumpsys window windows | grep -ci toast` 高频采样计数。

## 本版构建序列

| 构建号 | 主要内容 |
|--------|----------|
| v140 | v1.3.2 主体迭代 |
| v141 | 底栏模糊定位（band 一像素不画的排查） |
| v142 | **三层图层方案重写 `BlurBar.kt`** |
| v143 | 渐变模糊 + 通知测试三项 |
| v144 | toast 压到 0.5s |
| v145 | toast 改为 1.2s |
| **v146** | **应用图标改为 Icon-256、反馈邮箱修正为 outlook.com（最终出包）** |

### v146 修订详情

- **应用图标**：工程根目录 `Icon-256.ico`（256×256 RGBA）转成 Android 图标，
  生成 `mipmap-mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi` 五档 `ic_launcher.png`
  （48 / 72 / 96 / 144 / 192 px），并在 `AndroidManifest.xml` 的 `<application>` 上加
  `android:icon="@mipmap/ic_launcher"`。
  此前 manifest **完全没有指定 icon**，所以一直显示系统默认图标。
  验证：`aapt2 dump badging` 已输出 `application-icon-160/240/320/480/640` 五个密度。
- **反馈邮箱**：`SettingsPage.kt` 的 `AUTHOR_EMAIL` 由 `xu.tianhao@outkook.com`
  （早期误拼，少一个 l）改为 **`xu.tianhao@outlook.com`**。
  同步更新了 `docs/APP使用说明.md`、`PROJECT_PROMPT.md`、
  `History/prompts/项目约束提示词.md`、`.workbuddy/memory/MEMORY.md`。
  验证：dex 中 `outlook.com` 出现 3 次、`outkook.com` 出现 0 次。
- 体积：14776771 → **14927459 bytes**（+150KB，即图标资源）。

## 其他沉淀

- **MiuiX API 备忘（0.9.3）**：`top.yukonga.miuix.kmp.basic` 提供 `BasicComponent` / `Card` /
  `Scaffold` / `NavigationBar` / `NavigationBarItem` / `Slider` / `Switch` / `Text` / `Button` /
  `TextField` / `IconButton` / `Icon` / `SmallTitle`。
  ⚠️ **没有** `SmallTitle` 之外的通用「分组标题」；`SmallTitle` 自带左缩进，要顶格需显式传 `PaddingValues`。
  下拉菜单用 `top.yukonga.miuix.kmp.preference` 的 `OverlayDropdownPreference`（Overlay 渲染，无需独立窗口）。
  图标在 `top.yukonga.miuix.kmp.icon.MiuixIcons` + `...icon.extended.Back` 等。
- **MiuiX 没有内置 blur 修饰符**，需自己用 `RenderEffect`；
  `top.yukonga.miuix.kmp.shader.isRenderEffectSupported()` 可判断是否支持。
- **教务系统对接**：入口 `https://jwgl.shzu.edu.cn`，课表页 `.../jsxsd/xskb/xskb_list.do`；
  WebView **双 UA**（移动端 UA 命中移动版登录页；导入课表时切桌面 UA 让宽表格完整显示）；
  「学年学期」下拉 `select#xnxq01id`，兜底 `#xnm`(学年) × `#xqm`(学期) 联级框；
  学期代码统一规范化为 `2026-2027-1`；静默抓取学期列表 = 读
  `CookieManager.getCookie("https://jwgl.shzu.edu.cn")` → `HttpURLConnection` 拉 HTML → Jsoup 解析。
