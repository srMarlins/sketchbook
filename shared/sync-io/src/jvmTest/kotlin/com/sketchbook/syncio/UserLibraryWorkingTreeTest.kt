package com.sketchbook.syncio

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserLibraryWorkingTreeTest {
    private val root: Path = Files.createTempDirectory("sb-ul-")

    @AfterTest fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun touch(
        rel: String,
        contents: String = rel,
    ) {
        val p = root.resolve(rel)
        p.parent.createDirectories()
        p.writeText(contents)
    }

    @Test
    fun listIncludesUserContentSurface() {
        // Files Live writes that we *do* sync — racks, presets, clips, defaults, samples.
        for (
        rel in
        listOf(
            "Defaults/Default.als",
            "Defaults/Audio Effect Rack.adg",
            "Templates/Live Set.als",
            "Presets/Audio Effects/EQ Eight/Bass Cut.adv",
            "Presets/Instruments/Drum Rack/Kit.adg",
            "Clips/perc-loop.alc",
            "Samples/kick.wav",
            "Max for Live/MyDevice.amxd",
            "Drum Kits/808.adp",
            "Grooves/swing.agr",
        )
        ) {
            touch(rel)
        }

        val tree = UserLibraryWorkingTree(root)
        val list = tree.list().toSet()

        assertEquals(
            setOf(
                "Defaults/Default.als",
                "Defaults/Audio Effect Rack.adg",
                "Templates/Live Set.als",
                "Presets/Audio Effects/EQ Eight/Bass Cut.adv",
                "Presets/Instruments/Drum Rack/Kit.adg",
                "Clips/perc-loop.alc",
                "Samples/kick.wav",
                "Max for Live/MyDevice.amxd",
                "Drum Kits/808.adp",
                "Grooves/swing.agr",
            ),
            list,
        )
    }

    @Test
    fun listExcludesAbletonProjectInfoSubtree() {
        touch("Defaults/Default.als")
        touch("Ableton Project Info/AbletonProjectInfo.cfg")
        touch("Templates/Ableton Project Info/AbletonProjectInfo.cfg")

        val list = UserLibraryWorkingTree(root).list().toSet()

        assertEquals(setOf("Defaults/Default.als"), list)
    }

    @Test
    fun listExcludesOsJunk() {
        touch("Defaults/Default.als")
        touch(".DS_Store")
        touch("Defaults/.DS_Store")
        touch("Thumbs.db")
        touch("desktop.ini")
        touch(".Spotlight-V100/some.dat")
        touch(".fseventsd/marker")

        val list = UserLibraryWorkingTree(root).list().toSet()

        assertEquals(setOf("Defaults/Default.als"), list)
    }

    @Test
    fun listExcludesDotfilesAndOfficeStyleLocks() {
        touch("Templates/Default.als")
        touch("Templates/.hidden")
        touch("~\$temp.docx")

        val list = UserLibraryWorkingTree(root).list().toSet()

        assertEquals(setOf("Templates/Default.als"), list)
    }

    @Test
    fun listExcludesAlsBakAndTmp() {
        touch("Templates/Default.als")
        touch("Templates/Default.als.bak")
        touch("Templates/temp.tmp")

        val list = UserLibraryWorkingTree(root).list().toSet()

        assertEquals(setOf("Templates/Default.als"), list)
    }

    @Test
    fun listExcludesCacheSubtree() {
        touch("Defaults/Default.als")
        touch("Cache/old.dat")
        touch("Templates/Cache/inner.dat")

        val list = UserLibraryWorkingTree(root).list().toSet()

        assertEquals(setOf("Defaults/Default.als"), list)
    }

    @Test
    fun listOnMissingRootReturnsEmpty() {
        // UL might not exist on a fresh machine. Walking a non-existent root returns empty
        // rather than throwing — saves the watcher from special-casing the no-UL case.
        val missing = root.resolve("does-not-exist")
        val list = UserLibraryWorkingTree(missing).list()
        assertTrue(list.isEmpty())
    }

    @Test
    fun listExcludesRecycleBinAndTrashes() {
        touch("Defaults/Default.als")
        touch(".Trashes/old.als")
        touch("\$RECYCLE.BIN/old.als")

        val list = UserLibraryWorkingTree(root).list().toSet()

        assertEquals(setOf("Defaults/Default.als"), list)
    }
}
