package com.sketchbook.repo.impl

import app.cash.turbine.test
import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.ProjectId
import com.sketchbook.core.SketchbookError
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class SqlJournalRepositoryTest {

    /** Anchored timestamp for reproducible ordering. */
    private val baseInstant: Instant = Instant.parse("2026-05-05T12:00:00Z")

    private fun setup(): Pair<Catalog, SqlJournalRepository> {
        val handle = CatalogDb.openInMemory()
        val repo = SqlJournalRepository(
            catalog = handle.catalog,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        return handle.catalog to repo
    }

    private fun entry(
        offsetMs: Long = 0L,
        projectId: Long = 1L,
        action: ActionRecord = ActionRecord.Move("/a", "/b"),
        actor: String = "user",
    ): JournalEntry = JournalEntry(
        timestamp = Instant.fromEpochMilliseconds(baseInstant.toEpochMilliseconds() + offsetMs),
        projectId = ProjectId(projectId),
        action = action,
        actor = actor,
    )

    @Test
    fun appendThenObserveRecentReturnsNewestFirst() = runTest {
        val (_, repo) = setup()
        repo.append(entry(offsetMs = 0L, projectId = 1L, action = ActionRecord.Rename("a", "b")))
        repo.append(entry(offsetMs = 1_000L, projectId = 2L, action = ActionRecord.Rename("c", "d")))
        repo.append(entry(offsetMs = 2_000L, projectId = 3L, action = ActionRecord.Rename("e", "f")))

        repo.observeRecent(limit = 10).test {
            val emitted = awaitItem()
            assertEquals(3, emitted.size)
            assertEquals(ProjectId(3L), emitted[0].projectId)
            assertEquals(ProjectId(2L), emitted[1].projectId)
            assertEquals(ProjectId(1L), emitted[2].projectId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun appendAssignsAscendingSequenceValues() = runTest {
        val (_, repo) = setup()
        val a = repo.append(entry(offsetMs = 0L)).getOrThrow()
        val b = repo.append(entry(offsetMs = 1L)).getOrThrow()
        val c = repo.append(entry(offsetMs = 2L)).getOrThrow()

        val seqA = assertNotNull(a.sequence)
        val seqB = assertNotNull(b.sequence)
        val seqC = assertNotNull(c.sequence)
        assertTrue(seqA < seqB, "expected ascending seq: $seqA < $seqB")
        assertTrue(seqB < seqC, "expected ascending seq: $seqB < $seqC")
    }

    @Test
    fun undoLastRemovesAndReturnsMostRecent() = runTest {
        val (catalog, repo) = setup()
        repo.append(entry(offsetMs = 0L, projectId = 1L))
        repo.append(entry(offsetMs = 1_000L, projectId = 2L))
        val newest = repo.append(entry(offsetMs = 2_000L, projectId = 3L)).getOrThrow()

        val popped = repo.undoLast().getOrThrow()
        assertEquals(newest.sequence, popped.sequence)
        assertEquals(ProjectId(3L), popped.projectId)

        // Row count drops by one and the newest is gone.
        assertEquals(2L, catalog.catalogQueries.countJournalEntries().executeAsOne())
        repo.observeRecent(limit = 10).test {
            val emitted = awaitItem()
            assertEquals(2, emitted.size)
            assertEquals(ProjectId(2L), emitted[0].projectId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun undoLastOnEmptyReturnsNotFound() = runTest {
        val (_, repo) = setup()
        val result = repo.undoLast()
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            error is SketchbookError.NotFound,
            "expected NotFound, got ${error?.let { it::class.simpleName }}: ${error?.message}",
        )
    }

    @Test
    fun roundTripsAllSixActionRecordVariants() = runTest {
        val (_, repo) = setup()
        val variants: List<ActionRecord> = listOf(
            ActionRecord.Move(pathBefore = "/old/a.als", pathAfter = "/new/a.als"),
            ActionRecord.Rename(nameBefore = "draft", nameAfter = "final"),
            ActionRecord.Archive(wasArchived = false, isArchived = true),
            ActionRecord.SetTags(before = listOf("blue"), after = listOf("blue", "WIP")),
            ActionRecord.ForceTakeLock(priorOwnerHostName = "studio-mac", priorExpiresAtMs = 123L),
            ActionRecord.PushConflict(ourRev = 5, theirRev = 7),
        )

        variants.forEachIndexed { i, action ->
            repo.append(entry(offsetMs = i.toLong() * 1_000L, projectId = (i + 1).toLong(), action = action))
        }

        repo.observeRecent(limit = 10).test {
            val emitted = awaitItem()
            assertEquals(variants.size, emitted.size)
            // Newest first → last-appended variant is at index 0; reverse to compare to insert order.
            val roundTripped = emitted.reversed().map { it.action }
            assertEquals(variants, roundTripped)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun actionTypeColumnUsesStableTypeKeyNotClassName() = runTest {
        // Asserts that what lands in `action_type` is the variant's declared `typeKey`, not
        // ::class.simpleName. Lock both halves: the read-back through the repo (typeKey-driven
        // deserialization) and the raw column value (so an SQL filter `WHERE action_type =
        // 'Move'` keeps working under future R8/ProGuard renames).
        val (catalog, repo) = setup()
        repo.append(entry(offsetMs = 0L, projectId = 1L, action = ActionRecord.Move("/a", "/b")))
        repo.append(entry(offsetMs = 1L, projectId = 1L, action = ActionRecord.SetTags(emptyList(), listOf("wip"))))

        val rawTypes = catalog.catalogQueries.selectJournalRecent(10L).executeAsList()
            .map { it.action_type }
        // Newest first.
        assertEquals(listOf("SetTags", "Move"), rawTypes)
    }

    @Test
    fun selectJournalRecentIsDeterministicWhenTimestampsCollide() = runTest {
        // Two entries in the same millisecond: the secondary `id DESC` sort key in
        // `selectJournalRecent` is what makes `undoLast` always pop the most-recently-inserted
        // row, not whichever the storage engine happened to lay down first.
        val (catalog, repo) = setup()
        val collidedMs = 0L
        repo.append(entry(offsetMs = collidedMs, projectId = 1L, action = ActionRecord.Rename("a", "b")))
        repo.append(entry(offsetMs = collidedMs, projectId = 2L, action = ActionRecord.Rename("c", "d")))
        repo.append(entry(offsetMs = collidedMs, projectId = 3L, action = ActionRecord.Rename("e", "f")))

        // All three share the same occurred_at; expect newest insertion (project 3) at head.
        val rows = catalog.catalogQueries.selectJournalRecent(10L).executeAsList()
        assertEquals(3, rows.size)
        assertEquals(3L, rows[0].project_id)
        assertEquals(2L, rows[1].project_id)
        assertEquals(1L, rows[2].project_id)

        // undoLast reads the same head, so it pops project 3.
        val popped = repo.undoLast().getOrThrow()
        assertEquals(ProjectId(3L), popped.projectId)
    }

    @Test
    fun appendAutoFillsProjectNameAndPathFromCatalog() = runTest {
        // The auto-fill path: caller provides only the bare entry, the repository looks up
        // the project's current name + path from the catalog and stamps both onto the row.
        // Mirrors the project_name precedent from PR #100, extended to project_path in 9.sqm.
        val (catalog, repo) = setup()
        catalog.catalogQueries.insertProject(
            path = "/lib/Hot Track.als",
            name = "Hot Track",
            parent_dir = "/lib",
            tempo = null,
            time_sig_num = null,
            time_sig_den = null,
            key = null,
            track_count = 0,
            audio_tracks = 0,
            midi_tracks = 0,
            return_tracks = 0,
            live_version = null,
            last_modified = 0.0,
            last_scanned = 0.0,
            parse_status = null,
            parse_error = null,
            mac_paths_count = null,
            effort_score = null,
            effort_breakdown = null,
            file_size_bytes = null,
        )
        val projectId = catalog.catalogQueries.selectProjectIdByPath("/lib/Hot Track.als").executeAsOne()

        val appended = repo.append(
            entry(offsetMs = 0L, projectId = projectId, action = ActionRecord.MacPathRepaired(2, "Patched")),
        ).getOrThrow()

        assertEquals("Hot Track", appended.projectName)
        assertEquals("/lib/Hot Track.als", appended.projectPath)
    }

    @Test
    fun appendPreservesCallerSuppliedNameAndPathEvenForOrphanedProjectId() = runTest {
        // The caller-supplied path (used by SqlRepairRepository.applyMacPathRepair to capture
        // the path *before* a concurrent rescan can orphan the id): both fields land on the row
        // even when no projects row exists for the project_id. Without this, the journal append
        // would fall through to selectProjectById, miss, and write NULLs.
        val (_, repo) = setup()
        val orphanedId = 99_999L
        val appended = repo.append(
            JournalEntry(
                timestamp = baseInstant,
                projectId = ProjectId(orphanedId),
                projectName = "Stale Track",
                projectPath = "/lib/Stale Track.als",
                action = ActionRecord.MacPathRepaired(1, "Patched"),
            ),
        ).getOrThrow()

        assertEquals("Stale Track", appended.projectName)
        assertEquals("/lib/Stale Track.als", appended.projectPath)

        // Round-trip via observe to confirm the SqlDelight read path also surfaces both fields.
        repo.observeRecent(limit = 5).test {
            val emitted = awaitItem()
            assertEquals(1, emitted.size)
            assertEquals("Stale Track", emitted[0].projectName)
            assertEquals("/lib/Stale Track.als", emitted[0].projectPath)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun actorDefaultsToUserButCustomValueIsPersisted() = runTest {
        val (_, repo) = setup()
        val defaulted = repo.append(entry(offsetMs = 0L, projectId = 1L)).getOrThrow()
        val custom = repo.append(entry(offsetMs = 1_000L, projectId = 2L, actor = "sketchbook")).getOrThrow()

        assertEquals("user", defaulted.actor)
        assertEquals("sketchbook", custom.actor)

        repo.observeRecent(limit = 10).test {
            val emitted = awaitItem()
            // Newest first.
            assertEquals("sketchbook", emitted[0].actor)
            assertEquals("user", emitted[1].actor)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
