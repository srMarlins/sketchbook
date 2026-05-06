package com.sketchbook.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SampleRefTest {
    @Test fun defaultsAreNullExceptRawPath() {
        val s = SampleRef(rawPath = "x.wav")
        assertEquals("x.wav", s.rawPath)
        assertNull(s.relativePathType)
        assertNull(s.originalFileSize)
        assertNull(s.originalCrc)
        assertNull(s.lastModDate)
        assertEquals(false, s.hasOriginalFileRefSibling)
    }
}
