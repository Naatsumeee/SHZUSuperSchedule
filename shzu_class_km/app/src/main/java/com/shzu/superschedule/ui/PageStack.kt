package com.shzu.superschedule.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * 轻量页面栈（自带过渡方向）。
 *
 * - [push] 进入下一级，动画为「从右滑入」
 * - [pop] 返回上一级，动画为「从左滑入」；栈底再按返回才交给系统（退出应用）
 * - [canGoBack] 供 BackHandler 判断是否拦截返回
 */
class PageStack<T : Any>(initial: T) {

    private val stack: SnapshotStateList<T> = mutableStateListOf(initial)

    /** 最近一次操作方向：true = 前进，false = 后退 */
    private var forwardFlag = true

    val forward: Boolean get() = forwardFlag

    val current: T get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    val depth: Int get() = stack.size

    fun push(page: T) {
        if (stack.last() == page) return
        forwardFlag = true
        stack.add(page)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        forwardFlag = false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun resetTo(page: T) {
        forwardFlag = false
        stack.clear()
        stack.add(page)
    }
}

/**
 * 页面切换容器。
 *
 * 返回键与返回手势统一交给返回处理器：栈内还有上一级就拦截并 [PageStack.pop]，
 * 到栈底则放行给系统（退出应用）。
 *
 * 页面切换本身由 [AnimatedContent] 做方向感知过渡 —— 进入下一级从右侧滑入，
 * 返回上一级从左侧滑入。
 *
 * ## 预测性返回（可选，2026-10-01 重新加回）
 *
 * [predictiveBack] = true 时改用 `PredictiveBackHandler`：拖动过程中当前页
 * 会**跟手**右移，上一级页面从左侧跟着滑入；未划到位就松手则弹回原位。
 *
 * > 历史说明：BETA-v1.3 曾**默认启用**过预测性返回，因真机跟手体验不佳、
 * > 且与系统侧滑手势互相干扰，在 BETA-v1.3.2 **整体移除**。
 * > 现在作为**可选开关**回归（默认关闭）—— 想用的人自己开、
 * > 不想要的人不受影响，这才是当初那个问题的正解，而不是永久砍掉。
 *
 * ⚠️ 需 **Android 13（API 33）及以上**：低版本 `PredictiveBackHandler`
 * 不会被系统触发，行为自动等同普通返回，不需要额外判断。
 */
@Composable
fun <T : Any> PageHost(
    stack: PageStack<T>,
    modifier: Modifier = Modifier,
    predictiveBack: Boolean = false,
    content: @Composable (page: T) -> Unit,
) {
    // 手势进度（0..1）。仅在预测性返回拖动期间非零，松手/取消后归零。
    var gestureProgress by remember { mutableFloatStateOf(0f) }

    if (predictiveBack && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PredictiveBackHandler(enabled = stack.canGoBack) { progress ->
            runCatching {
                progress.collect { event -> gestureProgress = event.progress }
                // 划到位：真正出栈，并复位进度（进度由 pop 后的过渡动画接管）
                gestureProgress = 0f
                stack.pop()
            }.onFailure {
                // 未划到位就松手：进度归零，下面的动画会把页面弹回原位
                gestureProgress = 0f
            }
        }
    } else {
        BackHandler(enabled = stack.canGoBack) { stack.pop() }
    }

    Box(modifier = modifier) {
        // 手势进度映射为横向位移：拖动时跟手，松手时用 180ms 弹回。
        // 拖动期间用 0 时长（完全跟手），复位时才走动画。
        val progress by animateFloatAsState(
            targetValue = gestureProgress,
            animationSpec = tween(durationMillis = if (gestureProgress == 0f) 180 else 0),
            label = "predictiveBack",
        )

        // 用容器自身宽度换算位移，而不是取屏幕宽度 ——
        // 分屏 / 折叠屏 / 横屏下容器宽度与屏幕宽度并不相等。
        var layerWidth by remember { mutableIntStateOf(0) }

        AnimatedContent(
            targetState = stack.current,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { layerWidth = it.width }
                // 跟手位移叠在过渡之上：progress = 1 时恰好移出容器宽度。
                // 预测性返回关闭时 progress 恒为 0，这一层无副作用。
                .offset { IntOffset((progress * layerWidth).roundToInt(), 0) },
            transitionSpec = {
                // 手势已经"划到位"，此时 pop 触发的过渡应当为静止，
                // 否则会与跟手位移叠加成"先弹回再滑走"的割裂观感。
                if (gestureProgress > 0f) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else if (stack.forward) {
                    (
                        slideInHorizontally(tween(280), initialOffsetX = { it / 5 }) +
                            fadeIn(tween(280))
                        ) togetherWith (
                        slideOutHorizontally(tween(280), targetOffsetX = { -it / 8 }) +
                            fadeOut(tween(180))
                        )
                } else {
                    (
                        slideInHorizontally(tween(280), initialOffsetX = { -it / 8 }) +
                            fadeIn(tween(280))
                        ) togetherWith (
                        slideOutHorizontally(tween(280), targetOffsetX = { it / 5 }) +
                            fadeOut(tween(180))
                        )
                }
            },
            label = "page",
        ) { page ->
            content(page)
        }
    }
}
