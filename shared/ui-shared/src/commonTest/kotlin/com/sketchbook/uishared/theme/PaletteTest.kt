package com.sketchbook.uishared.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaletteTest {
    @Test
    fun abletonPaletteCovers1Through14() {
        val keys = AbletonPalette.keys.sorted()
        assertEquals((1..14).toList(), keys)
    }

    @Test
    fun abletonContrastTableMatchesPaletteKeys() {
        val keys = AbletonContrast.keys.sorted()
        assertEquals((1..14).toList(), keys)
        assertTrue(AbletonContrast.values.all { it == OnStrip.Light || it == OnStrip.Dark })
    }
}
