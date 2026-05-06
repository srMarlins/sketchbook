package com.sketchbook.integration

import app.cash.turbine.test
import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.CatalogFts
import com.sketchbook.catalog.JvmSampleScanner
import com.sketchbook.catalog.JvmScanner
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import com.sketchbook.repo.impl.SqlRepairRepository
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepairWorkflowTest {

    private val tmp: Path = createTempDirectory("repair-")
    @AfterTest fun cleanup() { tmp.toFile().deleteRecursively() }

    @Test
    fun missingSampleMatchAndMacAck() = runTest {
        val library = tmp.resolve("library").also { it.toFile().mkdirs() }
        Fixtures.writeMissingSamplesProject(library)
        Fixtures.writeMacPathsProject(library)
        val corpus = Fixtures.writeSampleCorpus(tmp.resolve("corpus"))

        val handle = CatalogDb.openInMemory()
        val fts = CatalogFts(handle.driver)

        // 1) Library scan.
        JvmScanner(handle.catalog, fts).scan(library).toList()
        // 2) Sample corpus scan.
        JvmSampleScanner(handle.catalog).scan(corpus.toString())

        val repair = SqlRepairRepository(
            catalog = handle.catalog,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        // Initial emission: 1 mac-import finding (mac_paths project), and 2 missing-sample
        // findings (the explicit one from missing_samples fixture, plus the mac-path-style
        // sample reference in mac_paths Project — that path doesn't resolve on Windows, so the
        // resolver flags it missing too).
        //
        // autoMatch only fires when the corpus uniquely matches by filename+size; since the
        // missing sample's size_bytes is null (file isn't on disk), the auto-match path is
        // bypassed and we test the candidates path instead.
        repair.observeFindings().test {
            val initial = awaitItem()
            assertEquals(1, initial.macImports.size, "expected 1 mac finding")
            assertEquals(2, initial.missingSamples.size, "expected 2 missing-sample findings")
            initial.missingSamples.forEach { miss ->
                assertTrue(
                    miss.candidates.isNotEmpty(),
                    "expected at least one candidate for ${miss.missingPath}",
                )
            }

            // Apply each missing-sample candidate. The combine() flow can emit an intermediate
            // stale-cache state (ackTick fires before missingFlow's underlying SQLDelight query
            // re-runs), so drain until the missing list reflects each apply.
            for (miss in initial.missingSamples) {
                repair.applyMissingSampleMatch(
                    projectId = miss.projectId,
                    missingPath = miss.missingPath,
                    candidatePath = miss.candidates.first().path,
                ).getOrThrow()
            }
            while (true) {
                val item = awaitItem()
                if (item.missingSamples.isEmpty()) break
            }

            // Acknowledge the mac finding.
            repair.acknowledgeMacImport(initial.macImports.single().projectId).getOrThrow()
            while (true) {
                val item = awaitItem()
                if (item.macImports.isEmpty()) break
            }
            cancelAndIgnoreRemainingEvents()
        }
    }
}
