package com.sketchbook.repo.impl

import app.cash.turbine.test
import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SketchbookError
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.repo.ActionRecord
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlSnapshotRepositoryTest {

    private val uuid = ProjectUuid("01H-test-snap")

    private fun setup(): Triple<Catalog, SqlJournalRepository, SqlSnapshotRepository> {
        val handle = CatalogDb.openInMemory()
        val journal = SqlJournalRepository(
            catalog = handle.catalog,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        val repo = SqlSnapshotRepository(
            catalog = handle.catalog,
            ioDispatcher = UnconfinedTestDispatcher(),
            journal = journal,
        )
        return Triple(handle.catalog, journal, repo)
    }

    private fun seedSnapshot(
        catalog: Catalog,
        rev: Long,
        kind: String,
        label: String?,
        timestamp: String = "2026-05-05T12:00:00Z",
    ) {
        catalog.catalogQueries.insertSnapshot(
            project_uuid = uuid.value,
            rev = rev,
            parent_rev = null,
            timestamp = timestamp,
            host_id = "host-a",
            kind = kind,
            label = label,
            manifest_path = "/manifests/$rev.json",
            manifest_hash = "hash-$rev",
            file_count = 1L,
            total_bytes = 100L,
            new_bytes = 100L,
        )
    }

    private fun seedIdentity(catalog: Catalog, projectId: Long) {
        // Bare-bones project row so the FK from project_identity has a target.
        catalog.catalogQueries.insertOrReplaceProject(
            path = "/proj/$projectId.als",
            name = "p$projectId",
            parent_dir = "/proj",
            tempo = 120.0,
            time_sig_num = 4,
            time_sig_den = 4,
            key = null,
            track_count = 1,
            audio_tracks = 1,
            midi_tracks = 0,
            return_tracks = 0,
            live_version = "12.0.0",
            last_modified = 0.0,
            last_scanned = 0.0,
            parse_status = "ok",
            parse_error = null,
            mac_paths_count = 0,
            effort_score = null,
            effort_breakdown = null,
            file_size_bytes = 0L,
        )
        val resolvedId = catalog.catalogQueries.selectProjectIdByPath("/proj/$projectId.als").executeAsOne()
        catalog.catalogQueries.insertProjectIdentityIfAbsent(
            project_id = resolvedId,
            uuid = uuid.value,
            created_at = "2026-05-05T12:00:00Z",
        )
    }

    @Test
    fun setSnapshotLabelPromotesAutoToNamedAndPersistsLabel() = runTest {
        val (catalog, journal, repo) = setup()
        seedIdentity(catalog, projectId = 1L)
        seedSnapshot(catalog, rev = 7L, kind = "auto", label = null)

        val result = repo.setSnapshotLabel(uuid, SnapshotRev(7L), "demo for jay")
        assertTrue(result.isSuccess, "expected success, got ${result.exceptionOrNull()}")

        // Observe via the public flow — same path the Timeline uses.
        repo.observeHistory(uuid).test {
            val emitted = awaitItem()
            assertEquals(1, emitted.size)
            assertEquals("demo for jay", emitted[0].label)
            assertEquals(SnapshotKind.Named, emitted[0].kind)
            cancelAndIgnoreRemainingEvents()
        }

        // Journal entry recorded with the right delta.
        journal.observeRecent(limit = 10).test {
            val entries = awaitItem()
            assertEquals(1, entries.size)
            val action = entries[0].action
            assertTrue(action is ActionRecord.SnapshotRelabeled)
            assertEquals(7L, action.rev)
            assertNull(action.labelBefore)
            assertEquals("demo for jay", action.labelAfter)
            assertEquals("auto", action.kindBefore)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setSnapshotLabelOnNamedRowKeepsItNamedAndUpdatesLabel() = runTest {
        val (catalog, _, repo) = setup()
        seedIdentity(catalog, projectId = 1L)
        seedSnapshot(catalog, rev = 3L, kind = "named", label = "old")

        repo.setSnapshotLabel(uuid, SnapshotRev(3L), "new").getOrThrow()

        val row = catalog.catalogQueries.selectSnapshotByRev(uuid.value, 3L).executeAsOne()
        assertEquals("new", row.label)
        assertEquals("named", row.kind)
    }

    @Test
    fun setSnapshotLabelAcceptsNullToClearLabelButStillPromotesKind() = runTest {
        val (catalog, _, repo) = setup()
        seedIdentity(catalog, projectId = 1L)
        seedSnapshot(catalog, rev = 5L, kind = "auto", label = "scratch")

        repo.setSnapshotLabel(uuid, SnapshotRev(5L), null).getOrThrow()

        val row = catalog.catalogQueries.selectSnapshotByRev(uuid.value, 5L).executeAsOne()
        assertNull(row.label)
        // Kind still flips to Named — the user touched the row, so it stops being a coalesce
        // candidate even if they cleared the label.
        assertEquals("named", row.kind)
    }

    @Test
    fun setSnapshotLabelAcceptsEmptyString() = runTest {
        val (catalog, _, repo) = setup()
        seedIdentity(catalog, projectId = 1L)
        seedSnapshot(catalog, rev = 5L, kind = "auto", label = "scratch")

        repo.setSnapshotLabel(uuid, SnapshotRev(5L), "").getOrThrow()

        val row = catalog.catalogQueries.selectSnapshotByRev(uuid.value, 5L).executeAsOne()
        assertEquals("", row.label)
        assertEquals("named", row.kind)
    }

    @Test
    fun setSnapshotLabelOnMissingRevReturnsNotFound() = runTest {
        val (catalog, _, repo) = setup()
        seedIdentity(catalog, projectId = 1L)
        // No snapshot rows seeded.

        val result = repo.setSnapshotLabel(uuid, SnapshotRev(99L), "nope")
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err is SketchbookError.NotFound, "expected NotFound, got ${err::class.simpleName}")
    }
}
