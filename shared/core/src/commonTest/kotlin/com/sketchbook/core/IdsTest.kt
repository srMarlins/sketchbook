package com.sketchbook.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BlobHashTest {
    @Test
    fun parsesValidB3Hash() {
        val hash = BlobHash("b3:" + "a".repeat(64))
        assertEquals("b3:" + "a".repeat(64), hash.value)
        assertEquals("a".repeat(64), hash.hex)
    }

    @Test
    fun rejectsMissingPrefix() {
        assertFailsWith<IllegalArgumentException> {
            BlobHash("a".repeat(64))
        }
    }

    @Test
    fun rejectsWrongDigestLength() {
        assertFailsWith<IllegalArgumentException> { BlobHash("b3:abc") }
        assertFailsWith<IllegalArgumentException> { BlobHash("b3:" + "a".repeat(63)) }
        assertFailsWith<IllegalArgumentException> { BlobHash("b3:" + "a".repeat(65)) }
    }

    @Test
    fun rejectsUppercaseHex() {
        assertFailsWith<IllegalArgumentException> {
            BlobHash("b3:" + "A".repeat(64))
        }
    }

    @Test
    fun rejectsNonHexChars() {
        assertFailsWith<IllegalArgumentException> {
            BlobHash("b3:" + "g".repeat(64))
        }
    }
}

class SnapshotRevTest {
    @Test
    fun rejectsNegative() {
        assertFailsWith<IllegalArgumentException> { SnapshotRev(-1) }
    }

    @Test
    fun nextIncrements() {
        assertEquals(SnapshotRev(1), SnapshotRev(0).next())
        assertEquals(SnapshotRev(48), SnapshotRev(47).next())
    }

    @Test
    fun comparable() {
        assertEquals(true, SnapshotRev(46) < SnapshotRev(47))
        assertEquals(true, SnapshotRev(47) > SnapshotRev(46))
    }
}

class ProjectIdTest {
    @Test
    fun rejectsNonPositive() {
        assertFailsWith<IllegalArgumentException> { ProjectId(0) }
        assertFailsWith<IllegalArgumentException> { ProjectId(-1) }
    }
}

class ProjectUuidTest {
    @Test
    fun rejectsBlank() {
        assertFailsWith<IllegalArgumentException> { ProjectUuid("") }
        assertFailsWith<IllegalArgumentException> { ProjectUuid("   ") }
    }
}

class UserIdTest {
    @Test
    fun defaultIsDefault() {
        assertEquals("default", UserId.DEFAULT.value)
    }

    @Test
    fun rejectsBlank() {
        assertFailsWith<IllegalArgumentException> { UserId("") }
    }
}

class TrackedTreeIdTest {
    @Test
    fun acceptsAlphanumericDashUnderscore() {
        val id = TrackedTreeId("tt-01HZ3W_abc")
        assertEquals("tt-01HZ3W_abc", id.value)
    }

    @Test
    fun rejectsBlank() {
        assertFailsWith<IllegalArgumentException> { TrackedTreeId("") }
        assertFailsWith<IllegalArgumentException> { TrackedTreeId("   ") }
    }

    @Test
    fun rejectsTooLong() {
        assertFailsWith<IllegalArgumentException> { TrackedTreeId("a".repeat(65)) }
    }

    @Test
    fun rejectsUnsafeChars() {
        assertFailsWith<IllegalArgumentException> { TrackedTreeId("project:abc") }
        assertFailsWith<IllegalArgumentException> { TrackedTreeId("a/b") }
        assertFailsWith<IllegalArgumentException> { TrackedTreeId("a..b") }
    }
}

class TrackedTreeKindTest {
    @Test
    fun knownKindsRoundTripByWireName() {
        assertEquals(TrackedTreeKind.Project, TrackedTreeKind.fromWire("project"))
        assertEquals(TrackedTreeKind.UserLibrary, TrackedTreeKind.fromWire("user_library"))
    }

    @Test
    fun unknownKindIsPreserved() {
        val unknown = TrackedTreeKind.fromWire("future_kind")
        assertEquals("future_kind", unknown.wireName)
    }
}

class CloudDocKeyTest {
    @Test
    fun acceptsRelativePaths() {
        assertEquals("registry.json", CloudDocKey("registry.json").path)
        assertEquals("profile/plugin_manifest_h.json", CloudDocKey("profile/plugin_manifest_h.json").path)
    }

    @Test
    fun rejectsBlank() {
        assertFailsWith<IllegalArgumentException> { CloudDocKey("") }
        assertFailsWith<IllegalArgumentException> { CloudDocKey("   ") }
    }

    @Test
    fun rejectsAbsolutePaths() {
        assertFailsWith<IllegalArgumentException> { CloudDocKey("/registry.json") }
    }

    @Test
    fun rejectsTraversal() {
        assertFailsWith<IllegalArgumentException> { CloudDocKey("foo/../bar") }
    }

    @Test
    fun prefixRejectsAbsolute() {
        assertFailsWith<IllegalArgumentException> { CloudDocKey.Prefix("/profile/") }
    }
}

class CollaboratorTest {
    @Test
    fun rolesAreOrdered() {
        // Sanity: enum order is stable so persistence doesn't accidentally reorder.
        assertEquals(0, CollabRole.Read.ordinal)
        assertEquals(1, CollabRole.Write.ordinal)
        assertEquals(2, CollabRole.Admin.ordinal)
    }

    @Test
    fun collaboratorRoundTrip() {
        val c = Collaborator(UserId("alice"), CollabRole.Write)
        assertEquals("alice", c.userId.value)
        assertEquals(CollabRole.Write, c.role)
    }
}
