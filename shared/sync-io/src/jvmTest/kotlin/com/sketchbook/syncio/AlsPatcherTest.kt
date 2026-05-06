package com.sketchbook.syncio

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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AlsPatcherTest {

    /**
     * Tiny self-contained .als-shaped XML fixture. Parser-als test resources can't reach across
     * modules; replicating one minimal fixture inline keeps this module test-self-contained.
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

    private fun gzipBytesOf(@Suppress("UNUSED_PARAMETER") name: String): ByteArray {
        // The `name` arg matches the spec's signature for readability; we always return the inline
        // fixture above (only one fixture is needed in this module's tests).
        val out = ByteArrayOutputStream(oneSampleRefXml.size + 64)
        GZIPOutputStream(out).use { it.write(oneSampleRefXml) }
        return out.toByteArray()
    }

    private fun ungzipToString(gzipped: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(gzipped)).use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `patcher writes new als atomically and leaves no temp files on success`() {
        val tmpDir = createTempDirectory("patcher")
        val als = tmpDir.resolve("MyTrack.als")
        Files.write(als, gzipBytesOf("rewriter/oneSampleRef.als.xml"))
        val before = Files.getLastModifiedTime(als)
        // Sleep just enough that mtime has a chance to differ on filesystems with second-resolution mtimes.
        Thread.sleep(1100)

        val result = AlsPatcher().patch(
            als,
            mapping = mapOf("/old/missing.wav" to "/new/found.wav"),
        )
        assertEquals(AlsPatcher.Outcome.Patched, result)
        assertContains(ungzipToString(Files.readAllBytes(als)), "/new/found.wav")
        assertNotEquals(before, Files.getLastModifiedTime(als))
        val tempCount = Files.list(tmpDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".tmp") || it.fileName.toString().contains(".patcher-tmp") }.count()
        }
        assertEquals(0, tempCount)
    }

    @Test
    fun `patcher skips when file is held by another process`() {
        val tmpDir = createTempDirectory("patcher")
        val als = tmpDir.resolve("Open.als").also { Files.write(it, gzipBytesOf("rewriter/oneSampleRef.als.xml")) }
        val patcher = AlsPatcher(busyDetector = { _ -> true })
        assertEquals(
            AlsPatcher.Outcome.SkippedBusy,
            patcher.patch(als, mapOf("/old/missing.wav" to "/new/x.wav")),
        )
    }

    @Test
    fun `empty mapping returns NoChange`() {
        val tmpDir = createTempDirectory("patcher")
        val als = tmpDir.resolve("Empty.als").also { Files.write(it, gzipBytesOf("rewriter/oneSampleRef.als.xml")) }
        val before = Files.readAllBytes(als)
        assertEquals(AlsPatcher.Outcome.NoChange, AlsPatcher().patch(als, emptyMap()))
        assertTrue(before.contentEquals(Files.readAllBytes(als)), "file untouched on NoChange")
    }

    @Test
    fun `non-matching mapping preserves the original Path values in the file`() {
        // Note: gzip re-encoding is not byte-identity, and StAX rewrite normalizes `<Foo/>` to
        // `<Foo></Foo>`. So a non-matching mapping currently goes through the Patched path and
        // the file is rewritten — but the *path attribute values* (which is what the rewriter
        // targets) must still match the originals.
        val tmpDir = createTempDirectory("patcher")
        val als = tmpDir.resolve("Untouched.als").also { Files.write(it, gzipBytesOf("rewriter/oneSampleRef.als.xml")) }
        val result = AlsPatcher().patch(als, mapOf("/does/not/match.wav" to "/anything.wav"))
        assertTrue(
            result is AlsPatcher.Outcome.Patched || result is AlsPatcher.Outcome.NoChange,
            "expected Patched or NoChange but got $result",
        )
        val afterXml = ungzipToString(Files.readAllBytes(als))
        assertContains(afterXml, "/old/missing.wav")
        assertTrue("/anything.wav" !in afterXml, "non-matching target must not appear in file")
    }

    @Test
    fun `missing source file yields Failed outcome`() {
        val tmpDir = createTempDirectory("patcher")
        val als = tmpDir.resolve("DoesNotExist.als")
        val result = AlsPatcher().patch(als, mapOf("/a" to "/b"))
        assertTrue(result is AlsPatcher.Outcome.Failed, "expected Failed but got $result")
    }

    @Test
    fun `restore writes provided bytes back atomically and returns Patched`() {
        // Round-trip: patch flips the .als content, restore brings back the *exact* original
        // bytes (byte-for-byte, including the gzip representation captured pre-patch).
        val tmpDir = createTempDirectory("patcher-restore")
        val als = tmpDir.resolve("Round.als")
        val originalBytes = gzipBytesOf("rewriter/oneSampleRef.als.xml")
        Files.write(als, originalBytes)
        val patcher = AlsPatcher()

        // Patch: file content changes.
        val patchOutcome = patcher.patch(als, mapOf("/old/missing.wav" to "/new/found.wav"))
        assertEquals(AlsPatcher.Outcome.Patched, patchOutcome)
        assertTrue(
            !Files.readAllBytes(als).contentEquals(originalBytes),
            "patch should have changed the bytes",
        )

        // Restore: bytes go back exactly.
        val restoreOutcome = patcher.restore(als, originalBytes)
        assertEquals(AlsPatcher.Outcome.Patched, restoreOutcome)
        assertTrue(
            originalBytes.contentEquals(Files.readAllBytes(als)),
            "restore must put back the *exact* original bytes",
        )

        // No temp files left behind.
        val tempCount = Files.list(tmpDir).use { stream ->
            stream.filter { it.fileName.toString().contains(".patcher-tmp") }.count()
        }
        assertEquals(0, tempCount)
    }

    @Test
    fun `restore skips when busy`() {
        val tmpDir = createTempDirectory("patcher-restore-busy")
        val als = tmpDir.resolve("Busy.als").also { Files.write(it, gzipBytesOf("rewriter/oneSampleRef.als.xml")) }
        val before = Files.readAllBytes(als)
        val patcher = AlsPatcher(busyDetector = { _ -> true })
        val outcome = patcher.restore(als, byteArrayOf(0x42))
        assertEquals(AlsPatcher.Outcome.SkippedBusy, outcome)
        assertTrue(before.contentEquals(Files.readAllBytes(als)), "busy file untouched")
    }
}
