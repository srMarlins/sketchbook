package com.sketchbook.sync

import com.sketchbook.core.TrackedTreeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KindPolicyTest {
    @Test
    fun projectPolicyMatchesDesignTable() {
        val p = KindPolicy.forKind(TrackedTreeKind.Project)
        assertTrue(p.leaseRequired)
        assertEquals(ConflictMode.BranchFork, p.conflictMode)
        assertTrue(p.privateScopeAllowed)
        assertTrue(p.alsPatchingEnabled)
        assertTrue(p.contributesToPluginManifest)
    }

    @Test
    fun userLibraryPolicyMatchesDesignTable() {
        val p = KindPolicy.forKind(TrackedTreeKind.UserLibrary)
        assertFalse(p.leaseRequired)
        assertEquals(ConflictMode.Merge(DeletePolicy.Tombstones), p.conflictMode)
        assertFalse(p.privateScopeAllowed)
        assertFalse(p.alsPatchingEnabled)
        assertTrue(p.contributesToPluginManifest)
    }

    @Test
    fun unknownKindHasNoPolicy() {
        assertFailsWith<IllegalStateException> {
            KindPolicy.forKind(TrackedTreeKind.Unknown("future-stems-kind"))
        }
    }
}
