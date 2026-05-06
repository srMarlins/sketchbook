package com.sketchbook.integration

import app.cash.turbine.test
import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.CatalogFts
import com.sketchbook.catalog.JvmScanner
import com.sketchbook.core.ProjectId
import com.sketchbook.repo.impl.InMemoryJournalRepository
import com.sketchbook.repo.impl.SqlProjectRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScanAndEditE2ETest {

    private val tmp: Path = createTempDirectory("scan-e2e-")

    @AfterTest fun cleanup() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun scanThenEditRoundTrip() = runTest {
        // Layout: tmp/library/<fixtures>...
        val library = tmp.resolve("library").also { it.toFile().mkdirs() }
        Fixtures.writeCleanProject(library)
        Fixtures.writeMissingSamplesProject(library)
        Fixtures.writeMacPathsProject(library)
        Fixtures.writeParseFailProject(library) // writes bad.als directly under library

        val handle = CatalogDb.openInMemory()
        val fts = CatalogFts(handle.driver)
        val scanner = JvmScanner(handle.catalog, fts, batchSize = 4)

        // Drive the scan to completion.
        scanner.scan(library).toList()

        // Catalog reflects all 4 files (3 projects + 1 bad.als).
        // selectAllProjects only returns non-archived rows, which is what we want here pre-edit.
        val rows = handle.catalog.catalogQueries.selectAllProjects().executeAsList()
        assertEquals(4, rows.size, "expected 4 catalog rows, got ${rows.map { it.path }}")

        val byName = rows.associateBy { it.name }
        assertEquals("ok", byName["clean"]?.parse_status)
        assertEquals("ok", byName["missing_samples"]?.parse_status)
        assertEquals("ok", byName["mac_paths"]?.parse_status)
        assertEquals("failed", byName["bad"]?.parse_status)
        assertNotNull(byName["bad"]?.parse_error, "parse_error should be populated for bad.als")

        // mac_paths fixture has at least one Mac-style sample path.
        assertTrue((byName["mac_paths"]?.mac_paths_count ?: 0L) > 0L)

        // missing_samples row has one missing sample child. Use selectSampleEntriesForProject so
        // we pick up the is_missing flag — selectSamplesForProject only returns the path column.
        val missingRow = byName["missing_samples"]!!
        val sampleChildren = handle.catalog.catalogQueries
            .selectSampleEntriesForProject(missingRow.id)
            .executeAsList()
        assertEquals(2, sampleChildren.size)
        assertEquals(1, sampleChildren.count { it.is_missing == 1L })

        // Now edit through the repository and verify journal + observation.
        val journal = InMemoryJournalRepository()
        val repo = SqlProjectRepository(
            catalog = handle.catalog,
            ioDispatcher = UnconfinedTestDispatcher(),
            journal = journal,
            fts = com.sketchbook.repo.ProjectFtsSearcher { _ -> emptyList() },
        )
        val cleanId = ProjectId(byName["clean"]!!.id)

        // setTags — observe an emission carrying the new tags.
        repo.setTags(cleanId, listOf("wip", "mix")).getOrThrow()
        val tags = handle.catalog.catalogQueries.selectTagsForProject(cleanId.value).executeAsList()
        assertEquals(listOf("mix", "wip"), tags.sorted())

        // archive — disappears from observeProjects.
        repo.observeProjects().test {
            val initial = awaitItem()
            assertTrue(initial.any { it.id == cleanId })
            repo.archive(cleanId, archived = true).getOrThrow()
            val afterArchive = awaitItem()
            assertTrue(afterArchive.none { it.id == cleanId })
            cancelAndIgnoreRemainingEvents()
        }

        // rename + move on a non-archived project.
        val macId = ProjectId(byName["mac_paths"]!!.id)
        repo.rename(macId, "mac_renamed").getOrThrow()
        val newParent = library.resolve("organized").also { it.toFile().mkdirs() }.toString()
        repo.move(macId, newParent).getOrThrow()
        val afterEdit = handle.catalog.catalogQueries.selectProjectById(macId.value).executeAsOne()
        assertEquals("mac_renamed", afterEdit.name)
        assertEquals(newParent, afterEdit.parent_dir)

        // Journal saw all four mutations: setTags, archive, rename, move.
        val entries = journal.observeRecent(100).first()
        val actionTypes = entries.map { it.action::class.simpleName }
        assertTrue("SetTags" in actionTypes, "missing SetTags in $actionTypes")
        assertTrue("Archive" in actionTypes, "missing Archive in $actionTypes")
        assertTrue("Rename" in actionTypes, "missing Rename in $actionTypes")
        assertTrue("Move" in actionTypes, "missing Move in $actionTypes")
    }
}
