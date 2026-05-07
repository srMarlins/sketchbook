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
