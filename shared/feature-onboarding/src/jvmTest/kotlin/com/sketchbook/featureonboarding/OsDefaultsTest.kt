package com.sketchbook.featureonboarding

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OsDefaultsTest {
    private lateinit var savedOsName: String

    @BeforeTest fun saveOs() { savedOsName = System.getProperty("os.name").orEmpty() }
    @AfterTest fun restoreOs() { System.setProperty("os.name", savedOsName) }

    @Test fun `Windows plugin folders include VST3 path`() {
        System.setProperty("os.name", "Windows 11")
        val result = defaultPluginFolders()
        assertTrue(result.any { it.contains("VST3", ignoreCase = true) })
    }

    @Test fun `Mac plugin folders include Library Audio Plug-Ins`() {
        System.setProperty("os.name", "Mac OS X")
        val result = defaultPluginFolders()
        assertTrue(result.any { it.contains("/Library/Audio/Plug-Ins") })
    }

    @Test fun `unknown OS returns empty plugin folders`() {
        System.setProperty("os.name", "Plan 9 from Bell Labs")
        assertEquals(emptyList(), defaultPluginFolders())
    }

    @Test fun `Windows projects-root suggestion ends with Live Projects`() {
        System.setProperty("os.name", "Windows 11")
        val r = defaultProjectsRootSuggestion()
        assertNotNull(r)
        assertTrue(r.endsWith("Live Projects"))
    }

    @Test fun `Mac projects-root suggestion includes Ableton User Library`() {
        System.setProperty("os.name", "Mac OS X")
        val r = defaultProjectsRootSuggestion()
        assertNotNull(r)
        assertTrue(r.contains("Ableton/User Library"))
    }

    @Test fun `unknown OS returns null projects-root suggestion`() {
        System.setProperty("os.name", "Plan 9 from Bell Labs")
        assertNull(defaultProjectsRootSuggestion())
    }

    @Test fun `samples-root suggestion is null on every platform`() {
        // No conventional default — UserSamples is user-organized, not OS-blessed.
        for (os in listOf("Windows 11", "Mac OS X", "Linux")) {
            System.setProperty("os.name", os)
            assertNull(defaultSamplesRootSuggestion(), "expected null on $os")
        }
    }
}
