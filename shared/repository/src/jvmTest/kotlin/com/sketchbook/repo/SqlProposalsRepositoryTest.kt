package com.sketchbook.repo

import app.cash.turbine.test
import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.repo.impl.SqlProposalsRepository
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class SqlProposalsRepositoryTest {
    /** Anchored so the cutoff math is reproducible. */
    private val now = Instant.parse("2026-05-05T00:00:00Z")

    private fun setup(): Pair<Catalog, SqlProposalsRepository> {
        val handle = CatalogDb.openInMemory()
        val repo =
            SqlProposalsRepository(
                catalog = handle.catalog,
                ioDispatcher = UnconfinedTestDispatcher(),
                now = { now },
            )
        return handle.catalog to repo
    }

    private fun seed(
        catalog: Catalog,
        name: String,
        lastModifiedSecs: Double,
    ) {
        catalog.catalogQueries.insertOrReplaceProject(
            path = "/lib/$name.als",
            name = name,
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
            last_modified = lastModifiedSecs,
            last_scanned = lastModifiedSecs,
            parse_status = "ok",
            parse_error = null,
            mac_paths_count = 0,
            effort_score = null,
            effort_breakdown = null,
            file_size_bytes = 0L,
        )
    }

    @Test
    fun derivesArchiveCandidatesFromOldUntouchedProjects() =
        runTest {
            val (catalog, repo) = setup()
            // 24mo old → qualifies; 1mo old → doesn't (cutoff is 18mo).
            seed(catalog, "old", lastModifiedSecs = (now.epochSeconds - 24L * 30 * 86400).toDouble())
            seed(catalog, "fresh", lastModifiedSecs = (now.epochSeconds - 30L * 86400).toDouble())
            repo.observe().test {
                val first = awaitItem()
                assertEquals(listOf("archive:1"), first.map { it.proposalId })
                assertTrue(first.first().rationale!!.contains("18 months"))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun approvalPersistsAndIsReflectedInLiveFlow() =
        runTest {
            val (catalog, repo) = setup()
            seed(catalog, "old", lastModifiedSecs = (now.epochSeconds - 24L * 30 * 86400).toDouble())
            repo.observe().test {
                val first = awaitItem()
                assertEquals(ProposalStatus.Pending, first.single().status)

                val approved = repo.approve("archive:1").getOrThrow()
                assertEquals(ProposalStatus.Approved, approved.status)

                val next = awaitItem()
                assertEquals(ProposalStatus.Approved, next.single().status)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
