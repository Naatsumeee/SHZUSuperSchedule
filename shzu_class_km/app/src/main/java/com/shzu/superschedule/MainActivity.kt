package com.shzu.superschedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.shzu.superschedule.ui.AppRoot
import com.shzu.superschedule.ui.AppTheme

/**
 * 应用唯一的 Activity。
 *
 * ## 为什么要手动提供 NavigationEventDispatcherOwner（v1.3.2 闪退修复）
 *
 * MiuiX 的弹层体系（`MiuixPopupUtils.MiuixPopupHost`）内部会调用
 * `NavigationBackHandler` 来接管返回键，而 `NavigationBackHandler` 依赖
 * `LocalNavigationEventDispatcherOwner`。
 *
 * 这个 CompositionLocal **只在 `NavHost` / `NavBackStackEntry` 里才会被自动提供**。
 * 本应用用的是自己手写的 `Scaffold` + 底部导航（没有引入 navigation-compose），
 * 于是点开任何一个下拉菜单 / 弹层时，`MiuixPopupHost` 一组合就抛：
 *
 *     java.lang.IllegalStateException:
 *     No NavigationEventDispatcher was provided via LocalNavigationEventDispatcherOwner
 *         at top.yukonga.miuix.kmp.utils.MiuixPopupUtils$PopupEntry(MiuixPopupUtils.kt:530)
 *
 * 而且是在**组合阶段**抛的，直接走 `AndroidRuntime: FATAL EXCEPTION: main`，
 * 表现就是用户反馈的「点一下就立即退出应用」。
 *
 * 修复方式：在 `setContent` 的最外层手动创建并提供一个
 * `NavigationEventDispatcherOwner`，让 MiuiX 的返回键接管有宿主可用。
 * 这样不必为了一个返回键回调就把整个导航体系换成 navigation-compose。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 手动兜底提供 NavigationEventDispatcherOwner —— 详见类注释。
            val dispatcher = remember { NavigationEventDispatcher() }
            val owner = remember { NavigationDispatcherOwner(dispatcher) }
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                AppTheme {
                    AppRoot()
                }
            }
        }
    }
}

/** [NavigationEventDispatcherOwner] 的最小实现，仅用于把 dispatcher 挂到组合树里。 */
private class NavigationDispatcherOwner(
    private val dispatcher: NavigationEventDispatcher,
) : NavigationEventDispatcherOwner {
    override val navigationEventDispatcher: NavigationEventDispatcher get() = dispatcher
}
