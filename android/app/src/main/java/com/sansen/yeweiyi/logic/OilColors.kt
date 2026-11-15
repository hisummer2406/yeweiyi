package com.sansen.yeweiyi.logic

/**
 * 罐颜色色板（设置页颜色选择的 20 种颜色，序号 1–20）。四端共用的契约：与
 * cloud/fixtures/protocol/v1/oil_color_palette.json 一致（OilColorsTest 校验），云端远程写入只接受这些颜色。
 * 库里存 ARGB（不透明），上报与远程写入用 0xRRGGBB。纯逻辑，Java 侧经 @JvmField/@JvmStatic 调用。
 */
object OilColors {

    @JvmField
    val PALETTE_RGB: IntArray = intArrayOf(
        0xFF0000, 0xFFFF00, 0x0000FF, 0x008000, 0x800080, 0x254D2A, 0x6F1200, 0x000034, 0xF4B2D6, 0x4C0B71,
        0x00456C, 0x00ADEF, 0x342F93, 0xF4F08D, 0xF1645D, 0xFF00CD, 0x413100, 0x139300, 0xC1CD23, 0xFF9900,
    )

    /** 序号 1–20 对应的 ARGB；超出范围返回 0（未设置）。 */
    @JvmStatic
    fun argbOf(index: Int): Int =
        if (index < 1 || index > PALETTE_RGB.size) 0 else toArgb(PALETTE_RGB[index - 1])

    @JvmStatic
    fun toArgb(rgb: Int): Int = (0xFF shl 24) or (rgb and 0xFFFFFF)

    @JvmStatic
    fun isPaletteRgb(rgb: Int): Boolean = PALETTE_RGB.contains(rgb)
}
