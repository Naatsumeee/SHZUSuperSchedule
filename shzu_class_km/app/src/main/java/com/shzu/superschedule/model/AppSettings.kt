package com.shzu.superschedule.model

import kotlinx.serialization.Serializable

/**
 * 一套用户自定义配色集（最多 [MAX_CUSTOM_PALETTES] 套）。
 *
 * 用户第一次关闭「使用预设配色集」时，App 会自动创建一套名为「自定义 1」的
 * 合集，其颜色**复制自马卡龙**，用户可以在此基础上一格格改。
 */
@Serializable
data class CustomPalette(
    /** 显示名，如「自定义 1」 */
    val name: String = "",
    /** 颜色槽位（ARGB），15 个 */
    val colors: List<Long> = emptyList(),
) {
    companion object {
        const val SLOT_COUNT = 15
        /** 最多允许的自定义合集数量 */
        const val MAX_CUSTOM_PALETTES = 3
    }
}

/** 应用设置 */
@Serializable
data class AppSettings(
    /** 当前学期，如 "2026-2027-1" */
    val semester: String = "",
    /** 上次导入时间 */
    val fetchTime: String = "",
    /** 是否使用预设配色集（关闭 = 使用自定义合集） */
    val useDefaultColors: Boolean = true,
    /** 【兼容旧版】扁平的自定义配色槽位，新版本改用 [customPalettes] */
    val customColors: List<Long> = emptyList(),
    /** 配色集编号（见 ColorSchemes 预设） */
    val colorSchemeIndex: Int = 0,
    /** 用户自定义配色合集，最多 3 套 */
    val customPalettes: List<CustomPalette> = emptyList(),
    /** 当前选中的自定义配色合集下标 */
    val activeCustomPalette: Int = 0,
    /** 是否自定义文字颜色 */
    val useCustomTextColor: Boolean = false,
    /** 自定义文字颜色（ARGB） */
    val customTextColor: Long = 0xFF1A1A1AL,
    /**
     * 自定义文字颜色与格子背景对比度过低时，是否自动改用自适应文字色。
     *
     * 由「文字颜色对比度提醒」弹窗里的选项写入：
     * true = 对看不清的格子使用自适应色；false = 保持用户选择。
     */
    val autoTextColorFallback: Boolean = false,

    /** 课程格字体大小（BETA-v1.3.2 默认 12） */
    val fontSize: Float = 12f,
    /** 文字对齐：0 居中 1 居左 2 两端对齐（默认两端对齐） */
    val alignment: Int = 2,
    /**
     * 单节课时（1 课时 = 1 行）的行高，单位 dp。
     *
     * 以它作为**固定标尺**，课表被严格划分为 10 行等距表格，
     * 1-2 行间距与 2-3 行间距完全一致。一门课占 N 课时就渲染 N × rowHeight 的高度。
     */
    val rowHeight: Float = 56f,
    /** 【已废弃】旧「课程格高度」字段，仅保留以兼容旧版本数据 */
    val gridCellHeight: Float = 100f,
    /** 课程格间隔(dp) —— 固定为 0，行距完全由 rowHeight 决定 */
    val gridSpacing: Float = 0f,
    /** 格子内文字与四周边界的间距(dp)（BETA-v1.3.2 默认 3） */
    val cellPadding: Float = 3f,
    /** 课表背景按课时绘制虚线网格（横竖均有） */
    val showGridLines: Boolean = false,
    /** 为课程格绘制细边框 */
    val showCellBorder: Boolean = false,
    /** 课程名是否加粗（默认开启） */
    val boldName: Boolean = true,
    /** 加粗时使用的字重：true=Bold（默认），false=Medium */
    val useBoldWeight: Boolean = true,
    /** 非本周课程是否显示（明显淡化） */
    val showOtherWeeks: Boolean = true,
    /** 是否在课程名前标注[非本周] */
    val markOtherWeeks: Boolean = true,
    /** 过长课程名是否省略（超过 3 行省略） */
    val ellipsizeName: Boolean = true,
    /** 过长教师名是否省略（超过 2 行省略） */
    val ellipsizeTeacher: Boolean = true,
    /** 是否显示教师名字 */
    val showTeacher: Boolean = true,
    /** 非本周课程格淡化强度（5-60），越大越接近原色（默认 25） */
    val otherWeekAlpha: Int = 25,

    /** 开学日期（第一周周一），格式 yyyy-MM-dd */
    val schoolStartDate: String = "",
    /** 上课提醒开关 */
    val reminderEnabled: Boolean = false,
    /** 提前提醒分钟数 */
    val reminderAdvanceMinutes: Int = 10,
    /**
     * 隐藏彩蛋是否已解锁（连续点击「开源说明」内容条 7 下解锁）。
     * 解锁后「系统与交互」里会出现「通知测试」。
     */
    val eggUnlocked: Boolean = false,
) {
    /** 当前生效的自定义配色合集（无则为 null） */
    fun activeCustomPalette(): CustomPalette? =
        customPalettes.getOrNull(activeCustomPalette.coerceAtLeast(0))

    /** 是否存在合法的自定义配色 */
    fun hasCustomPalette(): Boolean = customPalettes.any { it.colors.size >= 3 }

    /**
     * 恢复默认（保留学期/导入时间/开学日期等教务数据）。
     *
     * 注意：自定义配色合集属于用户的劳动成果，**不重置**。
     */
    fun resetAppearance(): AppSettings = copy(
        useDefaultColors = true,
        colorSchemeIndex = 0,
        useCustomTextColor = false,
        customTextColor = 0xFF1A1A1AL,
        autoTextColorFallback = false,
        fontSize = 12f,
        alignment = 2,
        rowHeight = 56f,
        gridSpacing = 0f,
        cellPadding = 3f,
        showGridLines = false,
        showCellBorder = false,
        boldName = true,
        useBoldWeight = true,
        showOtherWeeks = true,
        markOtherWeeks = true,
        ellipsizeName = true,
        ellipsizeTeacher = true,
        showTeacher = true,
        otherWeekAlpha = 25,
    )
}

