package com.sketchbook.repo

import app.cash.turbine.test
import com.sketchbook.als.AlsRewriter
import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.ProjectId
import com.sketchbook.core.SampleRefEdit
import com.sketchbook.repo.impl.InMemoryJournalRepository
import com.sketchbook.repo.impl.SqlRepairRepository
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    /**
     * Live 11/12-shaped fixture: SampleRef has a primary FileRef AND a
     * `SourceContext/SourceContext/OriginalFileRef/FileRef` sibling, both with the same path.
     * Used to prove the rewrite patches both copies atomically (the bug fixed by
     * AlsRewriter.rewriteSampleRefs).
     */
    private val sampleRefWithOriginalSiblingXml = """<?xml version="1.0" encoding="UTF-8"?>
<Ableton MajorVersion="12" MinorVersion="12.0" Creator="Ableton Live 12.0.5">
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
                  <SourceContext>
                    <SourceContext>
                      <OriginalFileRef>
                        <FileRef>
                          <Name Value="missing.wav"/>
                          <Path Value="/old/missing.wav"/>
                          <RelativePath Value="rel/missing.wav"/>
                        </FileRef>
                      </OriginalFileRef>
                    </SourceContext>
                  </SourceContext>
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

    private fun gzipBytesWithSibling(): ByteArray {
        val out = ByteArrayOutputStream(sampleRefWithOriginalSiblingXml.size + 64)
        GZIPOutputStream(out).use { it.write(sampleRefWithOriginalSiblingXml) }
        return out.toByteArray()
    }

    /**
     * Live 11/12 fixture that *also* carries the `OriginalFileSize`+`OriginalCrc` metadata in both
     * the primary FileRef and the OriginalFileRef sibling. Required for tests that assert the
     * apply path zeros the CRC and stamps the candidate's file size — the rewriter only updates
     * fields that are physically present in the document.
     */
    private val sampleRefWithMetaSiblingXml = """<?xml version="1.0" encoding="UTF-8"?>
