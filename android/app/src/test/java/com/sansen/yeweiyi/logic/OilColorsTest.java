package com.sansen.yeweiyi.logic;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.sansen.yeweiyi.logic.thingsboard.FixtureLoader;

import org.junit.Test;

/** 色板是四端共用的契约：与 cloud/fixtures/protocol/v1/oil_color_palette.json 一致。 */
public class OilColorsTest {

    @Test
    public void paletteMatchesSharedFixture() {
        JsonArray colors = FixtureLoader.loadProtocolV1("oil_color_palette.json").getAsJsonArray("colors");
        int[] expected = new int[colors.size()];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = colors.get(i).getAsInt();
        }
        assertEquals(20, expected.length);
        assertArrayEquals(expected, OilColors.PALETTE_RGB);
    }

    @Test
    public void argbAndMembership() {
        assertEquals(0xFFFF0000, OilColors.argbOf(1));
        assertEquals(0xFFFF9900, OilColors.argbOf(20));
        assertEquals(0, OilColors.argbOf(21));
        assertEquals(0xFF0000FF, OilColors.toArgb(0x0000FF));
        assertTrue(OilColors.isPaletteRgb(0xFF0000));
        assertFalse(OilColors.isPaletteRgb(0x123456));
        assertFalse(OilColors.isPaletteRgb(0));
    }
}
