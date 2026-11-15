package com.sansen.yeweiyi.logic

import com.sansen.yeweiyi.logic.thingsboard.FixtureLoader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 色板是四端共用的契约：与 cloud/fixtures/protocol/v1/oil_color_palette.json 一致。 */
class OilColorsTest {

    @Test
    fun paletteMatchesSharedFixture() {
        val colors = FixtureLoader.loadProtocolV1("oil_color_palette.json").getAsJsonArray("colors")
        val expected = IntArray(colors.size()) { colors.get(it).asInt }
        assertEquals(20, expected.size)
        assertArrayEquals(expected, OilColors.PALETTE_RGB)
    }

    @Test
    fun argbAndMembership() {
        assertEquals(0xFFFF0000.toInt(), OilColors.argbOf(1))
        assertEquals(0xFFFF9900.toInt(), OilColors.argbOf(20))
        assertEquals(0, OilColors.argbOf(21))
        assertEquals(0xFF0000FF.toInt(), OilColors.toArgb(0x0000FF))
        assertTrue(OilColors.isPaletteRgb(0xFF0000))
        assertFalse(OilColors.isPaletteRgb(0x123456))
        assertFalse(OilColors.isPaletteRgb(0))
    }
}
