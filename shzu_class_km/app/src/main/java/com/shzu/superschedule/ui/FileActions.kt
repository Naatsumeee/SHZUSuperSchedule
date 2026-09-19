package com.shzu.superschedule.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.shzu.superschedule.data.ScheduleStore

/**
 * 轻提示的**统一显示时长**。
 *
 * Android 的 `Toast` 只能设 `LENGTH_SHORT`（约 2s）/ `LENGTH_LONG`（约 3.5s）两档，
 * 无法直接指定毫秒数，官方也不打算开放。这里统一按 `LENGTH_SHORT` 弹出，
 * 再用 `Handler` 延时 `cancel()` 收掉，从而把显示时间**精确压到 1.2s** ——
 * 既保留系统 toast 的样式与层级，又能在看全文案的前提下不糊在屏幕上挡视线。
 */
private const val TOAST_DURATION_MS = 1200L

private val toastHandler = Handler(Looper.getMainLooper())
private var currentToast: Toast? = null
private var pendingDismiss: Runnable? = null

/** 统一的轻量 toast（显示 0.5s） */
fun toast(context: Context, message: String) {
    showToast(context, message)
}

/**
 * 语义上表示「更重要的提示」，显示时长与 [toast] 相同（0.5s）。
 * 保留该函数是为了让调用点体现语义差异，也便于以后单独调整时长。
 */
fun toastLong(context: Context, message: String) {
    showToast(context, message)
}

private fun showToast(context: Context, message: String) {
    // 用 applicationContext，避免持有 Activity 引用
    val app = context.applicationContext
    toastHandler.post {
        // 连续弹多条时，先把上一条收掉，避免排队堆积
        pendingDismiss?.let { toastHandler.removeCallbacks(it) }
        currentToast?.cancel()
        val t = Toast.makeText(app, message, Toast.LENGTH_SHORT)
        currentToast = t
        t.show()
        val dismiss = Runnable {
            t.cancel()
            if (currentToast === t) currentToast = null
        }
        pendingDismiss = dismiss
        toastHandler.postDelayed(dismiss, TOAST_DURATION_MS)
    }
}

/**
 * 课表 JSON 的 **导入 / 导出**（基于 SAF，无需任何存储权限）。
 *
 * - 导出：走 `CreateDocument`，用户在系统文件选择器里选保存位置和文件名；
 * - 导入：走 `OpenDocument`，拿到 uri 后读取文本，交给
 *   [ScheduleStore.parseAndValidate] 做严格校验，校验通过才回调 [onValid]。
 *
 * 返回值是一个 `ScheduleFileActions`，直接调 `export()` / `import()` 即可。
 */
class ScheduleFileActions(
    val export: (fileName: String, content: String) -> Unit,
    val import: () -> Unit,
)

/**
 * 创建导入导出能力。
 *
 * @param onValid 校验通过时回调，参数为解析好的存档
 * @param onError 校验失败/读取失败时回调，参数为给用户看的原因
 */
@Composable
fun rememberScheduleFileActions(
    fileName: String,
    contentProvider: () -> String,
    onValid: (com.shzu.superschedule.data.ScheduleArchive) -> Unit,
    onError: (String) -> Unit,
): ScheduleFileActions {
    val context = androidx.compose.ui.platform.LocalContext.current

    // 用 rememberUpdatedState 保证回调始终指向最新 lambda，避免闭包捕获旧值
    val latestValid = rememberUpdatedState(onValid)
    val latestError = rememberUpdatedState(onError)
    val latestContent = rememberUpdatedState(contentProvider)

    val createLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { latestContent.value() }.getOrNull()
        if (text.isNullOrBlank()) {
            toastLong(context, "导出失败：当前没有可导出的课表数据")
            return@rememberLauncherForActivityResult
        }
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: return@runCatching false
            true
        }.getOrDefault(false)
        if (ok) toastLong(context, "已导出课表到所选位置")
        else latestError.value("导出失败：无法写入所选文件")
    }

    val openLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: ""
        }.getOrElse {
            latestError.value("读取文件失败：${it.message ?: "未知错误"}")
            return@rememberLauncherForActivityResult
        }

        when (val result = ScheduleStore.parseAndValidate(text)) {
            is com.shzu.superschedule.data.ImportResult.Ok ->
                latestValid.value(result.archive)
            is com.shzu.superschedule.data.ImportResult.Invalid ->
                latestError.value("导入已取消 —— ${result.reason}")
        }
    }

    return remember(context, fileName) {
        ScheduleFileActions(
            export = { name, _ ->
                runCatching {
                    createLauncher.launch(name.ifBlank { "schedule.json" })
                }.onFailure {
                    // 部分定制系统没有 DocumentsUI，回退到系统分享
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_TEXT, latestContent.value())
                    }
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(send, "分享课表 JSON"),
                        )
                    }.onFailure { e ->
                        latestError.value("导出失败：${e.message ?: "系统不支持文件选择器"}")
                    }
                }
            },
            import = {
                runCatching {
                    openLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }.onFailure { e ->
                    latestError.value("打开文件选择器失败：${e.message ?: "未知错误"}")
                }
            },
        )
    }
}