/** 学期信息 */
@Serializable
data class SemesterEntry(
    /** 学期代码，如 "2026-2027-1" */
    val code: String = "",
    /** 显示名，如 "2026-2027学年第一学期" */
    val name: String = "",
    /** 开学日期 yyyy-MM-dd */
    val startDate: String = "",
    /** 是否已结束 */
    val finished: Boolean = false,
    /** 是否有课表数据 */
    val hasData: Boolean = false,
)

/**
 * 配色集：一套可直接选用的课程格背景色卡。
 *
 * ## 关于「非本周」与配色集的区分度（BETA-v1.3.2 调整）
 *
 * 非本周课程的格子是「向背景色淡化」的（详见 `CourseColors.fadedOpaque`），
 * 观感偏灰。因此**预设配色集不能太灰、太暗**，否则用户会把本周的课
 * 误读成非本周。这一版把「莫兰迪」「石墨灰蓝」两套的饱和度与明度整体提亮，
 * 并新增一套冷色调「冰川冷调」，与暖色的「暖阳」成对。
 */
object ColorSchemes {

    /** 主流的低饱和马卡龙色系（默认） */
    val macaron = listOf(
        0xFF8ECAE6, 0xFF90BE6D, 0xFFF4D06F, 0xFFF4A261,
        0xFFC8A2DC, 0xFF64CCC5, 0xFFF08080, 0xFF9A8CF0,
        0xFFF7B267, 0xFFB5D56A, 0xFF7FB3E8, 0xFFD9DB7A,
        0xFFE88C9A, 0xFF7FD4DE, 0xFFC9B79C,
    ).map { it.toInt().toLong() and 0xFFFFFFFFL }

