package com.sketchbook.repo

import app.cash.turbine.test
import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.ProjectId
import com.sketchbook.repo.impl.InMemoryJournalRepository
import com.sketchbook.repo.impl.SqlRepairRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Integration test for PR-W Task W3 — `SqlRepairRepository.applyMissingSampleMatch` rewrites the
 * on-disk `.als` via [AlsPatchService] in addition to updating the catalog row, and journals the
 * outcome so a later Undo (PR-W W4) can reverse the file-system change.
 *
 * Uses a fake [AlsPatchService] that round-trips through the same gzip+StAX pipe an end-to-end
 * test would use; the real `AlsPatcher` adapter (in `:shared:sync-io`) lives in `app-desktop`'s
 * graph wiring and is exercised by the desktop-level integration tests. Catalog is in-memory;
 * journal is the in-memory impl.
 */
class SqlRepairRepositoryAlsRewriteTest {

    /**
     * Tiny self-contained .als-shaped XML fixture. Repository tests can't pull resources from
     * `:shared:parser-als`'s test classpath — replicating one minimal fixture inline keeps this
     * module test-self-contained, mirroring the pattern used by `AlsPatcherTest`.
     */
    private val oneSampleRefXml = """<?xml version="1.0" encoding="UTF-8"?>
<Ableton MajorVersion="11" MinorVersion="11.3" Creator="Ableton Live 11.3.21">
  <LiveSet>
    <Tracks>
      <AudioTrack>
        <DeviceChain>
          <MainSequencer>
            <ClipSlotList>
              <AudioClip>
                <SampleRef>
                  <FileRef>
                    <Name Value="missing.wav"/>
                    <Path Value="/old/missing.wav"/>
                    <RelativePath Value="rel/missing.wav"/>
                  </FileRef>
                </SampleRef>
              </AudioClip>
            </ClipSlotList>
          </MainSequencer>
        </DeviceChain>
      </AudioTrack>
    </Tracks>
  </LiveSet>
</Ableton>
""".toByteArray(Charsets.UTF_8)

    private fun gzipBytes(): ByteArray {
        val out = ByteArrayOutputStream(oneSampleRefXml.size + 64)
        GZIPOutputStream(out).use { it.write(oneSampleRefXml) }
        return out.toByteArray()
    }

    private fun ungzipToString(gzipped: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(gzipped)).use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * Recording fake. Default mode rewrites the file naively (substring replace inside the
     * decoded XML, then re-gzip) so on-disk-rewrite assertions still mean something — that path
     * exercises the catalog ↔ journal ↔ patch sequencing without depending on the real StAX
     * implementation. `forcedOutcome` flips the fake into a no-op-with-outcome mode for the
     * SkippedBusy case.
     */
    private class RecordingPatchService(
        private val forcedOutcome: AlsPatchService.Outcome? = null,
    ) : AlsPatchService {
        var calls: Int = 0
            private set
        var lastPath: String? = null
            private set
        var lastMapping: Map<String, String>? = null
            private set

        override suspend fun patch(alsPath: String, mapping: Map<String, String>): AlsPatchService.Outcome {
            calls++
            lastPath = alsPath
            lastMapping = mapping
            if (forcedOutcome != null) return forcedOutcome
            // Round-trip rewrite: ungzip → string-replace each mapping → re-gzip.
            val path = Path.of(alsPath)
            val original = Files.readAllBytes(path)
            val ungzipped = GZIPInputStream(ByteArrayInputStream(original)).use { it.readBytes() }
            var text = ungzipped.toString(Charsets.UTF_8)
            var changed = false
            for ((from, to) in mapping) {
                if (from in text) {
                    text = text.replace(from, to)
                    changed = true
                }
            }
            if (!changed) return AlsPatchService.Outcome.NoChange
            val out = ByteArrayOutputStream(text.length + 64)
            GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            Files.write(path, out.toByteArray())
            return AlsPatchService.Outcome.Patched
        }
    }

    private fun seedProjectWithMissingSample(
        catalog: Catalog,
        als: Path,
        missingPath: String,
    ): ProjectId {
        catalog.catalogQueries.insertOrReplaceProject(
            path = als.toString(),
            name = als.fileName.toString().removeSuffix(".als"),
            parent_dir = als.parent.toString(),
            tempo = 120.0,
            time_sig_num = 4,
            time_sig_den = 4,
            track_count = 1,
            audio_tracks = 1,
            midi_tracks = 0,
            return_tracks = 0,
            live_version = "11.3.21",
            last_modified = 0.0,
            last_scanned = 0.0,
            parse_status = "ok",
            parse_error = null,
            mac_paths_count = 0,
            effort_score = null,
            effort_breakdown = null,
            file_size_bytes = Files.size(als),
        )
        val id = catalog.catalogQueries.selectProjectIdByPath(als.toString()).executeAsOne()
        catalog.catalogQueries.insertProjectSampleWithMissing(
            project_id = id,
            sample_path = missingPath,
            is_missing = 1,
        )
        return ProjectId(id)
    }

