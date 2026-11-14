package com.sansen.yeweiyi.logic;

/**
 * 罐颜色色板（设置页颜色选择的 20 种颜色，序号 1–20）。四端共用的契约：与
 * cloud/fixtures/protocol/v1/oil_color_palette.json 一致（OilColorsTest 校验），云端远程写入只接受这些颜色。
 * 库里存 ARGB（不透明），上报与远程写入用 0xRRGGBB。纯 Java。
 */
public final class OilColors {

    public static final int[] PALETTE_RGB = {
            0xFF0000, 0xFFFF00, 0x0000FF, 0x008000, 0x800080, 0x254D2A, 0x6F1200, 0x000034, 0xF4B2D6, 0x4C0B71,
            0x00456C, 0x00ADEF, 0x342F93, 0xF4F08D, 0xF1645D, 0xFF00CD, 0x413100, 0x139300, 0xC1CD23, 0xFF9900,
    };

    private OilColors() {
    }

    /** 序号 1–20 对应的 ARGB；超出范围返回 0（未设置）。 */
    public static int argbOf(int index) {
        if (index < 1 || index > PALETTE_RGB.length) {
            return 0;
        }
        return toArgb(PALETTE_RGB[index - 1]);
    }

    public static int toArgb(int rgb) {
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    public static boolean isPaletteRgb(int rgb) {
        for (int c : PALETTE_RGB) {
            if (c == rgb) {
                return true;
            }
        }
        return false;
    }
}
