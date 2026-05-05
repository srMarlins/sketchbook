package com.sketchbook.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProjectPathTest {

    @Test
    fun rejectsBlank() {
        assertFailsWith<IllegalArgumentException> { ProjectPath("") }
        assertFailsWith<IllegalArgumentException> { ProjectPath("   ") }
    }

    @Test
    fun rejectsBackslash() {
        assertFailsWith<IllegalArgumentException> { ProjectPath("Samples\\k.wav") }
    }

    @Test
    fun rejectsLeadingSlash() {
        assertFailsWith<IllegalArgumentException> { ProjectPath("/Samples/k.wav") }
    }

    @Test
    fun fromPlatformNormalizesBackslashes() {
        val p = ProjectPath.fromPlatform("Samples\\Imported\\k.wav")
        assertEquals("Samples/Imported/k.wav", p.value)
    }

    @Test
    fun fromPlatformStripsLeadingSlash() {
        val p = ProjectPath.fromPlatform("/Samples/k.wav")
        assertEquals("Samples/k.wav", p.value)
    }

    @Test
    fun fromPlatformHandlesMixedSeparators() {
        val p = ProjectPath.fromPlatform("Samples/Imported\\k.wav")
        assertEquals("Samples/Imported/k.wav", p.value)
    }

    @Test
    fun nameReturnsLastSegment() {
        assertEquals("k.wav", ProjectPath("Samples/Imported/k.wav").name)
        assertEquals("Project.als", ProjectPath("Project.als").name)
    }

    @Test
    fun parentDropsLastSegment() {
        assertEquals(ProjectPath("Samples/Imported"), ProjectPath("Samples/Imported/k.wav").parent)
        assertEquals(ProjectPath("Samples"), ProjectPath("Samples/Imported").parent)
        assertNull(ProjectPath("Project.als").parent)
    }

    @Test
    fun childAppendsSegment() {
        assertEquals(ProjectPath("Samples/k.wav"), ProjectPath("Samples").child("k.wav"))
    }

    @Test
    fun childRejectsSeparators() {
        assertFailsWith<IllegalArgumentException> { ProjectPath("Samples").child("a/b") }
        assertFailsWith<IllegalArgumentException> { ProjectPath("Samples").child("a\\b") }
    }

    @Test
    fun relativeToReturnsRemainder() {
        val rel = ProjectPath("Projects/foo/Samples/k.wav").relativeTo(ProjectPath("Projects/foo"))
        assertEquals(ProjectPath("Samples/k.wav"), rel)
    }

    @Test
    fun relativeToRejectsNonAncestor() {
        assertFailsWith<IllegalArgumentException> {
            ProjectPath("Projects/foo/k.wav").relativeTo(ProjectPath("Projects/bar"))
        }
    }

    @Test
    fun relativeToRejectsEqualPath() {
        assertFailsWith<IllegalArgumentException> {
            ProjectPath("Projects/foo").relativeTo(ProjectPath("Projects/foo"))
        }
    }

    @Test
    fun relativeToRejectsSiblingWithPrefixMatch() {
        // "Projects/foobar" should NOT be a descendant of "Projects/foo".
        assertFailsWith<IllegalArgumentException> {
            ProjectPath("Projects/foobar/k.wav").relativeTo(ProjectPath("Projects/foo"))
        }
    }
}