    private fun setup(
        patcher: AlsPatchService,
    ): Triple<Catalog, InMemoryJournalRepository, SqlRepairRepository> {
        val handle = CatalogDb.openInMemory()
        val journal = InMemoryJournalRepository()
        val repo = SqlRepairRepository(
            catalog = handle.catalog,
            ioDispatcher = UnconfinedTestDispatcher(),
            journal = journal,
            patcher = patcher,
        )
        return Triple(handle.catalog, journal, repo)
    }

    @Test
    fun `apply match rewrites the on-disk als and journals the change`() = runTest {
        val tmp = createTempDirectory("repo-rewrite")
        val als = tmp.resolve("My.als").also { Files.write(it, gzipBytes()) }
        val patcher = RecordingPatchService()
        val (catalog, journal, repo) = setup(patcher)
        val projectId = seedProjectWithMissingSample(catalog, als, missingPath = "/old/missing.wav")

        val result = repo.applyMissingSampleMatch(
            projectId = projectId,
            missingPath = "/old/missing.wav",
            candidatePath = "/new/found.wav",
        )

        assertTrue(result.isSuccess, "applyMissingSampleMatch should succeed; was $result")
        assertEquals(1, patcher.calls, "patcher should be invoked exactly once")
        assertEquals(als.toString(), patcher.lastPath)
        assertEquals(mapOf("/old/missing.wav" to "/new/found.wav"), patcher.lastMapping)

        // The `.als` on disk was rewritten through the patch service.
        val text = ungzipToString(Files.readAllBytes(als))
        assertContains(text, "/new/found.wav")
        assertTrue("/old/missing.wav" !in text, "old path must be gone")

        // Catalog row updated.
        val sampleRows = catalog.catalogQueries
            .selectSampleEntriesForProject(projectId.value)
            .executeAsList()
        assertEquals(1, sampleRows.size)
        assertEquals("/new/found.wav", sampleRows.first().sample_path)
        assertEquals(0L, sampleRows.first().is_missing)

        // Journal carries the outcome so a future Undo (W4) can act on it.
        journal.observeRecent(limit = 5).test {
            val entries = awaitItem()
            assertEquals(1, entries.size)
            val action = entries.first().action
            assertTrue(action is ActionRecord.MissingSampleMapped, "expected MissingSampleMapped but was $action")
            assertEquals("/old/missing.wav", action.missingPath)
            assertEquals("/new/found.wav", action.candidatePath)
            assertEquals("Patched", action.alsOutcome)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `apply match on busy als records SkippedBusy and leaves catalog updated`() = runTest {
        // The patcher reports SkippedBusy (Live has the file open). The catalog row still flips —
        // user intent is honored so the row drops out of Needs Attention; the journal entry is the
        // breadcrumb that lets a later retry pass spot un-rewritten files.
        val tmp = createTempDirectory("repo-rewrite-busy")
        val als = tmp.resolve("Busy.als").also { Files.write(it, gzipBytes()) }
        val patcher = RecordingPatchService(forcedOutcome = AlsPatchService.Outcome.SkippedBusy)
        val (catalog, journal, repo) = setup(patcher)
        val projectId = seedProjectWithMissingSample(catalog, als, missingPath = "/old/missing.wav")

        val result = repo.applyMissingSampleMatch(
            projectId = projectId,
            missingPath = "/old/missing.wav",
            candidatePath = "/new/found.wav",
        )
        assertTrue(result.isSuccess, "should succeed even when patcher is busy; was $result")

        // File on disk is untouched (forced SkippedBusy means the fake didn't write).
        val text = ungzipToString(Files.readAllBytes(als))
        assertContains(text, "/old/missing.wav", message = "busy file is left as-is")

        // Catalog row still flipped.
        val sampleRows = catalog.catalogQueries
            .selectSampleEntriesForProject(projectId.value)
            .executeAsList()
        assertEquals("/new/found.wav", sampleRows.first().sample_path)
        assertEquals(0L, sampleRows.first().is_missing)

        // Journal entry captures the SkippedBusy outcome for a retry pass.
        journal.observeRecent(limit = 5).test {
            val entries = awaitItem()
            val action = entries.first().action
            assertTrue(action is ActionRecord.MissingSampleMapped)
            assertEquals("SkippedBusy", action.alsOutcome)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
