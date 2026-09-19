package com.shzu.superschedule.ui

import androidx.compose.ui.graphics.Color
import com.shzu.superschedule.model.AppSettings
import com.shzu.superschedule.model.ColorSchemes
import com.shzu.superschedule.model.CustomPalette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 课程配色。
 *
 * ## 「非本周」为什么不能再靠透明度（BETA-v1.3.2 修复）
 *
 * 旧实现把非本周格子做成 `alpha = 25%` 的半透明色。开启「显示网格线」或
 * 「显示课程格边框」后，**底层的网格虚线和空格的边框会从半透明格子后面透出来**，
 * 视觉上像是格子破了个洞。
 *
 * 现在改为**不透明**：先把本色去饱和、提亮，再按强度与课表背景色 `surface`
 * 混合，最后 `alpha = 1`。淡化观感完全保留，但底层的一切都不再透出。
 */
object CourseColors {

    const val SLOT_COUNT = CustomPalette.SLOT_COUNT

    /** 自定义文字色与背景色的最低对比度（WCAG 大字阈值） */
    const val MIN_CONTRAST = 3.0f

    /** 默认色板（马卡龙） */
    val palette: List<Color> = ColorSchemes.byIndex(0).map { Color(it.toInt()) }

    fun indexOf(name: String): Int = abs(name.hashCode()) % SLOT_COUNT

    fun colorOf(name: String, thisWeek: Boolean): Color {
        val c = palette[abs(name.hashCode()) % palette.size]
        return if (thisWeek) c else fadedOpaque(c, Color.White, 25)
    }

    /** 当前生效色板的原始 ARGB 列表（用于对比度批量校验） */
    fun activePaletteArgbs(settings: AppSettings): List<Long> {
        val custom = settings.activeCustomPalette()?.colors.orEmpty()
        if (!settings.useDefaultColors) {
            if (custom.size >= 3) return custom
            if (settings.customColors.size >= 3) return settings.customColors
            return ColorSchemes.macaron
        }
        return ColorSchemes.byIndex(settings.colorSchemeIndex)
    }

    /** 当前生效色板 */
    fun activePalette(settings: AppSettings): List<Color> =
        activePaletteArgbs(settings).map { argbToColor(it) }

    /**
     * 综合取色。
     *
     * @param surface 课表背景色（本周格子用本色；非本周格子向它淡化）。
     *   深色主题下必须传真实的 surface，否则淡化方向会反掉。
     */
    fun resolve(
        settings: AppSettings,
        name: String,
        thisWeek: Boolean,
        surface: Color = Color.White,
    ): Color {
        val pal = activePalette(settings)
        val c = pal[abs(name.hashCode()) % pal.size]
        return if (thisWeek) c else fadedOpaque(c, surface, settings.otherWeekAlpha)
    }

    /**
     * 非本周课程：去饱和 + 提亮 + 与背景色混合，**保持完全不透明**。
     *
     * `strengthPercent` 语义与旧版 `otherWeekAlpha` 一致：
     * 越小越淡（越接近背景色），越大越接近本色。
     */
    fun fadedOpaque(color: Color, surface: Color, strengthPercent: Int = 25): Color {
        val t = (strengthPercent.coerceIn(5, 100)) / 100f
        // 1) 大幅去饱和
        val gray = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
        var r = color.red + (gray - color.red) * 0.92f
        var g = color.green + (gray - color.green) * 0.92f
        var b = color.blue + (gray - color.blue) * 0.92f
        // 2) 提亮，避免非本周格子过暗
        val lift = 0.22f
        r += (1f - r) * lift
        g += (1f - g) * lift
        b += (1f - b) * lift
        // 3) 与课表背景混合，得到"看起来是半透明、实际不透明"的颜色
        val br = min(1f, max(0f, r)) * t + surface.red * (1f - t)
        val bg = min(1f, max(0f, g)) * t + surface.green * (1f - t)
        val bb = min(1f, max(0f, b)) * t + surface.blue * (1f - t)
        return Color(
            red = min(1f, max(0f, br)),
            green = min(1f, max(0f, bg)),
            blue = min(1f, max(0f, bb)),
            alpha = 1f,
        )
    }

    /** 把可能半透明的颜色合成到不透明背景上（对比度计算前必须先做这一步） */
    fun opaqueOver(color: Color, surface: Color): Color {
        if (color.alpha >= 1f) return color
        val a = color.alpha
        return Color(
            red = color.red * a + surface.red * (1f - a),
            green = color.green * a + surface.green * (1f - a),
            blue = color.blue * a + surface.blue * (1f - a),
            alpha = 1f,
        )
    }

