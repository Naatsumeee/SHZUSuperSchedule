package com.shzu.superschedule.ui

import android.os.Build
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 底栏半透明底色 + **渐变高斯模糊**（磨砂玻璃）。
 *
 * ## 实现要点
 *
 * Compose 没有"模糊我背后的东西"的 API，只能：
 * 1. 把页面内容录进一张离屏 [GraphicsLayer]（[recordBarBackdrop]）；
 * 2. 从这张图层里**抠出底栏正后方的那一条**，录成第二张「切片图层」（尺寸 = 底栏）；
 * 3. 在底栏位置把切片图层画出来，并对这份**拷贝**做模糊。
 *
 * ### ⚠️ 踩坑记录（BETA-v1.3.2，真机实测，全部是"静默失效"级别）
 *
 * 1. **`GraphicsLayer.record {}` 必须用 `DrawScope` 的成员扩展
 *    `layer.record(size) { … }`**，不能用 [GraphicsLayer] 自己的成员
 *    `record(density, layoutDirection, size, block)`。前者会临时把本节点的
 *    `DrawContext.canvas` 换成图层画布再跑 block，于是 block 里的 `drawContent()`
 *    才真的画进图层；后者另建一个 `CanvasDrawScope`，`drawContent()` 仍画到屏幕，
 *    图层里一片空白 —— 现象是"底栏糊了个寂寞，只剩半透明纯色"。
 * 2. **`record {}` 里 `drawContent()` 必须是第一个绘制调用。** 一旦在它前面先画了
 *    别的东西（例如为补底色而 `drawRect(surface)`），内容就完全录不进图层。
 *    底色请由内容侧的 `Modifier.background(surface)` 提供（见 AppRoot）。
 * 3. **内容容器必须铺一层不透明底色**，否则快照在背景处透明，模糊结果会直接透出
 *    底下的清晰内容，看起来像"完全没模糊"。
 * 4. 🔴 **带 `renderEffect`（含 `Modifier.blur`）的节点，其内容必须落在**该节点自身
 *    的边界内。** 设了 `RenderEffect` 后该节点会被渲进一张**按节点尺寸分配**的离屏
 *    缓冲，边界外的内容整块丢弃 —— 哪怕 `clip = false`。之前直接把页面图层
 *    `translate(top = -栏高)` 拖进底栏画布，内容全在边界外，于是那一层一像素都不画
 *    （表现为底栏只是"透明栏透出实时页面"）。**正解：先把要用的那一条内容录成一张
 *    与底栏等大的切片图层，再对切片做模糊**（见 [recordBarBackdrop] 的第 2 步）。
 * 5. **`Modifier.blur` 内部带 `clip = true`**（`BlurredEdgeTreatment.Rectangle`
 *    等价于 `RectangleShape`），会再叠一层裁剪；本项目统一改用显式
 *    `renderEffect = BlurEffect(r, r, TileMode.Clamp)`（`graphicsLayer` 默认 `clip = false`）。
 *    `TileMode.Clamp` = 边缘复制，否则上下两端会各出现一个"越采越透"的假渐变。
 *
 * ## 模糊半径沿栏高渐变
 *
 * `RenderEffect` 只能给一个**固定**半径，做不出渐变，所以叠若干层固定半径的
 * 模糊层（默认 [BAND_COUNT] 层），自下而上半径递减，每层再乘一个纵向透明度遮罩：
 *
 * ```
 *   半径 44dp ────────────────────── 整条不透明（垫底，栏底最糊）
 *   半径 33dp ████████████░░░░░░░░░░ 上半段不透明，下半段淡出→露出 44dp
 *   半径 22dp ██████░░░░░░░░░░░░░░░░ 更上段不透明，淡出→露出 33dp
 *   半径 11dp ███░░░░░░░░░░░░░░░░░░░ 栏顶不透明，淡出→露出 22dp（栏顶最清）
 * ```
 *
 * 绘制顺序是「先画最糊的、再用较清晰的压上去」，于是任一高度上总有一层
 * 完全不透明 —— 合成结果始终不透明，模糊量则**自下而上平滑递减**。
 *
 * 最后再叠一层**顶边全透明的纵向渐变底色**，让底栏与页面之间没有可见分界线。
 */
private const val BAR_ALPHA_TOP = 0.45f
private const val BAR_ALPHA_BOTTOM = 0.65f

/** **栏底**（模糊最强处）的高斯模糊半径（dp），向上递减到 [BAND_TOP_RATIO] 倍 */
private const val BLUR_MAX_DP = 44f

/** 栏顶那一层相对 [BLUR_MAX_DP] 的比例，决定渐变跨度（越小渐变越明显） */
private const val BAND_TOP_RATIO = 0.25f

