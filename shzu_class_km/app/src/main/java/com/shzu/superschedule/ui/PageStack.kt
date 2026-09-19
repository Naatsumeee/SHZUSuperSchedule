package com.shzu.superschedule.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier

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
 * 返回键与返回手势统一交给 [BackHandler]：栈内还有上一级就拦截并 [PageStack.pop]，
 * 到栈底则放行给系统（退出应用）。
 *
 * 页面切换本身由 [AnimatedContent] 做方向感知过渡 —— 进入下一级从右侧滑入，
 * 返回上一级从左侧滑入。
 *
 * > 历史说明：BETA-v1.3 曾实现过系统「预测性返回手势」预览（拖动时上一级页面跟手滑入）。
 * > 该方案在真机上跟手体验不佳、且与系统侧滑手势互相干扰，已于 BETA-v1.3.2 **整体移除**，
 * > 回归标准的返回处理 + 过渡动画。
 */
@Composable
fun <T : Any> PageHost(
    stack: PageStack<T>,
    modifier: Modifier = Modifier,
    content: @Composable (page: T) -> Unit,
) {
    BackHandler(enabled = stack.canGoBack) { stack.pop() }

    Box(modifier = modifier) {
        AnimatedContent(
            targetState = stack.current,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (stack.forward) {
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
