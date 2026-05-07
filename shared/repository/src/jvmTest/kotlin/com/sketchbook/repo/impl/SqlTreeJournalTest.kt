package com.sketchbook.repo.impl

import app.cash.turbine.test
import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.CatalogHandle
import com.sketchbook.core.BlobHash
import com.sketchbook.core.Manifest
import com.sketchbook.core.ManifestFile
import com.sketchbook.core.ManifestStats
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
import com.sketchbook.repo.TreeJournal
import com.sketchbook.repo.TreeJournalEntry
import com.sketchbook.repo.TreeJournalEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SqlTreeJournalTest {
    private val treeId = TrackedTreeId("tt-01HZUL")
    private val kind = TrackedTreeKind.UserLibrary
    private val now = Instant.parse("2026-05-07T12:00:00Z")

    private fun setup(): Pair<CatalogHandle, TreeJournal> {
        val handle = CatalogDb.openInMemory()
        val journal =
            SqlTreeJournal(
                catalog = handle.catalog,
                ioDispatcher = UnconfinedTestDispatcher(),
            )
        return handle to journal
    }

    private fun manifest(
        rev: Long,
        kindOfSnapshot: SnapshotKind = SnapshotKind.Auto,
        timestamp: Instant = now,
        files: Map<String, ManifestFile> = emptyMap(),
    ): Manifest =
        Manifest(
            // v=1 wire still carries `project_uuid`; non-project kinds reuse the field for the
            // tree id until commit 9 introduces v=2.
            projectUuid = ProjectUuid(treeId.value),
            rev = SnapshotRev(rev),
            parentRev = if (rev > 1) SnapshotRev(rev - 1) else null,
            timestamp = timestamp,
            hostId = "host-a",
            hostName = "DesktopA",
            kind = kindOfSnapshot,
            files = files,
            stats =
                ManifestStats(
                    fileCount = files.size,
                    totalBytes = files.values.sumOf { it.size },
                    newBytes = 0L,
                ),
        )

    @Test
    fun recordSnapshotInsertsBothRows() =
        runTest {
            val (_, journal) = setup()

            val entry =
                journal
                    .recordSnapshot(manifest(rev = 1), treeId, kind, manifestPath = "trees/user_library/$treeId/manifests/1.json")
                    .getOrThrow()

            assertEquals(treeId, entry.treeId)
            assertEquals(kind, entry.kind)
            assertEquals(SnapshotRev(1), entry.rev)
            assertNotNull(entry.sequence)
            val event = entry.event as TreeJournalEvent.Snapshot
            assertEquals(1L, event.rev)

            val snapshots = journal.observeSnapshots(treeId)
            snapshots.test {
                val rows = awaitItem()
                assertEquals(1, rows.size)
                assertEquals(SnapshotRev(1), rows[0].rev)
                cancelAndIgnoreRemainingEvents()
            }

            val events = journal.observeRecent(treeId, limit = 10)
            events.test {
                val rows = awaitItem()
                assertEquals(1, rows.size)
                assertTrue(rows[0].event is TreeJournalEvent.Snapshot)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun recordSnapshotIsIdempotentOnRev() =
        runTest {
            val (_, journal) = setup()

            journal.recordSnapshot(manifest(rev = 1), treeId, kind, manifestPath = "p/1.json").getOrThrow()
            val second =
                journal.recordSnapshot(manifest(rev = 1), treeId, kind, manifestPath = "p/1.json").getOrThrow()

            // Second call returns an entry without a sequence (no insert happened).
            assertNull(second.sequence)

            journal.observeRecent(treeId, limit = 10).test {
                assertEquals(1, awaitItem().size)
                cancelAndIgnoreRemainingEvents()
            }
            journal.observeSnapshots(treeId).test {
                assertEquals(1, awaitItem().size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun snapshotsOrderedNewestFirst() =
        runTest {
            val (_, journal) = setup()

            journal.recordSnapshot(manifest(rev = 1), treeId, kind, manifestPath = "p/1.json").getOrThrow()
            journal.recordSnapshot(manifest(rev = 2), treeId, kind, manifestPath = "p/2.json").getOrThrow()
            journal.recordSnapshot(manifest(rev = 3), treeId, kind, manifestPath = "p/3.json").getOrThrow()

            journal.observeSnapshots(treeId).test {
                val rows = awaitItem()
                assertEquals(listOf(SnapshotRev(3), SnapshotRev(2), SnapshotRev(1)), rows.map { it.rev })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun appendEventStoresMergeAndMaterialize() =
        runTest {
            val (_, journal) = setup()

            val merge =
                journal
                    .appendEvent(
                        TreeJournalEntry(
                            treeId = treeId,
                            kind = kind,
                            timestamp = now,
                            hostId = "host-a",
                            event =
                                TreeJournalEvent.Merge(
                                    localRev = 5,
                                    remoteRev = 6,
                                    mergedRev = 7,
                                    tombstonesPropagated = 2,
                                ),
                            rev = SnapshotRev(7),
                        ),
                    ).getOrThrow()
            assertNotNull(merge.sequence)

            val mat =
                journal
                    .appendEvent(
                        TreeJournalEntry(
                            treeId = treeId,
                            kind = kind,
                            timestamp = now,
                            hostId = "host-a",
                            event = TreeJournalEvent.Materialize(rev = 7, filesWritten = 12, filesDeleted = 1),
                            rev = SnapshotRev(7),
                        ),
                    ).getOrThrow()
            assertNotNull(mat.sequence)
            // sequence is monotonic on the same connection.
            assertTrue(mat.sequence > merge.sequence)

            journal.observeRecent(treeId, limit = 10).test {
                val rows = awaitItem()
                assertEquals(2, rows.size)
                // Newest first.
                assertTrue(rows[0].event is TreeJournalEvent.Materialize)
                assertTrue(rows[1].event is TreeJournalEvent.Merge)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun observeRecentScopedToTreeId() =
        runTest {
            val (_, journal) = setup()
            val other = TrackedTreeId("tt-OTHER")

            journal.recordSnapshot(manifest(rev = 1), treeId, kind, manifestPath = "p/1.json").getOrThrow()
            journal
                .recordSnapshot(
                    manifest = manifest(rev = 1).copy(projectUuid = ProjectUuid(other.value)),
                    treeId = other,
                    kind = kind,
                    manifestPath = "p/o1.json",
                ).getOrThrow()

            journal.observeRecent(treeId, limit = 10).test {
                val rows = awaitItem()
                assertEquals(1, rows.size)
                assertEquals(treeId, rows[0].treeId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun manifestFieldsAreCopiedIntoSnapshotRow() =
        runTest {
            val (_, journal) = setup()

            val files =
                mapOf(
                    "Templates/Default.als" to
                        ManifestFile(
                            hash = BlobHash("b3:" + "a".repeat(64)),
                            size = 4096L,
                            mtime = now,
                        ),
                )
            val m =
                manifest(rev = 4, files = files)
                    .copy(label = "trial", stats = ManifestStats(fileCount = 1, totalBytes = 4096L, newBytes = 4096L))

            journal.recordSnapshot(m, treeId, kind, manifestPath = "p/4.json").getOrThrow()

            journal.observeSnapshots(treeId).test {
                val row = awaitItem().single()
                assertEquals("trial", row.label)
                assertEquals(1, row.fileCount)
                assertEquals(4096L, row.totalBytes)
                assertEquals(4096L, row.newBytes)
                assertEquals(SnapshotRev(3), row.parentRev)
                assertEquals("p/4.json", row.manifestPath)
                assertEquals(SnapshotKind.Auto, row.snapshotKind)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