/** 渐变模糊叠几层。层数越多过渡越顺，代价是每帧多几次离屏模糊。 */
private const val BAND_COUNT = 5

private const val TAG = "ShzuBar"

/** 平台是否支持真实高斯模糊（`RenderEffect` 自 Android 12 / API 31 起提供） */
internal fun blurSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 底栏参数（可被真机调试覆盖，见 [rememberBarTuning]）。
 */
internal data class BarTuning(
    val alphaTop: Float,
    val alphaBottom: Float,
    /** 栏底的最大模糊半径（dp），沿栏高向上递减 */
    val blurDp: Float,
    /** 额外纵向位移（px），仅用于真机定位问题 */
    val extraOffset: Float,
    /** 渐变模糊的层数 */
    val bands: Int,
    /** 诊断模式（0=正常），见 [FrostedLayer] 里的分支说明 */
    val dbg: Int,
)

/**
 * 读取底栏参数。
 *
 * 真机调试入口：把
 * `/sdcard/Android/data/com.shzu.superschedule/files/bar_diag.txt`
 * 写成 `alphaTop,alphaBottom,blurDp,extraOffset,bands,dbg`
 * （可只写前几项，其余取默认值），重启 App 即可生效；文件不存在则用默认常量。
 *
 * `dbg` 诊断模式：0=正常；1/2=不模糊（只验证切片图层有没有内容）；
 * 3=`Modifier.blur` 模糊；4=`RenderEffect` 模糊。非 0 时会跳过半透明底色，
 * 并在栏的左上角画洋红边框、在内容图层里多画两条洋红定位条。
 *
 * ⚠️ `adb shell` 创建的文件默认 `-rw-rw----`（属主 shell），App 打不开，
 * 写完记得 `chmod 666`。调完请删掉该文件。
 */
@Composable
private fun rememberBarTuning(): BarTuning {
    val context = LocalContext.current
    return remember(context) {
        val def = BarTuning(
            alphaTop = BAR_ALPHA_TOP,
            alphaBottom = BAR_ALPHA_BOTTOM,
            blurDp = BLUR_MAX_DP,
            extraOffset = 0f,
            bands = BAND_COUNT,
            dbg = 0,
        )
        runCatching {
            val f = File(context.getExternalFilesDir(null), "bar_diag.txt")
            if (!f.exists()) return@runCatching def
            val v = f.readText().trim().split(",")
            BarTuning(
                alphaTop = v.getOrNull(0)?.trim()?.toFloatOrNull() ?: def.alphaTop,
                alphaBottom = v.getOrNull(1)?.trim()?.toFloatOrNull() ?: def.alphaBottom,
                blurDp = v.getOrNull(2)?.trim()?.toFloatOrNull() ?: def.blurDp,
                extraOffset = v.getOrNull(3)?.trim()?.toFloatOrNull() ?: 0f,
                bands = v.getOrNull(4)?.trim()?.toIntOrNull() ?: def.bands,
                dbg = v.getOrNull(5)?.trim()?.toIntOrNull() ?: 0,
            ).also { Log.d(TAG, "bar tuning from file: $it") }
        }.onFailure { Log.w(TAG, "bar tuning read failed: $it") }.getOrDefault(def)
    }
}

/**
 * 底栏背景模糊所需的离屏图层。
 *
 * - [contentLayer]：整页内容（[recordBarBackdrop] 每帧录制）；
 * - [sliceLayer]：从 [contentLayer] 抠出的、**底栏正后方那一条**，尺寸与底栏相同。
 *   模糊必须作用在它身上 —— 带 `renderEffect` 的节点只能渲染自己边界内的内容
 *   （见文件头踩坑记录第 4 条）。
 *
 * [contentTopY] / [barTopY] 是内容层与底栏在**窗口坐标系**下的纵坐标，
 * 两者相减即底栏相对内容层的偏移，据此平移拷贝，让"底栏正后方的那一段内容"
 * 正好落进切片。
 *
 * ⚠️ [recordBarBackdrop] 必须挂在**不含 padding** 的位置（见 AppRoot 的注释），
 * 否则 `drawWithContent` 的绘制原点与 `onGloballyPositioned` 报告的节点坐标不一致，
 * 偏移会相差一个状态栏高度。
 */
@Stable
class BarBackdropState internal constructor(
    internal val contentLayer: GraphicsLayer,
    internal val sliceLayer: GraphicsLayer,
) {
    /** 页面内容层在窗口中的纵坐标 */
    internal var contentTopY by mutableFloatStateOf(0f)

    /** 底栏在窗口中的纵坐标 */
    internal var barTopY by mutableFloatStateOf(0f)

    /** 底栏的像素尺寸（由底栏自身测量上报） */
    internal var barW by mutableIntStateOf(0)
    internal var barH by mutableIntStateOf(0)

    /** 上一次录制用的像素尺寸，仅用于在尺寸变化时打一次日志 */
    internal var lastRecW by mutableIntStateOf(0)
    internal var lastRecH by mutableIntStateOf(0)
}