    /** 主文字颜色 */
    fun textColorOf(settings: AppSettings, background: Color): Color {
        if (!settings.useCustomTextColor) return onColor(background)
        val custom = argbToColor(settings.customTextColor)
        // 用户开了「自适应兜底」时，对比度不够的格子自动换自动色
        if (settings.autoTextColorFallback && !readable(custom, background)) {
            return onColor(background)
        }
        return custom
    }

    /** 非本周课程文字：在当前文字色上明显淡化 */
    fun fadedTextColor(settings: AppSettings, background: Color): Color {
        val base = textColorOf(settings, background)
        return base.copy(alpha = 0.55f)
    }

    /**
     * 自动文字色（不读用户的「自定义文字颜色」）。
     * 用于对比度校验与自适应兜底，避免互相递归。
     */
    fun onColor(background: Color): Color {
        val l = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
        return if (l > 0.6f) Color(0xFF1A1A1A) else Color.White
    }

    // ---------------- 对比度（WCAG） ----------------

    private fun relLuminance(c: Color): Float {
        fun ch(v: Float): Float =
            if (v <= 0.03928f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * ch(c.red) + 0.7152f * ch(c.green) + 0.0722f * ch(c.blue)
    }

    /** 两色对比度（1.0 ~ 21.0） */
    fun contrastRatio(a: Color, b: Color): Float {
        val la = relLuminance(a) + 0.05f
        val lb = relLuminance(b) + 0.05f
        return if (la > lb) la / lb else lb / la
    }

    /** 前景色在该背景上是否足够清晰 */
    fun readable(fg: Color, bg: Color, minRatio: Float = MIN_CONTRAST): Boolean =
        contrastRatio(fg, bg) >= minRatio

    /**
     * 检查自定义文字色在当前整条色板上是否有看不清的格子。
     * @return 对比度不足的颜色下标列表（空 = 全部合格）
     */
    fun lowContrastSlots(settings: AppSettings, textColor: Color): List<Int> =
        activePalette(settings).mapIndexedNotNull { index, bg ->
            if (readable(textColor, bg)) null else index
        }

    // ---------------- 标准 HSL 色盘 ----------------

    /** 生成 HSL 色盘：色相均分，可调饱和度/明度 */
    fun hslPalette(
        slots: Int = 36,
        saturation: Float = 0.55f,
        lightness: Float = 0.68f,
    ): List<Color> = (0 until slots).map { i ->
        hslToColor(i * 360f / slots, saturation, lightness)
    }

    /** HSL → Color，h ∈ [0,360)，s/l ∈ [0,1] */
    fun hslToColor(h: Float, s: Float, l: Float): Color {
        val hh = ((h % 360f) + 360f) % 360f
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = l - c / 2f
        val r1: Float
        val g1: Float
        val b1: Float
        when {
            hh < 60f -> { r1 = c; g1 = x; b1 = 0f }
            hh < 120f -> { r1 = x; g1 = c; b1 = 0f }
            hh < 180f -> { r1 = 0f; g1 = c; b1 = x }
            hh < 240f -> { r1 = 0f; g1 = x; b1 = c }
            hh < 300f -> { r1 = x; g1 = 0f; b1 = c }
            else -> { r1 = c; g1 = 0f; b1 = x }
        }
        return Color(
            red = min(1f, max(0f, r1 + m)),
            green = min(1f, max(0f, g1 + m)),
            blue = min(1f, max(0f, b1 + m)),
            alpha = 1f,
        )
    }

    /** Color → HSL（h 0-360, s 0-1, l 0-1） */
    fun colorToHsl(color: Color): Triple<Float, Float, Float> {
        val r = color.red
        val g = color.green
        val b = color.blue
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val l = (maxC + minC) / 2f
        val d = maxC - minC
        if (d == 0f) return Triple(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f)).coerceAtLeast(0.0001f)
        val h = when (maxC) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return Triple(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l)
    }

    fun argbToColor(argb: Long): Color = Color(argb.toInt())

    fun colorToArgb(color: Color): Long {
        val a = (color.alpha * 255f).roundToInt().toLong() and 0xFF
        val r = (color.red * 255f).roundToInt().toLong() and 0xFF
        val g = (color.green * 255f).roundToInt().toLong() and 0xFF
        val b = (color.blue * 255f).roundToInt().toLong() and 0xFF
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