    /**
     * 莫兰迪柔和色系（提亮版）。
     *
     * 原版明度约 0.62、饱和度约 0.30，配上淡化后的非本周格子几乎分不出差别；
     * 这一版统一把明度提到 0.76 左右、饱和度提到 0.40 左右，
     * 保持「安静、灰调」的气质，但足够清透明亮。
     */
    val morandi = listOf(
        0xFF9FC7D8, 0xFFA8CFA0, 0xFFE3D2A0, 0xFFDCB79A,
        0xFFC0B2D6, 0xFF9CC9C0, 0xFFD8A8B2, 0xFFB0ADD8,
        0xFFDCC2A0, 0xFFBED09A, 0xFFA6C2D8, 0xFFD2D2A0,
        0xFFD8AEAE, 0xFFA8CFD2, 0xFFC6BCAE,
    ).map { it.toInt().toLong() and 0xFFFFFFFFL }

    /** 清透高饱和系（颜色更鲜明、区分度最高） */
    val vivid = listOf(
        0xFF6FB7E8, 0xFF6FC46F, 0xFFF2C94C, 0xFFF2994A,
        0xFFB57BDB, 0xFF3FC1B0, 0xFFEB6E7B, 0xFF7B7BE8,
        0xFFF2994A, 0xFFA5CC4A, 0xFF5A9FE0, 0xFFCFC63F,
        0xFFE06C8A, 0xFF5CC4D0, 0xFFC0A98E,
    ).map { it.toInt().toLong() and 0xFFFFFFFFL }

    /**
     * 石墨灰蓝系（提亮版）。
     *
     * 原版饱和度只有 0.27 上下，和「非本周淡化色」撞车最严重；
     * 这一版把饱和度拉到 0.45 左右、明度提到 0.72 左右，
     * 保留克制、商务的冷调气质，但一眼就能看出是「本周的课」。
     */
    val graphite = listOf(
        0xFF88B4DC, 0xFF8FC4B4, 0xFFD8CB84, 0xFFD2A886,
        0xFFA997D6, 0xFF7CC0B8, 0xFFD494A4, 0xFF95A2DC,
        0xFFD2B78C, 0xFFA8C882, 0xFF7FAADC, 0xFFC6C87E,
        0xFFD19AA6, 0xFF8CBEC8, 0xFFB8AE9C,
    ).map { it.toInt().toLong() and 0xFFFFFFFFL }

    /** 暖阳系（橙黄暖调） */
    val warmSun = listOf(
        0xFFF2B47E, 0xFFE8A76C, 0xFFF4CE7A, 0xFFE9A08A,
        0xFFD9A5B4, 0xFFE8C98F, 0xFFEE9E7E, 0xFFCDA8D6,
        0xFFF0BE72, 0xFFDCC07F, 0xFFE5A87C, 0xFFE0CE85,
        0xFFE89EA5, 0xFFD8B98F, 0xFFD6BBAA,
    ).map { it.toInt().toLong() and 0xFFFFFFFFL }

    /** 冰川冷调（蓝/青/薄荷/蓝紫，与「暖阳」成对的冷色调集） */
    val glacier = listOf(
        0xFF7EC8F0, 0xFF6FD0D8, 0xFF8FA8EE, 0xFF9FD6C0,
        0xFF6FC0DE, 0xFFA793EE, 0xFF86D4E8, 0xFF7FB6E8,
        0xFFB49CEE, 0xFF8ED0CE, 0xFF6FA8E0, 0xFF9CC6F2,
        0xFF84BCDA, 0xFFA8A0F0, 0xFF7CC4C4,
    ).map { it.toInt().toLong() and 0xFFFFFFFFL }

    /** 全部预设（下标即持久化的 colorSchemeIndex，**不要重排已有项**） */
    val all: List<Pair<String, List<Long>>> = listOf(
        "马卡龙" to macaron,
        "莫兰迪" to morandi,
        "清透高饱和" to vivid,
        "石墨灰蓝" to graphite,
        "暖阳" to warmSun,
        "冰川冷调" to glacier,
    )

    /** 默认只陈列这一套（其余需要二次点击展开） */
    const val DEFAULT_VISIBLE_INDEX = 0

    fun byIndex(index: Int): List<Long> = all.getOrElse(index) { all[0] }.second

    fun nameOf(index: Int): String = all.getOrElse(index) { all[0] }.first
}
