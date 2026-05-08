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
        // Seed the parent registry row so the FKs added in 11.sqm are satisfied when the
        // journal inserts into tree_snapshots / tree_journal / user_library_plugins. In
        // production the migrator + TreeRegistry mint this row before any snapshot lands.
        handle.catalog.catalogQueries.upsertTreeRegistryEntry(
            tree_id = treeId.value,
            tree_kind = kind.wireName,
            scope_key = "default",
            display_name = "User Library",
            owner_user_id = "DEFAULT",
            collaborators_json = "[]",
            created_at = now.toEpochMilliseconds(),
            created_by_host = "host-a",
            updated_at = now.toEpochMilliseconds(),
        )
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
            treeId = treeId,
            kind = kind,
            rev = SnapshotRev(rev),
            parentRev = if (rev > 1) SnapshotRev(rev - 1) else null,
            timestamp = timestamp,
            hostId = "host-a",
            hostName = "DesktopA",
            snapshotKind = kindOfSnapshot,
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
                journal.recordSnapshot(manifest(rev = 1), treeId, kind, manifestPath = "trees/user_library/$treeId/manifests/1.json")

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

            journal.recordSnapshot(manifest(rev = 1), treeId, kind, manifestPath = "p/1.json")
            val second =
                journal.recordSnapshot(manifest(rev = 1), treeId, kind, manifestPath = "p/1.json")
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

            journal.recordSnapshot(manifest(rev = 1), treeId, kind, manifestPath = "p/1.json")
            journal.recordSnapshot(manifest(rev = 2), treeId, kind, manifestPath = "p/2.json")
            journal.recordSnapshot(manifest(rev = 3), treeId, kind, manifestPath = "p/3.json")
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
                    )
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
                    )
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
            val (handle, journal) = setup()
            val other = TrackedTreeId("tt-OTHER")
            // Second tree needs its own registry-cache row so the FK passes when its
            // snapshot lands.
            handle.catalog.catalogQueries.upsertTreeRegistryEntry(
                tree_id = other.value,
                tree_kind = kind.wireName,
                scope_key = "other",
                display_name = "Other",
                owner_user_id = "DEFAULT",
                collaborators_json = "[]",
                created_at = now.toEpochMilliseconds(),
                created_by_host = "host-a",
                updated_at = now.toEpochMilliseconds(),
            )

            journal.recordSnapshot(manifest(rev = 1), treeId, kind, manifestPath = "p/1.json")
            journal.recordSnapshot(
                manifest = manifest(rev = 1).copy(treeId = other),
                treeId = other,
                kind = kind,
                manifestPath = "p/o1.json",
            )
            journal.observeRecent(treeId, limit = 10).test {
                val rows = awaitItem()
                assertEquals(1, rows.size)
                assertEquals(treeId, rows[0].treeId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun unknownEventDiscriminatorDecodesAsUnknown() =
        runTest {
            // Simulate an older binary reading a journal row written by a newer client that
            // emitted an event variant ("future_event") this build doesn't know about. The
            // polymorphic default-deserializer registered on DefaultJson should route the row
            // to TreeJournalEvent.Unknown rather than throwing SerializationException and
            // taking the journal flow down.
            val (handle, journal) = setup()
            handle.catalog.catalogQueries.insertTreeJournalEntry(
                tree_id = treeId.value,
                tree_kind = kind.wireName,
                timestamp = now.toEpochMilliseconds(),
                host_id = "host-a",
                event_kind = "future_event",
                payload_json = """{"type":"future_event","newField":"x","payload":[1,2,3]}""",
                rev = 99L,
            )

            journal.observeRecent(treeId, limit = 10).test {
                val rows = awaitItem()
                assertEquals(1, rows.size)
                assertTrue(
                    rows[0].event is TreeJournalEvent.Unknown,
                    "expected Unknown sentinel for unrecognized type discriminator, got ${rows[0].event::class.simpleName}",
                )
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

            journal.recordSnapshot(m, treeId, kind, manifestPath = "p/4.json")
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
