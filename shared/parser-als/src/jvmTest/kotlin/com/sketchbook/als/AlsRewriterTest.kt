package com.sketchbook.als

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlsRewriterTest {

    private fun resourceBytes(name: String): ByteArray {
        val cl = Thread.currentThread().contextClassLoader ?: AlsRewriterTest::class.java.classLoader
        val stream = cl.getResourceAsStream(name)
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
        GZIPInputStream(ByteArrayInputStream(gzipped)).use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `rewrites SampleRef Path Value while preserving everything else`() {
        val original = gzipBytesOf("rewriter/oneSampleRef.als.xml")
        val rewritten = AlsRewriter.rewriteSamplePaths(
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
        val rewritten = AlsRewriter.rewriteSamplePaths(
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
        val rewritten = AlsRewriter.rewriteSamplePaths(
            original,
            mapping = mapOf("/does/not/match.wav" to "/anything.wav"),
        )
        val text = ungzipToString(rewritten)
        assertContains(text, """<Path Value="/old/missing.wav"""")
        assertFalse("/anything.wav" in text)
    }
}
