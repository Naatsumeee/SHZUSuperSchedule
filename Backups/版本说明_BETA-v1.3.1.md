# v1.3.1 — 课表布局模型定型 + 桌面小组件修复 + 下拉闪退修复

| 项目 | 值 |
|------|-----|
| APK | `石大超级课表_BETA-v1.3.1.apk`（14.73 MB） |
| 包名 | `com.shzu.superschedule` |
| versionName / versionCode | `1.3.1` / `14` |
| 构建时间 | 2026-09-19 02:49 |
| compileSdk / targetSdk | 37 / 36 |

## 定位

把 v1.3 引入的网格模型**彻底做严**（行距严格相等、长格不被压缩），
同时修掉了一批影响日常使用的硬伤：下拉菜单点击闪退、桌面小组件载入失败、课表不持久化。

## 改动明细

### 课表布局（本版核心）

- 课表改为**以「课程高度」滑块为唯一标尺的严格 10 行等距表格**，彻底消除行距不等。
- 移除午休 / 晚休分隔条，**忽略下课时间**，所有课按同样高宽渲染。
- 连排课时**合并长格**，长度严格 = 课时数 × 格高（2 课时 2 格，3 课时 3 格）。
- 修复**长格被父容器压成 1 行**的问题（改用浮层精确按行定位）。
- 修复**非本周课程与本周课程叠在同一格互相压字**的问题。

### 底栏

- 底栏改为**只模糊背景，图标与文字保持清晰**。

### 数据与持久化

- 课表以**本地 json 文件持久化**，下次打开直接读取，不再重复导入。
- 新增课表 **JSON 导入 / 导出**，导入前严格校验并给出失败原因。
- 修复刷新可读学期失效 —— **根因：取 Cookie 的 URL 未带 `/jsxsd` 路径，拿不到登录会话**。

### 文字与样式

- 课程名默认使用 Bold 字重（可切 Medium）；课程格文字默认两端对齐、字号 11。
- `【非本周】` 改为 `[非本周]`。

### 稳定性

- **修复下拉菜单点击闪退**。

### 页面

- 今日页加回「今日课程」大标题；非本周课程不出现在当日课表。

### 桌面小组件

- 修复小组件**行控件 id 重复**导致显示错乱。
- 修复小组件「更多尺寸」档位缺失，以及桌面编辑页「载入窗口小部件时出现问题」。
- 修复小组件内容区域不随所选尺寸变化的问题（改为按真实可用高度分配行高）。

## 本版沉淀下来的技术要点（很重要，后面反复用到）

### 桌面小组件

- **RemoteViews 白名单**：布局里**禁用 `<View>`**（`android.view.View` 不在白名单），
  色条 / 分隔线要用 `ImageView`。违规时桌面膨胀会抛异常，但**被 MIUI 静默吞掉**，
  表现为永久灰色的「载入窗口小部件时出现问题」；而 `previewLayout` 走普通渲染路径看起来正常，
  **极具迷惑性**。
- **尺寸档位靠多个 `<receiver>`**：MIUI 的选择器把每个 receiver 当成一个条目。
  共 5 个：自适应 `ScheduleWidgetProvider` + Small / Wide / Tall / Big（2×2 / 4×2 / 2×4 / 4×4），
  各配独立 xml info。
- `initialLayout` 指向加载态布局（小米官方规范），`previewLayout` 指向正式布局。
- **卡片要填满格子**：根布局 `match_parent`，行高按 `OPTION_APPWIDGET_MIN/MAX_HEIGHT`
  的真实 dp 扣除固定开销后均分（`setMinimumHeight`），不要用 `wrap_content + minHeight`。
- 排查方法：logcat 过滤 tag `flutter :`（hyper_launcher_app 是 Flutter 应用），
  关键行 `[AppWidgetHostView] INFLATE path` / `_buildWithLayoutParams`；
  **「有 INFLATE path 但没有 `_buildWithLayoutParams`」= 膨胀被吞 → 查白名单**。
- Provider 推送前自检：`rv.apply(context, FrameLayout(context))` 捕获异常打日志。
- shell 无法广播 `APPWIDGET_UPDATE`（SecurityException）；触发更新用 monkey 拉起 app 或重启桌面。

### 课表布局

- 整个课表 = 固定 10 行 × 7 列网格，10 行对应 1-10 课时，**行距严格相等**。
- 「课程高度」滑块（`AppSettings.rowHeight`，默认 52dp，范围 34-96）统一调节所有课高度，
  并决定网格行距：`rowStride = rowHeight + CELL_GAP * 2`。
- 一门课占 N 个课时就铺满 N 格（周视图用 `Layout` + `Constraints.fixed` 的 overlay 定位，
  多行格直接 `Constraints.fixed` 才不会被 clamp）。
- 非本周课程：过滤掉与本周块重叠的；其余按 `from-to` 分组合并成一个块并标 `mergedWeeks`
  （如「11,13 周」）；今日页只显示本周课程。
- 空位由 `EmptyCell` 渲染并可点击加课；被长格覆盖的行由 `occupiedByDay` 标记后只留 `Spacer`。
- 虚线网格 `Modifier.gridBackground(enabled)`：7 条竖线 + 每行 1 条横线，
  `PathEffect.dashPathEffect(10f, 8f)`。
- 今日页节数按**科目（课程名去重）**统计，一天最多 5 门。
- 课表 JSON 持久化在 `ScheduleStore`（`files/schedule/<学期>.json`）。

### MiuiX 弹层

- ⚠️ **MiuiX 弹层依赖 `LocalNavigationEventDispatcherOwner`**：
  该 CompositionLocal 只在 NavHost 中自动提供；本项目没有导航库，
  必须在 `MainActivity` 的 `setContent` 外层**手动提供 `NavigationEventDispatcherOwner`**，
  否则点开任何下拉 / 弹层就 `IllegalStateException` 闪退。
  依赖：`androidx.navigationevent:navigationevent(-compose):1.1.2`。
