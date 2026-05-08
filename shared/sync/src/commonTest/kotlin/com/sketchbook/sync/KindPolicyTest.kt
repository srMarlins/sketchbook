package com.sketchbook.sync

import com.sketchbook.core.TrackedTreeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KindPolicyTest {
    @Test
    fun projectPolicyMatchesDesignTable() {
        val p = assertNotNull(KindPolicy.forKind(TrackedTreeKind.Project))
        assertTrue(p.leaseRequired)
        assertEquals(ConflictMode.BranchFork, p.conflictMode)
        assertTrue(p.privateScopeAllowed)
        assertTrue(p.alsPatchingEnabled)
        assertTrue(p.contributesToPluginManifest)
    }

    @Test
    fun userLibraryPolicyMatchesDesignTable() {
        val p = assertNotNull(KindPolicy.forKind(TrackedTreeKind.UserLibrary))
        assertFalse(p.leaseRequired)
        assertEquals(ConflictMode.Merge(DeletePolicy.Tombstones), p.conflictMode)
        assertFalse(p.privateScopeAllowed)
        assertFalse(p.alsPatchingEnabled)
        assertTrue(p.contributesToPluginManifest)
    }

    @Test
    fun unknownKindHasNoPolicy() {
        assertNull(KindPolicy.forKind(TrackedTreeKind.Unknown("future-stems-kind")))
    }
}
