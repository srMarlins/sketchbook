package com.sketchbook.als

import com.sketchbook.core.SampleRefEdit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlsRewriterTest {
    private fun resourceBytes(name: String): ByteArray {
        val cl = Thread.currentThread().contextClassLoader ?: AlsRewriterTest::class.java.classLoader
        val stream =
            cl.getResourceAsStream(name)
                ?: error("test resource not found on classpath: $name")
        return stream.use { it.readBytes() }
    }

    private fun gzipBytesOf(name: String): ByteArray {
        val raw = resourceBytes(name)
        val out = ByteArrayOutputStream(raw.size + 64)
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    private fun ungzipToString(gzipped: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(gzipped)).use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    @Test
    fun `rewrites SampleRef Path Value while preserving everything else`() {
        val original = gzipBytesOf("rewriter/oneSampleRef.als.xml")
        val rewritten =
            AlsRewriter.rewriteSamplePaths(
                original,
                mapping = mapOf("/old/missing.wav" to "/new/found.wav"),
            )
        val text = ungzipToString(rewritten)
        assertContains(text, """<Path Value="/new/found.wav"""")
        assertFalse("/old/missing.wav" in text, "old path must be gone")
        assertTrue(text.startsWith("<?xml"), "XML preamble preserved")
        // Surrounding structure intact.
        assertContains(text, "<Ableton")
        assertContains(text, "<SampleRef")
        assertContains(text, """<Name Value="missing.wav"""")
    }

    @Test
    fun `rewrites Mac-style Path Value to POSIX target via mapping`() {
        val original = gzipBytesOf("rewriter/oneSampleRefMac.als.xml")
        val macSrc = "Macintosh HD:/Users/jay/Samples/kick.wav"
        val posix = "/Users/jay/Samples/kick.wav"
        val rewritten =
            AlsRewriter.rewriteSamplePaths(
                original,
                mapping = mapOf(macSrc to posix),
            )
        val text = ungzipToString(rewritten)
        assertContains(text, """<Path Value="$posix"""")
        assertFalse(macSrc in text, "old mac-style path must be gone")
        assertTrue(text.startsWith("<?xml"), "XML preamble preserved")
    }

    @Test
    fun `empty mapping returns the input bytes unchanged`() {
        val original = gzipBytesOf("rewriter/oneSampleRef.als.xml")
        val rewritten = AlsRewriter.rewriteSamplePaths(original, mapping = emptyMap())
        assertTrue(original.contentEquals(rewritten), "no-op mapping should return identical bytes")
    }

    @Test
    fun `non-matching mapping leaves Path values intact`() {
        val original = gzipBytesOf("rewriter/oneSampleRef.als.xml")
        val rewritten =
            AlsRewriter.rewriteSamplePaths(
                original,
                mapping = mapOf("/does/not/match.wav" to "/anything.wav"),
            )
        val text = ungzipToString(rewritten)
        assertContains(text, """<Path Value="/old/missing.wav"""")
        assertFalse("/anything.wav" in text)
    }

    @Test
    fun `rewrites SampleRef RelativePath Value`() {
        val original = gzipBytesOf("rewriter/oneSampleRef.als.xml")
        val rewritten =
            AlsRewriter.rewriteSamplePaths(
                original,
                mapping = mapOf("rel/missing.wav" to "rel/found.wav"),
            )
        val text = ungzipToString(rewritten)
        assertContains(text, """<RelativePath Value="rel/found.wav"""")
        assertFalse("rel/missing.wav" in text)
    }

    @Test fun rewriteSampleRefsUpdatesPrimaryAndOriginalFileRefSibling() {
        val gz = javaClass.getResourceAsStream("/live12-sampleref.xml.gz")!!.readBytes()
        val edit =
            SampleRefEdit(
                oldPath = "D:/Audio/Project/Samples/Imported/kick.wav",
                newPath = "E:/NewLocation/kick.wav",
                newRelativePath = "Samples/Imported/kick.wav",
                newRelativePathType = 1,
                newOriginalFileSize = 58394528L,
                newOriginalCrc = 0L, // force Live to recompute
                newLastModDate = 1700000000L,
            )
        val out = AlsRewriter.rewriteSampleRefs(gz, listOf(edit))
        val md = AlsParser.parse(out.inputStream())
        assertEquals(1, md.sampleRefs.size)
        val s = md.sampleRefs[0]
        assertEquals("E:/NewLocation/kick.wav", s.rawPath)
        assertEquals(1, s.relativePathType)
        assertEquals(0L, s.originalCrc)
        assertEquals(1700000000L, s.lastModDate)
        // Verify the OriginalFileRef sibling was patched too — re-parse the gunzipped output as raw
        // XML and assert the second Path also moved.
        val xml =
            java.util.zip
                .GZIPInputStream(out.inputStream())
                .readBytes()
                .decodeToString()
        val pathOccurrences =
            Regex("""<Path Value="([^"]+)"></Path>|<Path Value="([^"]+)"/>""")
                .findAll(xml)
                .map { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
                .toList()
        assertEquals(2, pathOccurrences.size)
        assertTrue(pathOccurrences.all { it == "E:/NewLocation/kick.wav" })
    }

    @Test fun rewriteSamplePathsStillWorksAsThinWrapper() {
        val gz = javaClass.getResourceAsStream("/live12-sampleref.xml.gz")!!.readBytes()
        val out =
            AlsRewriter.rewriteSamplePaths(
                gz,
                mapOf("D:/Audio/Project/Samples/Imported/kick.wav" to "E:/x/kick.wav"),
            )
        val md = AlsParser.parse(out.inputStream())
        assertEquals("E:/x/kick.wav", md.sampleRefs[0].rawPath)
    }

    @Test fun rewriteSampleRefsIsIdempotentWhenOldPathDoesNotMatch() {
        val gz = javaClass.getResourceAsStream("/live12-sampleref.xml.gz")!!.readBytes()
        val edit = SampleRefEdit(oldPath = "no-such-path.wav", newPath = "x.wav")
        val out = AlsRewriter.rewriteSampleRefs(gz, listOf(edit))
        // gunzip both sides and compare — byte-equal
        val before =
            java.util.zip
                .GZIPInputStream(gz.inputStream())
                .readBytes()
        val after =
            java.util.zip
                .GZIPInputStream(out.inputStream())
                .readBytes()
        assertContentEquals(before, after)
    }

    @Test
    fun `does not rewrite Path Value outside SampleRef subtree`() {
        val original = gzipBytesOf("rewriter/pathOutsideSampleRef.als.xml")
        val rewritten =
            AlsRewriter.rewriteSamplePaths(
                original,
                mapping = mapOf("/old/missing.wav" to "/new/found.wav"),
            )
        val text = ungzipToString(rewritten)
        // Inside SampleRef: rewritten.
        assertContains(text, """<SampleRef>""")
        // BrowserSelection's Path stayed put.
        assertContains(text, """<BrowserSelection>""")
        // Original had 2 occurrences of /old/missing.wav (BrowserSelection + SampleRef);
        // post-rewrite, exactly 1 should remain (BrowserSelection) and exactly 1 of /new/found.wav.
        val oldNeedle = "/old/missing.wav"
        val newNeedle = "/new/found.wav"
        assertEquals(1, text.windowed(size = oldNeedle.length).count { it == oldNeedle })
        assertEquals(1, text.windowed(size = newNeedle.length).count { it == newNeedle })
    }
}