<Ableton MajorVersion="12" MinorVersion="12.0" Creator="Ableton Live 12.0.5">
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
                    <RelativePathType Value="3"/>
                    <OriginalFileSize Value="9999"/>
                    <OriginalCrc Value="42"/>
                  </FileRef>
                  <SourceContext>
                    <SourceContext>
                      <OriginalFileRef>
                        <FileRef>
                          <Name Value="missing.wav"/>
                          <Path Value="/old/missing.wav"/>
                          <RelativePath Value="rel/missing.wav"/>
                          <RelativePathType Value="3"/>
                          <OriginalFileSize Value="9999"/>
                          <OriginalCrc Value="42"/>
                        </FileRef>
                      </OriginalFileRef>
                    </SourceContext>
                  </SourceContext>
                  <LastModDate Value="100"/>
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

    private fun gzipBytesWithMetaSibling(): ByteArray {
        val out = ByteArrayOutputStream(sampleRefWithMetaSiblingXml.size + 64)
        GZIPOutputStream(out).use { it.write(sampleRefWithMetaSiblingXml) }
        return out.toByteArray()
    }

    private fun ungzipToString(gzipped: ByteArray): String = GZIPInputStream(ByteArrayInputStream(gzipped)).use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * Recording fake. Default mode rewrites the file naively (substring replace inside the
     * decoded XML, then re-gzip) so on-disk-rewrite assertions still mean something — that path
     * exercises the catalog ↔ journal ↔ patch sequencing without depending on the real StAX
     * implementation. `forcedOutcome` flips the fake into a no-op-with-outcome mode for the
     * SkippedBusy case.
     */
    private class RecordingPatchService(
        private val forcedOutcome: AlsPatchService.Outcome? = null,
        private val forcedRestoreOutcome: AlsPatchService.Outcome? = null,
    ) : AlsPatchService {
        var calls: Int = 0
            private set
        var lastPath: String? = null
            private set
        var lastMapping: Map<String, String>? = null
            private set
        var lastEdits: List<SampleRefEdit>? = null
            private set
        var restoreCalls: Int = 0
            private set
        var lastRestorePath: String? = null
            private set
        var lastRestoreBytes: ByteArray? = null
            private set

        private fun rewriteMapping(alsPath: String, mapping: Map<String, String>): AlsPatchService.Outcome {
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

        override suspend fun patch(alsPath: String, mapping: Map<String, String>): AlsPatchService.Outcome {
            calls++
            lastPath = alsPath
            lastMapping = mapping
            return rewriteMapping(alsPath, mapping)
        }

        override suspend fun patch(alsPath: String, edits: List<SampleRefEdit>): AlsPatchService.Outcome {
            calls++
            lastPath = alsPath
            lastEdits = edits
            // Reuse the substring rewrite by projecting edits to oldPath -> newPath. Good enough
            // for catalog↔journal↔patch sequencing assertions; richer rewrites are covered by the
            // real AlsRewriter end-to-end tests via [RealRewriterPatchService].
            return rewriteMapping(alsPath, edits.associate { it.oldPath to it.newPath })
        }

        override suspend fun restore(alsPath: String, bytes: ByteArray): AlsPatchService.Outcome {
            restoreCalls++
            lastRestorePath = alsPath
            lastRestoreBytes = bytes
            if (forcedRestoreOutcome != null) return forcedRestoreOutcome
            Files.write(Path.of(alsPath), bytes)
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
            key = null,
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
        // applyMissingSampleMatch routes through the rich SampleRefEdit overload now (PR follow-up
        // to PR #102). Assert the edit carries the expected old/new pair plus the path-changed
        // CRC-zeroing contract.
        val edits = assertNotNull(patcher.lastEdits, "edits-based patch should have been called")
        assertEquals(1, edits.size)
        assertEquals("/old/missing.wav", edits.first().oldPath)
        assertEquals("/new/found.wav", edits.first().newPath)
        assertEquals(0L, edits.first().newOriginalCrc, "CRC must be zeroed when path changes")

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

    @Test
    fun `apply then restore brings back exact pre-patch bytes and reverts catalog`() = runTest {
        // PR-W W4 round-trip: applyMissingSampleMatch snapshots the .als bytes to a sidecar
        // before delegating to the patcher; restoreMissingSampleMatch reads the sidecar and
        // calls AlsPatchService.restore(), then reverts the catalog row so the finding
        // re-surfaces in Needs Attention.
        val tmp = createTempDirectory("repo-undo")
        val als = tmp.resolve("Round.als")
        val originalBytes = gzipBytes()
        Files.write(als, originalBytes)
        val patcher = RecordingPatchService()
        val (catalog, journal, repo) = setup(patcher)
        val projectId = seedProjectWithMissingSample(catalog, als, missingPath = "/old/missing.wav")

        // Apply: patches the file, flips the catalog.
        repo.applyMissingSampleMatch(
            projectId = projectId,
            missingPath = "/old/missing.wav",
            candidatePath = "/new/found.wav",
        ).getOrThrow()
        // Sanity: post-patch bytes differ from originals.
        assertTrue(!Files.readAllBytes(als).contentEquals(originalBytes), "patch should change the bytes")

        // Restore.
        repo.restoreMissingSampleMatch(
            projectId = projectId,
            missingPath = "/old/missing.wav",
            candidatePath = "/new/found.wav",
        ).getOrThrow()

        // .als bytes are restored *exactly* to the pre-patch state.
        assertContentEquals(originalBytes, Files.readAllBytes(als), "restore must return exact original bytes")
        assertEquals(1, patcher.restoreCalls, "patcher.restore should be invoked once")
        assertEquals(als.toString(), patcher.lastRestorePath)

        // Sidecar cleaned up after a successful undo.
        val sidecar = als.resolveSibling("${als.fileName}.patcher-undo")
        assertTrue(Files.notExists(sidecar), "sidecar should be removed after successful restore")

        // Catalog row reverted: missing_path is back, is_missing=1.
        val sampleRows = catalog.catalogQueries
            .selectSampleEntriesForProject(projectId.value)
            .executeAsList()
        assertEquals(1, sampleRows.size)
        assertEquals("/old/missing.wav", sampleRows.first().sample_path)
        assertEquals(1L, sampleRows.first().is_missing)

        // Journal carries both the apply and the symmetric unmap entry.
        journal.observeRecent(limit = 5).test {
            val entries = awaitItem()
            assertEquals(2, entries.size, "expected Mapped + Unmapped journal entries")
            // observeRecent returns most-recent first.
            val unmap = entries.first().action
            assertTrue(unmap is ActionRecord.MissingSampleUnmapped, "expected MissingSampleUnmapped but was $unmap")
            assertEquals("/old/missing.wav", unmap.missingPath)
            assertEquals("/new/found.wav", unmap.candidatePath)
            assertEquals("Patched", unmap.alsOutcome)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `restore with no sidecar still reverts catalog and journals NoUndoBytes`() = runTest {
        // No prior apply ran (or sidecar was wiped) — restore should still revert the catalog
        // so the finding re-surfaces, and the journal records that no on-disk undo happened.
        val tmp = createTempDirectory("repo-undo-empty")
        val als = tmp.resolve("Empty.als").also { Files.write(it, gzipBytes()) }
        val patcher = RecordingPatchService()
        val (catalog, journal, repo) = setup(patcher)
        val projectId = seedProjectWithMissingSample(catalog, als, missingPath = "/old/missing.wav")

        // Manually flip the row to simulate an apply whose sidecar got cleaned up.
        catalog.catalogQueries.applyMissingSampleMatch(
            new_path = "/new/found.wav",
            project_id = projectId.value,
            old_path = "/old/missing.wav",
        )

        repo.restoreMissingSampleMatch(
            projectId = projectId,
            missingPath = "/old/missing.wav",
            candidatePath = "/new/found.wav",
        ).getOrThrow()

        assertEquals(0, patcher.restoreCalls, "no sidecar means restore should not be invoked")

        val sampleRows = catalog.catalogQueries
            .selectSampleEntriesForProject(projectId.value)
            .executeAsList()
        assertEquals("/old/missing.wav", sampleRows.first().sample_path)
        assertEquals(1L, sampleRows.first().is_missing)

        journal.observeRecent(limit = 5).test {
            val entries = awaitItem()
            val action = entries.first().action
            assertTrue(action is ActionRecord.MissingSampleUnmapped)
            assertEquals("NoUndoBytes", action.alsOutcome)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `restore honors busy outcome from patch service`() = runTest {
        // Sidecar exists, but the patch service reports SkippedBusy. Catalog still reverts so
        // the finding re-surfaces; journal records SkippedBusy so a retry pass can act on it.
        val tmp = createTempDirectory("repo-undo-busy")
        val als = tmp.resolve("Busy.als")
        val originalBytes = gzipBytes()
        Files.write(als, originalBytes)
        val patcher = RecordingPatchService(forcedRestoreOutcome = AlsPatchService.Outcome.SkippedBusy)
        val (catalog, journal, repo) = setup(patcher)
        val projectId = seedProjectWithMissingSample(catalog, als, missingPath = "/old/missing.wav")

        repo.applyMissingSampleMatch(
            projectId = projectId,
            missingPath = "/old/missing.wav",
            candidatePath = "/new/found.wav",
        ).getOrThrow()

        repo.restoreMissingSampleMatch(
            projectId = projectId,
            missingPath = "/old/missing.wav",
            candidatePath = "/new/found.wav",
        ).getOrThrow()

        assertEquals(1, patcher.restoreCalls)

        // Catalog row reverted regardless of patch outcome.
        val sampleRows = catalog.catalogQueries
            .selectSampleEntriesForProject(projectId.value)
            .executeAsList()
        assertEquals("/old/missing.wav", sampleRows.first().sample_path)
        assertEquals(1L, sampleRows.first().is_missing)

        journal.observeRecent(limit = 5).test {
            val entries = awaitItem()
            val unmap = entries.first().action
            assertTrue(unmap is ActionRecord.MissingSampleUnmapped)
            assertEquals("SkippedBusy", unmap.alsOutcome)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Patch service that exercises the *real* StAX rewriter (not substring replace), so the
     * test can assert atomic rewrites of both the primary `<Path>` and the OriginalFileRef
     * sibling within one SampleRef.
     */
    private class RealRewriterPatchService : AlsPatchService {
        override suspend fun patch(alsPath: String, mapping: Map<String, String>): AlsPatchService.Outcome {
            val path = Path.of(alsPath)
            val original = Files.readAllBytes(path)
            val rewritten = AlsRewriter.rewriteSamplePaths(original, mapping)
            if (rewritten.contentEquals(original)) return AlsPatchService.Outcome.NoChange
            Files.write(path, rewritten)
            return AlsPatchService.Outcome.Patched
        }

        override suspend fun patch(alsPath: String, edits: List<SampleRefEdit>): AlsPatchService.Outcome {
            val path = Path.of(alsPath)
            val original = Files.readAllBytes(path)
            val rewritten = AlsRewriter.rewriteSampleRefs(original, edits)
            if (rewritten.contentEquals(original)) return AlsPatchService.Outcome.NoChange
            Files.write(path, rewritten)
            return AlsPatchService.Outcome.Patched
        }

        override suspend fun restore(alsPath: String, bytes: ByteArray): AlsPatchService.Outcome {
            Files.write(Path.of(alsPath), bytes)
            return AlsPatchService.Outcome.Patched
        }
    }

    @Test
    fun `missing sample match patches both FileRef and OriginalFileRef`() = runTest {
        val tmp = createTempDirectory("repo-rewrite-sibling")
        val als = tmp.resolve("Sibling.als").also { Files.write(it, gzipBytesWithSibling()) }
        val patcher = RealRewriterPatchService()
        val (catalog, _, repo) = setup(patcher)
        val projectId = seedProjectWithMissingSample(catalog, als, missingPath = "/old/missing.wav")

        repo.applyMissingSampleMatch(
            projectId = projectId,
            missingPath = "/old/missing.wav",
            candidatePath = "/new/found.wav",
        ).getOrThrow()

        // Both `<Path Value="…"/>` occurrences (primary FileRef + OriginalFileRef sibling) must
        // now point at the candidate. The StAX writer may emit either self-closing or
        // open/close form, so accept both.
        val xml = ungzipToString(Files.readAllBytes(als))
        val pathOccurrences = Regex("""<Path Value="([^"]+)"></Path>|<Path Value="([^"]+)"/>""")
            .findAll(xml).map { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }.toList()
        assertEquals(2, pathOccurrences.size, "expected exactly two <Path> occurrences (primary + sibling)")
        assertTrue(
            pathOccurrences.all { it == "/new/found.wav" },
            "both Path values must be the candidate; was $pathOccurrences",
        )
        assertTrue("/old/missing.wav" !in xml, "old path must be gone from both FileRefs")
    }

    @Test
    fun `applyMissingSampleMatch writes OriginalCrc zero and candidate size`() = runTest {
        // Stat the candidate file and assert that after apply, the .als now carries that exact
        // file size in its OriginalFileSize attribute (and OriginalCrc=0 — path changed, so the
        // CRC must be invalidated; Live recomputes on its next save).
        val tmp = createTempDirectory("repo-rewrite-meta")
        val als = tmp.resolve("Meta.als").also { Files.write(it, gzipBytesWithMetaSibling()) }
        val candidate = tmp.resolve("found.wav")
        // Write a deterministic size — different from the fixture's 9999, so we can prove the
        // apply path actually stat'd the candidate rather than echoing the original.
        val candidateBytes = ByteArray(7777) { (it and 0xFF).toByte() }
        Files.write(candidate, candidateBytes)
        val candidateSize = Files.size(candidate)
        check(candidateSize == 7777L)

        val patcher = RealRewriterPatchService()
        val (catalog, _, repo) = setup(patcher)
        val pid = seedProjectWithMissingSample(catalog, als, missingPath = "/old/missing.wav")

        repo.applyMissingSampleMatch(
            projectId = pid,
            missingPath = "/old/missing.wav",
            candidatePath = candidate.toString(),
        ).getOrThrow()

        // Re-parse via the canonical AlsParser to read structured metadata.
        val md = Files.newInputStream(als).use { com.sketchbook.als.AlsParser.parse(it) }
        assertEquals(1, md.sampleRefs.size)
        val s = md.sampleRefs.first()
        assertEquals(candidate.toString(), s.rawPath, "primary path now points at the candidate")
        assertEquals(0L, s.originalCrc, "CRC must be zeroed when path changes")
        assertEquals(candidateSize, s.originalFileSize, "OriginalFileSize must match the candidate's size")
        // RelativePathType=1 means absolute — we wrote an absolute candidate path.
        assertEquals(1, s.relativePathType)
    }
}
