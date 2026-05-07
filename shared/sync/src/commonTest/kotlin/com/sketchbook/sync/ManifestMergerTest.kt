package com.sketchbook.sync

import com.sketchbook.core.BlobHash
import com.sketchbook.core.Manifest
import com.sketchbook.core.ManifestFile
import com.sketchbook.core.ManifestStats
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ManifestMergerTest {
    private val uuid = ProjectUuid("01H-merge-uuid")
    private val t0 = Instant.parse("2026-05-07T00:00:00Z")
    private val t1 = Instant.parse("2026-05-07T00:01:00Z")
    private val t2 = Instant.parse("2026-05-07T00:02:00Z")
    private val mergerHost = "host-c"
    private val clock = FixedClock(Instant.parse("2026-05-07T00:03:00Z"))

    private fun blob(suffix: String): BlobHash = BlobHash("b3:" + suffix.padEnd(BlobHash.DIGEST_HEX_LEN, '0'))

    private fun mf(
        rev: Long,
        host: String,
        files: Map<String, ManifestFile>,
        parent: SnapshotRev? = null,
    ): Manifest =
        Manifest(
            treeId = TrackedTreeId(uuid.value),
            kind = TrackedTreeKind.Project,
            rev = SnapshotRev(rev),
            parentRev = parent,
            timestamp = t0,
            hostId = host,
            hostName = host,
            snapshotKind = SnapshotKind.Auto,
            files = files,
            stats =
                ManifestStats(
                    fileCount = files.values.count { !it.deleted },
                    totalBytes = files.values.filterNot { it.deleted }.sumOf { it.size },
                    newBytes = 0,
                ),
        )

    @Test
    fun disjointRelpathsAreUnioned() {
        val local =
            mf(
                2,
                "host-a",
                mapOf("a" to ManifestFile(blob("aa"), 1, t0)),
                parent = SnapshotRev(1),
            )
        val remote =
            mf(
                2,
                "host-b",
                mapOf("b" to ManifestFile(blob("bb"), 1, t0)),
                parent = SnapshotRev(1),
            )

        val merged = mergeManifests(local, remote, mergerHost, clock)

        assertEquals(setOf("a", "b"), merged.files.keys)
        assertEquals(SnapshotRev(3), merged.rev)
        assertEquals(remote.rev, merged.parentRev)
        assertEquals(SnapshotKind.Auto, merged.snapshotKind)
        assertNull(merged.label)
    }

    @Test
    fun sameRelpathLwwByMtime() {
        val local =
            mf(
                2,
                "host-a",
                mapOf("x" to ManifestFile(blob("aa"), 5, t1)),
            )
        val remote =
            mf(
                2,
                "host-b",
                mapOf("x" to ManifestFile(blob("bb"), 7, t2)),
            )

        val merged = mergeManifests(local, remote, mergerHost, clock)
        assertEquals(blob("bb"), merged.files["x"]!!.hash)
        assertEquals(7L, merged.files["x"]!!.size)
    }

    @Test
    fun tieBreakByHostIdLexicographic() {
        // Identical mtime → host-a (smaller lexically) wins.
        val local =
            mf(
                2,
                "host-a",
                mapOf("x" to ManifestFile(blob("aa"), 5, t1)),
            )
        val remote =
            mf(
                2,
                "host-b",
                mapOf("x" to ManifestFile(blob("bb"), 7, t1)),
            )
        val merged = mergeManifests(local, remote, mergerHost, clock)
        assertEquals(blob("aa"), merged.files["x"]!!.hash)
    }

    @Test
    fun tombstoneSurvivesMerge() {
        // Local has tombstone for "x" with later mtime; remote has live copy.
        val local =
            mf(
                2,
                "host-a",
                mapOf("x" to ManifestFile(hash = blob("aa"), size = 0, mtime = t2, deleted = true)),
            )
        val remote =
            mf(
                2,
                "host-b",
                mapOf("x" to ManifestFile(hash = blob("bb"), size = 7, mtime = t1)),
            )
        val merged = mergeManifests(local, remote, mergerHost, clock)
        assertTrue(merged.files["x"]!!.deleted)
        // Tombstones excluded from stats.
        assertEquals(0, merged.stats.fileCount)
        assertEquals(0L, merged.stats.totalBytes)
    }

    @Test
    fun statsRecomputedFromMergedFiles() {
        val local =
            mf(
                2,
                "host-a",
                mapOf(
                    "a" to ManifestFile(blob("aa"), 10, t1),
                    "b" to ManifestFile(blob("bb"), 20, t1),
                ),
            )
        val remote =
            mf(
                2,
                "host-b",
                mapOf(
                    "c" to ManifestFile(blob("cc"), 30, t1),
                ),
            )
        val merged = mergeManifests(local, remote, mergerHost, clock)
        assertEquals(3, merged.stats.fileCount)
        assertEquals(60L, merged.stats.totalBytes)
    }

    @Test
    fun newRevIsMaxPlusOne() {
        val local = mf(5, "host-a", emptyMap())
        val remote = mf(8, "host-b", emptyMap())
        val merged = mergeManifests(local, remote, mergerHost, clock)
        assertEquals(SnapshotRev(9), merged.rev)
        assertEquals(SnapshotRev(8), merged.parentRev)
    }
}