/** 创建底栏背景模糊所需的图层状态（需在 Scaffold 的父级调用） */
@Composable
fun rememberBarBackdrop(): BarBackdropState {
    val contentLayer = rememberGraphicsLayer()
    val sliceLayer = rememberGraphicsLayer()
    return remember(contentLayer, sliceLayer) {
        BarBackdropState(contentLayer, sliceLayer)
    }
}

/**
 * 挂到**页面内容容器**上（且要挂在 `padding` 之前）：
 * 每帧把内容录进离屏图层，再从中抠出底栏那一条录成切片，供底栏做背景模糊取用。
 * 内容本身的显示不受影响。
 *
 * ⚠️ 录图层时 `drawContent()` 必须是 `record {}` 里的**第一个**绘制调用；
 * ⚠️ 必须用 `DrawScope` 的成员扩展 `record(size) { … }`（详见文件头踩坑记录）。
 */
@Composable
fun Modifier.recordBarBackdrop(state: BarBackdropState): Modifier {
    val tuning = rememberBarTuning()
    return this
        .onGloballyPositioned { state.contentTopY = it.positionInWindow().y }
        .drawWithContent {
            val w = size.width.toInt()
            val h = size.height.toInt()
            if (w > 0 && h > 0) {
                if (w != state.lastRecW || h != state.lastRecH) {
                    state.lastRecW = w
                    state.lastRecH = h
                    Log.d(TAG, "record content layer ${w}x${h}")
                }
                // ① 整页内容
                state.contentLayer.record(size = IntSize(w, h)) {
                    this@drawWithContent.drawContent()
                    if (tuning.dbg != 0) {
                        // 诊断：图层内定位标记（能在底栏看到，说明 drawContent 录进去了）
                        drawRect(
                            color = Color.Magenta,
                            topLeft = Offset(0f, 200f),
                            size = androidx.compose.ui.geometry.Size(120f, 12f),
                        )
                        drawRect(
                            color = Color.Magenta,
                            topLeft = Offset(0f, 2900f),
                            size = androidx.compose.ui.geometry.Size(120f, 12f),
                        )
                    }
                }
                // ② 底栏那一条切片：尺寸与底栏一致，内容因此**落在自己的边界内**，
                //    后续才能对切片施加 RenderEffect（见文件头踩坑记录第 4 条）。
                val bw = state.barW
                val bh = state.barH
                if (bw > 0 && bh > 0) {
                    val off = state.contentTopY - state.barTopY + tuning.extraOffset
                    state.sliceLayer.record(size = IntSize(bw, bh)) {
                        translate(top = off) { drawLayer(state.contentLayer) }
                    }
                }
            }
            drawContent()
        }
}

/**
 * 底栏容器：底层为「渐变背景模糊 + 半透明底色」的磨砂层，上层是传入的导航栏内容。
 */
@Composable
fun BlurredBarContainer(
    state: BarBackdropState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        FrostedLayer(state)
        content()
    }
}

/**
 * 磨砂层：先把底栏正后方的页面内容**按高度渐变地模糊**后画上来，
 * 再叠半透明底色与顶部柔光。绘制顺序不可颠倒。
 */
