package com.sketchbook.repo

import app.cash.turbine.test
import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.CatalogFts
import com.sketchbook.core.PluginFormat
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
        val repo =
            SqlProjectRepository(
                catalog = handle.catalog,
                ioDispatcher = UnconfinedTestDispatcher(),
                journal = journal,
                fts = ProjectFtsSearcher { q -> fts.search(q) },
            )
        return Triple(handle.catalog, fts, repo)
    }

    private fun seed(
        catalog: com.sketchbook.catalog.db.Catalog,
        fts: CatalogFts,
        name: String,
        parent: String,
        lastModified: Double = 0.0,
    ): Long {
        val path = "$parent/$name.als"
        catalog.catalogQueries.insertOrReplaceProject(
            path = path,
            name = name,
            parent_dir = parent,
            tempo = 120.0,
            time_sig_num = 4,
            time_sig_den = 4,
            key = null,
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
    fun observeProjectsEmitsAllRowsByDefault() =
        runTest {
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
    fun observeProjectsFiltersByFts() =
        runTest {
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
    fun observeProjectEmitsOnMutation() =
        runTest {
            val (catalog, fts, repo) = setup()
            val id = ProjectId(seed(catalog, fts, "foo", "/lib"))

            repo.observeProject(id).test {
                val initial = awaitItem()
                assertNotNull(initial)
                assertEquals("foo", initial.name)

                repo.rename(id, "foo_v2")
                val renamed = awaitItem()
                assertEquals("foo_v2", renamed?.name)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun renameWritesJournalEntry() =
        runTest {
            val (catalog, fts, repo) = setup()
            val id = ProjectId(seed(catalog, fts, "foo", "/lib"))
            val entry = repo.rename(id, "foo_v2")
            val action = entry.action as ActionRecord.Rename
            assertEquals("foo", action.nameBefore)
            assertEquals("foo_v2", action.nameAfter)
            assertEquals(id, entry.projectId)
            assertEquals(1L, entry.sequence)
        }

    @Test
    fun moveUpdatesPathAndJournals() =
        runTest {
            val (catalog, fts, repo) = setup()
            val id = ProjectId(seed(catalog, fts, "foo", "/lib/old"))
            val entry = repo.move(id, "/lib/new")
            val action = entry.action as ActionRecord.Move
            assertEquals("/lib/old/foo.als", action.pathBefore)
            assertEquals("/lib/new/foo.als", action.pathAfter)

            val row = catalog.catalogQueries.selectProjectById(id.value).executeAsOne()
            assertEquals("/lib/new", row.parent_dir)
            assertEquals("/lib/new/foo.als", row.path)
        }

    @Test
    fun archiveRemovesFromObserveProjects() =
        runTest {
            val (catalog, fts, repo) = setup()
            val id = ProjectId(seed(catalog, fts, "foo", "/lib"))
            repo.observeProjects().test {
                assertEquals(1, awaitItem().size)

                repo.archive(id, archived = true)
                val afterArchive = awaitItem()
                assertEquals(0, afterArchive.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun setTagsRoundTrips() =
        runTest {
            val (catalog, fts, repo) = setup()
            val id = ProjectId(seed(catalog, fts, "foo", "/lib"))
            val entry = repo.setTags(id, listOf("mix", "wip"))
            val action = entry.action as ActionRecord.SetTags
            assertEquals(emptyList(), action.before)
            assertEquals(listOf("mix", "wip"), action.after)

            val saved = catalog.catalogQueries.selectTagsForProject(id.value).executeAsList()
            assertEquals(listOf("mix", "wip"), saved)
        }

    @Test
    fun missingProjectThrowsNotFound() =
        runTest {
            val (_, _, repo) = setup()
            kotlin.test.assertFailsWith<com.sketchbook.core.SketchbookError.NotFound> {
                repo.rename(ProjectId(999), "x")
            }
        }

    @Test
    fun observeProjectReturnsNullForMissingId() =
        runTest {
            val (_, _, repo) = setup()
            repo.observeProject(ProjectId(999)).test {
                assertNull(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ------------------------------------------------------------------------
    // PR-AA: inverse plugin lookup
    // ------------------------------------------------------------------------

    private fun seedPlugin(
        catalog: com.sketchbook.catalog.db.Catalog,
        projectId: Long,
        name: String,
        type: String,
        track: String? = null,
    ) {
        catalog.catalogQueries.insertProjectPlugin(
            project_id = projectId,
            plugin_name = name,
            plugin_type = type,
            track_name = track,
        )
    }

    @Test
    fun observeProjectsUsingReturnsAnyFormatWhenFormatNull() =
        runTest {
            val (catalog, fts, repo) = setup()
            // A: Serum on VST2; B: Serum on VST3; C: Vital — only A+B should match "Serum".
            val a = seed(catalog, fts, "track_a", "/lib", lastModified = 3.0)
            val b = seed(catalog, fts, "track_b", "/lib", lastModified = 2.0)
            val c = seed(catalog, fts, "track_c", "/lib", lastModified = 1.0)
            seedPlugin(catalog, a, "Serum", "vst2")
            seedPlugin(catalog, b, "Serum", "vst3")
            seedPlugin(catalog, c, "Vital", "vst3")

            repo.observeProjectsUsing("Serum", format = null, excludeProjectId = null).test {
                val rows = awaitItem()
                assertEquals(listOf("track_a", "track_b"), rows.map { it.name })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun observeProjectsUsingFiltersByFormat() =
        runTest {
            val (catalog, fts, repo) = setup()
            val a = seed(catalog, fts, "track_a", "/lib", lastModified = 3.0)
            val b = seed(catalog, fts, "track_b", "/lib", lastModified = 2.0)
            seedPlugin(catalog, a, "Serum", "vst2")
            seedPlugin(catalog, b, "Serum", "vst3")

            // Narrow to VST2 → only A. (Substitutes for "version 1.35 only" since the catalog has
            // no plugin-version column; format is the closest analog the schema captures.)
            repo
                .observeProjectsUsing(
                    "Serum",
                    format = com.sketchbook.core.PluginFormat.Vst2,
                    excludeProjectId = null,
                ).test {
                    val rows = awaitItem()
                    assertEquals(listOf("track_a"), rows.map { it.name })
                    cancelAndIgnoreRemainingEvents()
                }
        }

    @Test
    fun observeProjectsUsingExcludesCurrentProject() =
        runTest {
            val (catalog, fts, repo) = setup()
            val a = seed(catalog, fts, "current", "/lib", lastModified = 3.0)
            val b = seed(catalog, fts, "other", "/lib", lastModified = 2.0)
            seedPlugin(catalog, a, "Serum", "vst3")
            seedPlugin(catalog, b, "Serum", "vst3")

            repo
                .observeProjectsUsing(
                    "Serum",
                    format = null,
                    excludeProjectId = ProjectId(a),
                ).test {
                    val rows = awaitItem()
                    assertEquals(listOf("other"), rows.map { it.name })
                    cancelAndIgnoreRemainingEvents()
                }
        }

    @Test
    fun observeProjectsUsingDistinctsMultiTrackUse() =
        runTest {
            val (catalog, fts, repo) = setup()
            val a = seed(catalog, fts, "multi", "/lib", lastModified = 1.0)
            // Same plugin loaded on three tracks — the popover should still show the project once.
            seedPlugin(catalog, a, "Serum", "vst3", track = "kick")
            seedPlugin(catalog, a, "Serum", "vst3", track = "snare")
            seedPlugin(catalog, a, "Serum", "vst3", track = "lead")

            repo.observeProjectsUsing("Serum", format = null, excludeProjectId = null).test {
                val rows = awaitItem()
                assertEquals(1, rows.size)
                assertEquals("multi", rows[0].name)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun observeProjectsUsingSkipsArchivedProjects() =
        runTest {
            val (catalog, fts, repo) = setup()
            val a = seed(catalog, fts, "live_one", "/lib", lastModified = 2.0)
            val b = seed(catalog, fts, "shelved", "/lib", lastModified = 1.0)
            seedPlugin(catalog, a, "Serum", "vst3")
            seedPlugin(catalog, b, "Serum", "vst3")
            catalog.catalogQueries.setArchived(archived = 1, id = b)

            repo.observeProjectsUsing("Serum", format = null, excludeProjectId = null).test {
                val rows = awaitItem()
                assertEquals(listOf("live_one"), rows.map { it.name })
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ------------------------------------------------------------------------
    // PR-R R3: stage override + journal
    // ------------------------------------------------------------------------

    @Test
    fun setStageOverridePersistsAndJournals() =
        runTest {
            val (catalog, fts, repo) = setup()
            val id = ProjectId(seed(catalog, fts, "song", "/lib"))
            // Pre-populate stage_inferred so the journal entry can capture it. The scanner does this
            // on real installs; the seed helper doesn't, so we hand-write the column here.
            catalog.catalogQueries.updateStageInferred(
                stage_inferred = "Mixing",
                has_local_bounce = 0L,
                id = id.value,
            )

            val entry = repo.setStageOverride(id, com.sketchbook.core.Stage.Done)
            val action = entry.action as ActionRecord.StageOverridden
            assertEquals("Mixing", action.stageInferred)
            assertNull(action.stageBefore)
            assertEquals("Done", action.stageAfter)
            assertEquals(id, entry.projectId)

            val saved = catalog.catalogQueries.selectProjectById(id.value).executeAsOne()
            assertEquals("Done", saved.stage_override)

            // Clearing back to Auto journals the prior override as `stageBefore`.
            val cleared = repo.setStageOverride(id, null)
            val clearAction = cleared.action as ActionRecord.StageOverridden
            assertEquals("Done", clearAction.stageBefore)
            assertNull(clearAction.stageAfter)
            val clearedRow = catalog.catalogQueries.selectProjectById(id.value).executeAsOne()
            assertNull(clearedRow.stage_override)
        }

    @Test
    fun observeProjectsUsingEmptyWhenNoMatch() =
        runTest {
            val (catalog, fts, repo) = setup()
            val a = seed(catalog, fts, "only_one", "/lib")
            seedPlugin(catalog, a, "Pro-Q 3", "vst3")
            repo
                .observeProjectsUsing(
                    "Serum",
                    format = null,
                    excludeProjectId = ProjectId(a),
                ).test {
                    val rows = awaitItem()
                    assertTrue(rows.isEmpty())
                    cancelAndIgnoreRemainingEvents()
                }
        }

    // ------------------------------------------------------------------------
    // PR-T: missing-plugin coverage
    // ------------------------------------------------------------------------

    private fun markPluginInstalled(
        catalog: com.sketchbook.catalog.db.Catalog,
        name: String,
        type: String,
        installed: Boolean,
    ) {
        catalog.catalogQueries.markPluginsInstalledByNameAndType(
            is_installed = if (installed) 1L else 0L,
            plugin_name = name,
            plugin_type = type,
        )
    }

    @Test
    fun observeMissingPluginCoverageGroupsByNameAndType() =
        runTest {
            val (catalog, fts, repo) = setup()
            // Three projects: P1+P2 use Serum VST3; P3 uses Serum VST2; all marked missing. P4 uses
            // Vital VST3 but it's installed.
            val p1 = seed(catalog, fts, "p1", "/lib", lastModified = 4.0)
            val p2 = seed(catalog, fts, "p2", "/lib", lastModified = 3.0)
            val p3 = seed(catalog, fts, "p3", "/lib", lastModified = 2.0)
            val p4 = seed(catalog, fts, "p4", "/lib", lastModified = 1.0)
            seedPlugin(catalog, p1, "Serum", "vst3")
            seedPlugin(catalog, p2, "Serum", "vst3")
            seedPlugin(catalog, p3, "Serum", "vst2")
            seedPlugin(catalog, p4, "Vital", "vst3")
            markPluginInstalled(catalog, "Serum", "vst3", installed = false)
            markPluginInstalled(catalog, "Serum", "vst2", installed = false)
            markPluginInstalled(catalog, "Vital", "vst3", installed = true)

            repo.observeMissingPluginCoverage().test {
                val rows = awaitItem()
                // Two missing groups; Serum VST3 first (2 affected projects), then Serum VST2 (1).
                assertEquals(2, rows.size)
                assertEquals("Serum", rows[0].name)
                assertEquals(PluginFormat.Vst3, rows[0].format)
                assertEquals(2, rows[0].affectedProjects)
                assertEquals("Serum", rows[1].name)
                assertEquals(PluginFormat.Vst2, rows[1].format)
                assertEquals(1, rows[1].affectedProjects)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun observeMissingPluginCoverageExcludesArchivedProjects() =
        runTest {
            val (catalog, fts, repo) = setup()
            val live = seed(catalog, fts, "live", "/lib", lastModified = 2.0)
            val archived = seed(catalog, fts, "archived", "/lib", lastModified = 1.0)
            seedPlugin(catalog, live, "Serum", "vst3")
            seedPlugin(catalog, archived, "Serum", "vst3")
            markPluginInstalled(catalog, "Serum", "vst3", installed = false)
            catalog.catalogQueries.setArchived(archived = 1, id = archived)

            repo.observeMissingPluginCoverage().test {
                val rows = awaitItem()
                // Archived project doesn't count toward `affected_projects` — user has shelved it,
                // they don't need it to be screaming red on the home chip.
                assertEquals(1, rows.size)
                assertEquals(1, rows[0].affectedProjects)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun observeMissingPluginSummaryCountsCompoundDistinct() =
        runTest {
            val (catalog, fts, repo) = setup()
            val p1 = seed(catalog, fts, "p1", "/lib", lastModified = 3.0)
            val p2 = seed(catalog, fts, "p2", "/lib", lastModified = 2.0)
            val p3 = seed(catalog, fts, "p3", "/lib", lastModified = 1.0)
            seedPlugin(catalog, p1, "Serum", "vst3")
            seedPlugin(catalog, p2, "Serum", "vst2")
            seedPlugin(catalog, p3, "Vital", "vst3")
            markPluginInstalled(catalog, "Serum", "vst3", installed = false)
            markPluginInstalled(catalog, "Serum", "vst2", installed = false)
            markPluginInstalled(catalog, "Vital", "vst3", installed = false)

            repo.observeMissingPluginSummary().test {
                val s = awaitItem()
                assertNotNull(s)
                // Three (name, type) pairs missing; three distinct projects affected.
                assertEquals(3, s.missingPluginCount)
                assertEquals(3, s.affectedProjects)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun observeMissingPluginSummaryReportsZerosWhenAllInstalled() =
        runTest {
            val (catalog, fts, repo) = setup()
            val p1 = seed(catalog, fts, "p1", "/lib")
            seedPlugin(catalog, p1, "Serum", "vst3")
            // is_installed defaults to 1 from the schema; nothing missing.

            repo.observeMissingPluginSummary().test {
                val s = awaitItem()
                assertNotNull(s)
                assertEquals(0, s.missingPluginCount)
                assertEquals(0, s.affectedProjects)
                assertTrue(s.isEmpty)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
