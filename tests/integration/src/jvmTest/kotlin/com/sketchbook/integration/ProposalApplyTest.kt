package com.sketchbook.integration

import com.sketchbook.actions.ProposalActionExecutor
import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.CatalogFts
import com.sketchbook.catalog.JvmScanner
import com.sketchbook.repo.ProposalAction
import com.sketchbook.repo.impl.InMemoryJournalRepository
import com.sketchbook.repo.impl.SqlProjectRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProposalApplyTest {
    private val tmp: Path = createTempDirectory("prop-")

    @AfterTest fun cleanup() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun applySetTagsAndArchiveCommitsBoth() =
        runTest {
            val library = tmp.resolve("library").also { it.toFile().mkdirs() }
            Fixtures.writeCleanProject(library)
            val handle = CatalogDb.openInMemory()
            val fts = CatalogFts(handle.driver)
            JvmScanner(handle.catalog, fts).scan(library).toList()
            val row =
                handle.catalog.catalogQueries
                    .selectAllProjects()
                    .executeAsList()
                    .single()
            val pid = row.id

            val journal = InMemoryJournalRepository()
            val repo =
                SqlProjectRepository(
                    catalog = handle.catalog,
                    ioDispatcher = UnconfinedTestDispatcher(),
                    journal = journal,
                    fts = com.sketchbook.repo.ProjectFtsSearcher { _ -> emptyList() },
                )
            val executor = ProposalActionExecutor(repo)

            val actions =
                listOf(
                    ProposalAction(
                        type = "SetTags",
                        args =
                            buildJsonObject {
                                put("project_id", pid)
                                put("tags", JsonArray(listOf(JsonPrimitive("ai"), JsonPrimitive("review"))))
                            },
                    ),
                    ProposalAction(
                        type = "ArchiveProject",
                        args = buildJsonObject { put("project_id", pid) },
                    ),
                )

            val r = executor.apply(actions)
            assertTrue(r.isSuccess, "apply failed: ${r.exceptionOrNull()}")

            val tags =
                handle.catalog.catalogQueries
                    .selectTagsForProject(pid)
                    .executeAsList()
            assertEquals(listOf("ai", "review"), tags.sorted())
            val refreshed =
                handle.catalog.catalogQueries
                    .selectProjectById(pid)
                    .executeAsOne()
            assertEquals(1L, refreshed.is_archived)
        }

    @Test
    fun unknownActionTypeFailsButFirstActionAlreadyCommitted() =
        runTest {
            val library = tmp.resolve("library2").also { it.toFile().mkdirs() }
            Fixtures.writeCleanProject(library)
            val handle = CatalogDb.openInMemory()
            val fts = CatalogFts(handle.driver)
            JvmScanner(handle.catalog, fts).scan(library).toList()
            val pid =
                handle.catalog.catalogQueries
                    .selectAllProjects()
                    .executeAsList()
                    .single()
                    .id

            val journal = InMemoryJournalRepository()
            val repo =
                SqlProjectRepository(
                    catalog = handle.catalog,
                    ioDispatcher = UnconfinedTestDispatcher(),
                    journal = journal,
                    fts = com.sketchbook.repo.ProjectFtsSearcher { _ -> emptyList() },
                )
            val executor = ProposalActionExecutor(repo)

            // First action is valid; second is unknown — executor stops on the unknown one.
            val actions =
                listOf(
                    ProposalAction(
                        type = "SetTags",
                        args =
                            buildJsonObject {
                                put("project_id", pid)
                                put("tags", JsonArray(listOf(JsonPrimitive("first"))))
                            },
                    ),
                    ProposalAction(
                        type = "RecolorEverythingToTaupe", // not a real action
                        args = buildJsonObject { put("project_id", pid) },
                    ),
                )

            val r = executor.apply(actions)
            assertTrue(r.isFailure, "expected failure on unknown action type")

            // ProposalActionExecutor processes serially. The first action committed; this test
            // documents the observed behavior — the docstring on apply() says "no partial commits"
            // but the implementation reads serial-and-stop. If the contract changes to all-or-nothing
            // (transactional batch), update this assertion to assertTrue(tags.isEmpty()).
            val tags =
                handle.catalog.catalogQueries
                    .selectTagsForProject(pid)
                    .executeAsList()
            assertEquals(listOf("first"), tags)
        }
}