@Composable
private fun BoxScope.FrostedLayer(state: BarBackdropState) {
    val tuning = rememberBarTuning()
    val supported = blurSupported()

    // 半透明底色：用背景色本身做底，才能和页面无缝衔接
    val base: Color = MiuixTheme.colorScheme.surface
    val top = base.copy(alpha = tuning.alphaTop)
    val bottom = base.copy(alpha = tuning.alphaBottom)

    // 每层只打一次日志，避免每帧刷屏
    val logged = remember(tuning) { BooleanArray(16) }

    Box(
        modifier = Modifier
            .matchParentSize()
            // 把一切绘制严格裁在底栏自身的矩形内，杜绝像素溢出到栏外
            .clipToBounds()
            .onGloballyPositioned {
                state.barW = it.size.width
                state.barH = it.size.height
                state.barTopY = it.positionInWindow().y
                Log.d(
                    TAG,
                    "bar blur: supported=$supported contentTopY=${state.contentTopY} " +
                        "barTopY=${state.barTopY} bar=${it.size.width}x${it.size.height} " +
                        "tuning=$tuning",
                )
            },
    ) {
        // 1) 渐变模糊的内容切片（栏底最糊，往上逐渐变清）
        //
        // ⚠️ 这些 Canvas 必须**直接**是底栏 Box 的子节点，中间不能再夹一层 Box：
        // 夹了之后绘制链会失效（真机实测：那一层的 Canvas 一帧都不会执行）。
        if (supported) {
            val n = tuning.bands.coerceIn(2, 8)
            Log.d(TAG, "progressive blur: n=$n max=${tuning.blurDp}dp dbg=${tuning.dbg}")

            if (tuning.dbg != 0) {
                // ── 诊断分支：把切片原样（或模糊后）画出来，不叠任何底色 ──
                val canvasMod = when (tuning.dbg) {
                    // 4) 绕过 Modifier.blur，直接用 RenderEffect
                    4 -> Modifier.graphicsLayer {
                        renderEffect = BlurEffect(
                            tuning.blurDp, tuning.blurDp, TileMode.Clamp,
                        )
                    }
                    // 3) Modifier.blur
                    3 -> Modifier.blur(
                        radius = tuning.blurDp.dp,
                        edgeTreatment = BlurredEdgeTreatment.Rectangle,
                    )
                    else -> Modifier
                }
                Canvas(
                    modifier = Modifier
                        .matchParentSize()
                        .then(canvasMod),
                ) {
                    if (!logged[10]) {
                        logged[10] = true
                        Log.d(
                            TAG,
                            "dbg${tuning.dbg} canvas draw ${size.width}x${size.height} " +
                                "sliceSize=${state.sliceLayer.size}",
                        )
                    }
                    drawLayer(state.sliceLayer)
                    // 洋红边框：证明这个 Canvas 确实在画
                    drawRect(
                        color = Color.Magenta,
                        topLeft = Offset.Zero,
                        size = androidx.compose.ui.geometry.Size(size.width, 6f),
                    )
                    drawRect(
                        color = Color.Magenta,
                        topLeft = Offset.Zero,
                        size = androidx.compose.ui.geometry.Size(6f, size.height),
                    )
                }
                return@Box
            }

            // 从最模糊（栏底）到最清晰（栏顶）依次叠加
            for (i in n - 1 downTo 0) {
                val radiusDp = tuning.blurDp *
                    (BAND_TOP_RATIO + (1f - BAND_TOP_RATIO) * i / (n - 1))
                // 最模糊的一层整条不透明，不需要遮罩；其余层按纵向渐变淡出
                val maskMod = if (i == n - 1) {
                    Modifier
                } else {
                    // 相邻两层的淡出段**互相重叠**（淡出跨度 = 1.6 个档位），
                    // 这样上下来回都是渐变交叉，不会出现"一级一级跳"的生硬感。
                    // 最底层（i == n-1）整条不透明垫底，所以合成结果任何时候都不透明，
                    // 交叉区露出的是更糊的一层，而不是页面本身。
                    val stops = arrayOf(
                        0f to Color.White,
                        i.toFloat() / n to Color.White,
                        ((i + 1.6f) / n).coerceAtMost(1f) to Color.Transparent,
                    )
                    Modifier
                        // Offscreen 合成：让下面的 DstIn 遮罩只作用于本层的模糊结果
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(colorStops = stops),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                }

                Canvas(
                    modifier = Modifier
                        .matchParentSize()
                        .then(maskMod)
                        // 对切片做模糊。切片内容本来就落在本画布边界内，所以这里
                        // 可以安全地用 RenderEffect（不会被离屏缓冲裁掉）。
                        .graphicsLayer {
                            val r = radiusDp.dp.toPx()
                            if (!logged[i]) {
                                logged[i] = true
                                Log.d(TAG, "band $i graphicsLayer r=$r px")
                            }
                            renderEffect = BlurEffect(r, r, TileMode.Clamp)
                        },
                ) {
                    if (!logged[i + 8]) {
                        logged[i + 8] = true
                        Log.d(
                            TAG,
                            "band $i draw ${size.width}x${size.height} r=${radiusDp}dp",
                        )
                    }
                    drawLayer(state.sliceLayer)
                }
            }
        }

        // 2) 渐变底色 + 顶部柔光（压在模糊层之上）
        Canvas(modifier = Modifier.matchParentSize()) {
            // 渐变底色：**顶边完全透明**，向下逐渐加深到 alphaBottom。
            // 这一步是让底栏"不生硬"的关键 —— 顶边不留硬边，而是把自己的底色
            // 一点点叠上去，底栏与页面之间没有可见的分界线，
            // 只有"越往下越白/越实"的过渡，磨砂感才自然。
            drawRect(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to base.copy(alpha = 0f),
                        0.45f to top,
                        1.00f to bottom,
                    ),
                ),
            )
            // 顶部玻璃高光：也用渐变（1px 实心白线会显得很生硬），
            // 只占最上面约 10% 的高度，迅速淡出。
            drawRect(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to Color.White.copy(alpha = 0.30f),
                        0.10f to Color.Transparent,
                    ),
                ),
            )
        }
    }
}
