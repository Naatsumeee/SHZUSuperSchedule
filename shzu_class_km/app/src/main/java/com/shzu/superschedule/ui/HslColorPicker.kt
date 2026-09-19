package com.shzu.superschedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 标准 HSL 色盘取色器。
 * 用色相条 + 饱和度/明度滑杆，用户可选取任意颜色，而不是从固定色块里挑。
 */
@Composable
fun HslColorPickerDialog(
    initial: Color,
    title: String = "选择颜色",
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
) {
    val hsl = remember(initial) { CourseColors.colorToHsl(initial) }
    var hue by remember { mutableFloatStateOf(hsl.first) }
    var sat by remember { mutableFloatStateOf(hsl.second.coerceIn(0.15f, 0.95f)) }
    var light by remember { mutableFloatStateOf(hsl.third.coerceIn(0.3f, 0.9f)) }

    val current = CourseColors.hslToColor(hue, sat, light)

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Medium)

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(current)
                            .border(
                                0.5.dp,
                                MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f),
                                RoundedCornerShape(10.dp),
                            ),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "HSL(${hue.toInt()}°, ${(sat * 100).toInt()}%, ${(light * 100).toInt()}%)",
                            fontSize = 12.sp,
                        )
                        Text(
                            text = "预览",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }

                // 色相条
                Spacer(Modifier.height(14.dp))
                Text("色相", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(6.dp))
                HueBar(
                    hue = hue,
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                    onChange = { hue = it },
                )

                // 饱和度 / 明度
                Spacer(Modifier.height(10.dp))
                LabeledSlider(
                    label = "饱和度",
                    value = sat,
                    range = 0f..1f,
                    display = "${(sat * 100).toInt()}%",
                    onChange = { sat = it },
                )
                LabeledSlider(
                    label = "明度",
                    value = light,
                    range = 0f..1f,
                    display = "${(light * 100).toInt()}%",
                    onChange = { light = it },
                )

                // 常见调色推荐：同色相不同明度
                Spacer(Modifier.height(4.dp))
                Text("同色系", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.35f, 0.5f, 0.65f, 0.8f, 0.9f).forEach { l ->
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CourseColors.hslToColor(hue, sat, l))
                                .clickable { light = l },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(
                        onClick = { onConfirm(current) },
                        modifier = Modifier.weight(1f),
                    ) { Text("确定") }
                }
            }
        }
    }
}

/** 色相条：可点击/拖动选色相 */
@Composable
private fun HueBar(
    hue: Float,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit,
) {
    val colors = remember {
        (0..36).map { CourseColors.hslToColor(it * 10f, 0.6f, 0.62f) }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.horizontalGradient(colors))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { off ->
                        onChange((off.x / size.width * 360f).coerceIn(0f, 359f))
                    },
                ) { change, _ ->
                    onChange((change.position.x / size.width * 360f).coerceIn(0f, 359f))
                }
            }
            .clickable(indication = null, interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource()) {
                // 点击由 dragStart 处理；此处兜底
            }
            .drawBehind {
                val x = (hue / 360f) * size.width
                drawRect(
                    color = Color.White,
                    topLeft = Offset(x - 2f, 0f),
                    size = androidx.compose.ui.geometry.Size(4f, size.height),
                )
            },
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(text = display, fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
