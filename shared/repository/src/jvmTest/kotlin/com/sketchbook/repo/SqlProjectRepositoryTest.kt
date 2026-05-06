package com.sketchbook.repo

import app.cash.turbine.test
import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.CatalogFts
import com.sketchbook.core.ProjectId
import com.sketchbook.repo.impl.InMemoryJournalRepository
import com.sketchbook.repo.impl.SqlProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlProjectRepositoryTest {

    private fun setup(): Triple<com.sketchbook.catalog.db.Catalog, CatalogFts, SqlProjectRepository> {
        val handle = CatalogDb.openInMemory()
        val fts = CatalogFts(handle.driver)
        val journal = InMemoryJournalRepository()
        val repo = SqlProjectRepository(
            catalog = handle.catalog,
            ioDispatcher = UnconfinedTestDispatcher(),
            journal = journal,
            ftsSearch = { q -> fts.search(q) },
        )
        return Triple(handle.catalog, fts, repo)
    }

    private fun seed(catalog: com.sketchbook.catalog.db.Catalog, fts: CatalogFts, name: String, parent: String, lastModified: Double = 0.0): Long {
        val path = "$parent/$name.als"
        catalog.catalogQueries.insertOrReplaceProject(
            path = path,
            name = name,
            parent_dir = parent,
            tempo = 120.0,
            time_sig_num = 4,
            time_sig_den = 4,
            track_count = 1,
            audio_tracks = 1,
            midi_tracks = 0,
            return_tracks = 0,
            live_version = "12.0.0",
            last_modified = lastModified,
            last_scanned = lastModified,
            parse_status = "ok",
            parse_error = null,
            mac_paths_count = 0,
            effort_score = null,
            effort_breakdown = null,
            file_size_bytes = 0L,
        )
        val id = catalog.catalogQueries.selectProjectIdByPath(path).executeAsOne()
        fts.upsert(rowid = id, name = name, parentDir = parent, pluginNames = "", sampleFilenames = "", notes = "")
        return id
    }

    @Test
    fun observeProjectsEmitsAllRowsByDefault() = runTest {
        val (catalog, fts, repo) = setup()
        seed(catalog, fts, "alpha", "/lib", lastModified = 1.0)
        seed(catalog, fts, "beta", "/lib", lastModified = 2.0)
        repo.observeProjects().test {
            val first = awaitItem()
            assertEquals(setOf("alpha", "beta"), first.map { it.name }.toSet())
            // Order is last_modified DESC.
            assertEquals("beta", first.first().name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeProjectsFiltersByFts() = runTest {
        val (catalog, fts, repo) = setup()
        seed(catalog, fts, "kick_lab", "/lib")
        seed(catalog, fts, "ambient_pad", "/lib")
        repo.observeProjects("kick").test {
            val matched = awaitItem()
            assertEquals(1, matched.size)
            assertEquals("kick_lab", matched[0].name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeProjectEmitsOnMutation() = runTest {
        val (catalog, fts, repo) = setup()
        val id = ProjectId(seed(catalog, fts, "foo", "/lib"))

        repo.observeProject(id).test {
            val initial = awaitItem()
            assertNotNull(initial)
            assertEquals("foo", initial.name)

            repo.rename(id, "foo_v2").getOrThrow()
            val renamed = awaitItem()
            assertEquals("foo_v2", renamed?.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun renameWritesJournalEntry() = runTest {
        val (catalog, fts, repo) = setup()
        val id = ProjectId(seed(catalog, fts, "foo", "/lib"))
        val entry = repo.rename(id, "foo_v2").getOrThrow()
        val action = entry.action as ActionRecord.Rename
        assertEquals("foo", action.nameBefore)
        assertEquals("foo_v2", action.nameAfter)
        assertEquals(id, entry.projectId)
        assertEquals(1L, entry.sequence)
    }

    @Test
    fun moveUpdatesPathAndJournals() = runTest {
        val (catalog, fts, repo) = setup()
        val id = ProjectId(seed(catalog, fts, "foo", "/lib/old"))
        val entry = repo.move(id, "/lib/new").getOrThrow()
        val action = entry.action as ActionRecord.Move
        assertEquals("/lib/old/foo.als", action.pathBefore)
        assertEquals("/lib/new/foo.als", action.pathAfter)

        val row = catalog.catalogQueries.selectProjectById(id.value).executeAsOne()
        assertEquals("/lib/new", row.parent_dir)
        assertEquals("/lib/new/foo.als", row.path)
    }

    @Test
    fun archiveRemovesFromObserveProjects() = runTest {
        val (catalog, fts, repo) = setup()
        val id = ProjectId(seed(catalog, fts, "foo", "/lib"))
        repo.observeProjects().test {
            assertEquals(1, awaitItem().size)

            repo.archive(id, archived = true).getOrThrow()
            val afterArchive = awaitItem()
            assertEquals(0, afterArchive.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setTagsRoundTrips() = runTest {
        val (catalog, fts, repo) = setup()
        val id = ProjectId(seed(catalog, fts, "foo", "/lib"))
        val entry = repo.setTags(id, listOf("mix", "wip")).getOrThrow()
        val action = entry.action as ActionRecord.SetTags
        assertEquals(emptyList(), action.before)
        assertEquals(listOf("mix", "wip"), action.after)

        val saved = catalog.catalogQueries.selectTagsForProject(id.value).executeAsList()
        assertEquals(listOf("mix", "wip"), saved)
    }

    @Test
    fun missingProjectReturnsNotFound() = runTest {
        val (_, _, repo) = setup()
        val result = repo.rename(ProjectId(999), "x")
        assertTrue(result.isFailure)
        val cause = result.exceptionOrNull()
        assertTrue(cause is com.sketchbook.core.SketchbookError.NotFound)
    }

    @Test
    fun observeProjectReturnsNullForMissingId() = runTest {
        val (_, _, repo) = setup()
        repo.observeProject(ProjectId(999)).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
