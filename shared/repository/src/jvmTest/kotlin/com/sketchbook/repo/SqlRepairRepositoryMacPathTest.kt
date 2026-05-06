package com.sketchbook.repo

import app.cash.turbine.test
import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.als.AlsParser
import com.sketchbook.als.AlsRewriter
import com.sketchbook.core.ProjectId
import com.sketchbook.core.SampleRefEdit
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Integration test for PR-W Task W5 — `SqlRepairRepository.applyMacPathRepair` reuses the same
 * patcher pipe that missing-sample Apply does, mapping each Mac-style `<Path Value="...">` inside
 * a `<SampleRef>` to its POSIX equivalent (everything after the first colon). The catalog flips
 * the mac-import finding off (via repair_acks); the journal records a `MacPathRepaired` entry so
 * a future audit pass can spot which projects went through the rewrite.
 *
 * Mirrors the W3 test fixture style: minimal inline gzipped XML so the test is self-contained.
 */
class SqlRepairRepositoryMacPathTest {

    private val twoMacPathsXml = """<?xml version="1.0" encoding="UTF-8"?>
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
                    <Name Value="kick.wav"/>
                    <Path Value="Macintosh HD:/Users/jay/Samples/kick.wav"/>
                    <RelativePath Value="Samples/kick.wav"/>
                  </FileRef>
                </SampleRef>
              </AudioClip>
              <AudioClip>
                <SampleRef>
                  <FileRef>
                    <Name Value="snare.wav"/>
                    <Path Value="OS X:/Volumes/External/Splice/snare.wav"/>
                    <RelativePath Value="Samples/snare.wav"/>
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

    private val noMacPathsXml = """<?xml version="1.0" encoding="UTF-8"?>
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
                    <Name Value="kick.wav"/>
                    <Path Value="/Users/jay/Samples/kick.wav"/>
                    <RelativePath Value="Samples/kick.wav"/>
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

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size + 64)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun ungzipToString(gzipped: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(gzipped)).use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * Default-mode fake mirrors the W3 test's behavior — does the rewrite via substring replace on
     * the decoded XML. That's good enough for asserting the catalog↔journal↔patch sequencing in
     * this layer; the real StAX rewriter is exercised by `AlsRewriterTest`.
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
        var lastEdits: List<SampleRefEdit>? = null
            private set

        private fun rewriteMapping(alsPath: String, mapping: Map<String, String>): AlsPatchService.Outcome {
            if (forcedOutcome != null) return forcedOutcome
            if (mapping.isEmpty()) return AlsPatchService.Outcome.NoChange
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
            return rewriteMapping(alsPath, edits.associate { it.oldPath to it.newPath })
        }

        override suspend fun restore(alsPath: String, bytes: ByteArray): AlsPatchService.Outcome {
            Files.write(Path.of(alsPath), bytes)
            return AlsPatchService.Outcome.Patched
        }
    }

    private fun seedMacImportProject(catalog: Catalog, als: Path, macPathsCount: Int = 2): ProjectId {
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
            mac_paths_count = macPathsCount.toLong(),
            effort_score = null,
            effort_breakdown = null,
            file_size_bytes = Files.size(als),
        )
        val id = catalog.catalogQueries.selectProjectIdByPath(als.toString()).executeAsOne()
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
    fun `mac path repair rewrites all volume-prefixed paths to posix and journals Patched`() = runTest {
        val tmp = createTempDirectory("repo-mac-repair")
        val als = tmp.resolve("MacImport.als").also { Files.write(it, gzip(twoMacPathsXml)) }
        val patcher = RecordingPatchService()
        val (catalog, journal, repo) = setup(patcher)
        val projectId = seedMacImportProject(catalog, als)

        val result = repo.applyMacPathRepair(projectId)

        assertTrue(result.isSuccess, "applyMacPathRepair should succeed; was $result")
        assertEquals(1, patcher.calls, "patcher should be invoked exactly once")
        assertEquals(als.toString(), patcher.lastPath)
        // Mac-path repair routes through the rich SampleRefEdit overload (PR follow-up to PR #102).
        // Each edit drops the volume prefix and zeroes the CRC since the path is changing.
        val edits = assertNotNull(patcher.lastEdits, "edits-based patch should have been called")
        assertEquals(2, edits.size)
        val byOld = edits.associateBy { it.oldPath }
        assertEquals("/Users/jay/Samples/kick.wav", byOld["Macintosh HD:/Users/jay/Samples/kick.wav"]?.newPath)
        assertEquals("/Volumes/External/Splice/snare.wav", byOld["OS X:/Volumes/External/Splice/snare.wav"]?.newPath)
        assertTrue(edits.all { it.newOriginalCrc == 0L }, "every Mac-path edit must zero the CRC")

        // Disk: both Mac-style paths gone, both POSIX paths present.
        val text = ungzipToString(Files.readAllBytes(als))
        assertContains(text, "/Users/jay/Samples/kick.wav")
        assertContains(text, "/Volumes/External/Splice/snare.wav")
        assertFalse("Macintosh HD:" in text, "Mac-style prefix must be gone")
        assertFalse("OS X:" in text, "Mac-style prefix must be gone")

        // Finding clears: a repair_ack row was written for this project under the mac_import scope.
        repo.observeFindings().test {
            val findings = awaitItem()
            assertTrue(
                findings.macImports.none { it.projectId == projectId },
                "mac-import finding should drop after repair",
            )
            cancelAndIgnoreRemainingEvents()
        }

        // Journal carries the outcome.
        journal.observeRecent(limit = 5).test {
            val entries = awaitItem()
            assertEquals(1, entries.size)
            val action = entries.first().action
            assertTrue(action is ActionRecord.MacPathRepaired, "expected MacPathRepaired but was $action")
            assertEquals(2, action.mappingCount)
            assertEquals("Patched", action.alsOutcome)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `mac path repair with no mac-style paths is a no-op patch but still acks the finding`() = runTest {
        // Project is flagged in the catalog (mac_paths_count > 0 because the parser counts POSIX
        // /Users/ prefixes too), but the .als has no actual `Volume:` prefix to repair. The mapping
        // is empty, so we skip the patch and just ack the finding so it drops out of Needs Attention.
        val tmp = createTempDirectory("repo-mac-repair-noop")
        val als = tmp.resolve("AlreadyPosix.als").also { Files.write(it, gzip(noMacPathsXml)) }
        val patcher = RecordingPatchService()
        val (catalog, journal, repo) = setup(patcher)
        val projectId = seedMacImportProject(catalog, als)

        val result = repo.applyMacPathRepair(projectId)
        assertTrue(result.isSuccess)

        // Patcher was never invoked — empty mapping short-circuits.
        assertEquals(0, patcher.calls, "patcher should not be invoked when there's nothing to map")

        // Disk untouched.
        val text = ungzipToString(Files.readAllBytes(als))
        assertContains(text, "/Users/jay/Samples/kick.wav")

        // Finding clears.
        repo.observeFindings().test {
            val findings = awaitItem()
            assertTrue(findings.macImports.none { it.projectId == projectId })
            cancelAndIgnoreRemainingEvents()
        }

        // Journal records the no-op so an audit pass can see we tried.
        journal.observeRecent(limit = 5).test {
            val entries = awaitItem()
            val action = entries.first().action
            assertTrue(action is ActionRecord.MacPathRepaired)
            assertEquals(0, action.mappingCount)
            assertEquals("NoChange", action.alsOutcome)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `mac path repair on busy als records SkippedBusy and still acks the finding`() = runTest {
        val tmp = createTempDirectory("repo-mac-repair-busy")
        val als = tmp.resolve("Busy.als").also { Files.write(it, gzip(twoMacPathsXml)) }
        val patcher = RecordingPatchService(forcedOutcome = AlsPatchService.Outcome.SkippedBusy)
        val (catalog, journal, repo) = setup(patcher)
        val projectId = seedMacImportProject(catalog, als)

        val result = repo.applyMacPathRepair(projectId)
        assertTrue(result.isSuccess, "should succeed even when patcher is busy; was $result")

        // File on disk is untouched.
        val text = ungzipToString(Files.readAllBytes(als))
        assertContains(text, "Macintosh HD:")

        // Finding still cleared — user intent honored. Journal records the busy outcome so a
        // retry pass / future scan can spot un-rewritten files.
        repo.observeFindings().test {
            val findings = awaitItem()
            assertTrue(findings.macImports.none { it.projectId == projectId })
            cancelAndIgnoreRemainingEvents()
        }

        journal.observeRecent(limit = 5).test {
            val entries = awaitItem()
            val action = entries.first().action
            assertTrue(action is ActionRecord.MacPathRepaired)
            assertEquals("SkippedBusy", action.alsOutcome)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Mac-path-bearing fixture that *also* carries `OriginalCrc` + `OriginalFileSize` in both the
     * primary FileRef and the OriginalFileRef sibling. Required for the CRC-invalidation test —
     * the rewriter only updates fields that physically exist in the document.
     */
    private val macPathWithMetaXml = """<?xml version="1.0" encoding="UTF-8"?>
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
                    <Name Value="kick.wav"/>
                    <Path Value="Macintosh HD:/Users/jay/Samples/kick.wav"/>
                    <RelativePath Value="Samples/kick.wav"/>
                    <OriginalFileSize Value="58394528"/>
                    <OriginalCrc Value="7866"/>
                  </FileRef>
                  <SourceContext>
                    <SourceContext>
                      <OriginalFileRef>
                        <FileRef>
                          <Name Value="kick.wav"/>
                          <Path Value="Macintosh HD:/Users/jay/Samples/kick.wav"/>
                          <RelativePath Value="Samples/kick.wav"/>
                          <OriginalFileSize Value="58394528"/>
                          <OriginalCrc Value="7866"/>
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

    /**
     * Real-rewriter [AlsPatchService] for asserting the structured output of a Mac-path repair —
     * the substring-replace recording fake can't observe attribute-level CRC/size changes.
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
    fun `mac path repair zeros OriginalCrc since path change invalidates it`() = runTest {
        val tmp = createTempDirectory("repo-mac-crc")
        val als = tmp.resolve("MacCrc.als").also { Files.write(it, gzip(macPathWithMetaXml)) }
        val patcher = RealRewriterPatchService()
        val (catalog, _, repo) = setup(patcher)
        val projectId = seedMacImportProject(catalog, als, macPathsCount = 1)

        repo.applyMacPathRepair(projectId).getOrThrow()

        // Re-parse the rewritten file: path is POSIX-ified and OriginalCrc was zeroed (was 7866).
        val md = Files.newInputStream(als).use { AlsParser.parse(it) }
        assertEquals(1, md.sampleRefs.size)
        val s = md.sampleRefs.first()
        assertEquals("/Users/jay/Samples/kick.wav", s.rawPath, "path POSIX-ified")
        assertEquals(0L, s.originalCrc, "CRC must be zeroed when path changes")
        // Size left untouched — we don't have a candidate file to stat in the Mac-path case.
        assertEquals(58394528L, s.originalFileSize, "OriginalFileSize is preserved")
    }
}
