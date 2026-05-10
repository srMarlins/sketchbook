package com.sketchbook.cloud.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MetadataPathTest {
    @Test
    fun `DocPath builders produce valid paths`() {
        assertEquals("users/U/trees/T", DocPath.tree("U", "T").value)
        assertEquals("users/U/machines/H", DocPath.machine("U", "H").value)
        assertEquals("users/U/plugins/H", DocPath.plugins("U", "H").value)
        assertEquals("users/U/locks/T", DocPath.lock("U", "T").value)
    }

    @Test
    fun `DocPath rejects empty and leading or trailing slashes`() {
        assertFailsWith<IllegalArgumentException> { DocPath("") }
        assertFailsWith<IllegalArgumentException> { DocPath("/users/u/trees/t") }
        assertFailsWith<IllegalArgumentException> { DocPath("users/u/trees/t/") }
    }

    @Test
    fun `DocPath rejects odd segment counts and blank segments`() {
        assertFailsWith<IllegalArgumentException> { DocPath("users/u/trees") }
        assertFailsWith<IllegalArgumentException> { DocPath("users//trees/t") }
    }

    @Test
    fun `DocPath exposes collection and id accessors`() {
        val path = DocPath.tree("U", "T")
        assertEquals("users/U/trees", path.collection.value)
        assertEquals("T", path.id)
    }

    @Test
    fun `CollectionPath builders produce valid paths`() {
        assertEquals("users/U/trees", CollectionPath.trees("U").value)
        assertEquals("users/U/machines", CollectionPath.machines("U").value)
    }

    @Test
    fun `CollectionPath rejects even segment counts`() {
        assertFailsWith<IllegalArgumentException> { CollectionPath("users/u/trees/t") }
    }

    @Test
    fun `CollectionPath dot doc produces a DocPath`() {
        val docPath = CollectionPath.trees("U").doc("T")
        assertEquals("users/U/trees/T", docPath.value)
    }
}
