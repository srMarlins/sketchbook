package com.sketchbook.integration

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

/**
 * Builders for synthetic Ableton project trees used by the integration suite. Each
 * `write*Project` method writes a complete project directory (`Project.als` plus any
 * `Samples/` and `Ableton Project Info/` siblings the test needs) under [parent] and returns
 * the project directory. XML templates are kept side-by-side with the writers so a reviewer
 * can see exactly what the parser will see.
 */
object Fixtures {

    // Common preamble. Live's parser checks Creator for the version string.
    private const val PREAMBLE =
        """<?xml version="1.0" encoding="UTF-8"?><Ableton MajorVersion="11" MinorVersion="11.3" Creator="Ableton Live 11.3.21">"""
    private const val POSTAMBLE = "</Ableton>"

    /** Clean project: parses ok, 1 audio track, 1 sample present on disk, no mac paths. */
    fun writeCleanProject(parent: Path, name: String = "clean"): Path {
        val dir = ensureProjectDir(parent, name)
        val sample = "loop.wav"
        writeWav(dir.resolve("Samples").resolve(sample))
        val xml = """
            $PREAMBLE
              <LiveSet>
                <Tracks><AudioTrack/></Tracks>
                <SampleRef>
                  <FileRef>
                    <RelativePathType Value="3"/>
                    <RelativePath>
                      <RelativePathElement Dir="Samples"/>
                    </RelativePath>
                    <Name Value="$sample"/>
                    <Path Value="Samples/$sample"/>
                  </FileRef>
                </SampleRef>
              </LiveSet>
            $POSTAMBLE
        """.trimIndent()
        // Live's "Project Folder" convention: the .als is named after the project, not "Project.als".
        // The scanner derives `projects.name` from the .als filename, so naming it after the
        // fixture's `name` keeps each project distinguishable post-scan.
        writeGzippedAls(dir.resolve("$name.als"), xml)
        return dir
    }

    /** References two samples but only one is on disk — the second drives a missing finding. */
    fun writeMissingSamplesProject(parent: Path, name: String = "missing_samples"): Path {
        val dir = ensureProjectDir(parent, name)
        val foundName = "found.wav"
        val missingName = "missing.wav"
        writeWav(dir.resolve("Samples").resolve(foundName))
        val xml = """
            $PREAMBLE
              <LiveSet>
                <Tracks><AudioTrack/></Tracks>
                <SampleRef>
                  <FileRef>
                    <RelativePathType Value="3"/>
                    <RelativePath><RelativePathElement Dir="Samples"/></RelativePath>
                    <Name Value="$foundName"/>
                    <Path Value="Samples/$foundName"/>
                  </FileRef>
                </SampleRef>
                <SampleRef>
                  <FileRef>
                    <RelativePathType Value="3"/>
                    <RelativePath><RelativePathElement Dir="Samples"/></RelativePath>
                    <Name Value="$missingName"/>
                    <Path Value="Samples/$missingName"/>
                  </FileRef>
                </SampleRef>
              </LiveSet>
            $POSTAMBLE
        """.trimIndent()
        writeGzippedAls(dir.resolve("$name.als"), xml)
        return dir
    }

    /** Sample paths are Mac-style absolute paths -> `mac_paths_count > 0`. */
    fun writeMacPathsProject(parent: Path, name: String = "mac_paths"): Path {
        val dir = ensureProjectDir(parent, name)
        val xml = """
            $PREAMBLE
              <LiveSet>
                <Tracks><AudioTrack/></Tracks>
                <SampleRef>
                  <FileRef>
                    <RelativePathType Value="3"/>
                    <Name Value="kick.wav"/>
                    <Path Value="/Users/somebody/Music/Samples/kick.wav"/>
                  </FileRef>
                </SampleRef>
              </LiveSet>
            $POSTAMBLE
        """.trimIndent()
        writeGzippedAls(dir.resolve("$name.als"), xml)
        return dir
    }

    /**
     * Parse-fail fixture: writes a `.als` with non-gzipped garbage bytes so [AlsParser.parse]
     * throws. Returns the file path (no project dir wrapping needed).
     */
    fun writeParseFailProject(parent: Path, name: String = "bad"): Path {
        Files.createDirectories(parent)
        val file = parent.resolve("$name.als")
        Files.write(file, "this is not a gzipped als".toByteArray())
        return file
    }

    /**
     * Samples corpus root: a few WAV files. Includes one named "missing.wav" matching the file
     * referenced (but absent) in [writeMissingSamplesProject], so the auto-match test gets a
     * candidate hit.
     */
    fun writeSampleCorpus(parent: Path): Path {
        Files.createDirectories(parent)
        writeWav(parent.resolve("missing.wav"))
        writeWav(parent.resolve("kick.wav"))
        writeWav(parent.resolve("snare.wav"))
        return parent
    }

    // -------------------------------------------------------------- private

    private fun ensureProjectDir(parent: Path, name: String): Path {
        val dir = parent.resolve("$name Project")
        Files.createDirectories(dir)
        Files.createDirectories(dir.resolve("Samples"))
        Files.createDirectories(dir.resolve("Ableton Project Info"))
        return dir
    }

    private fun writeGzippedAls(target: Path, xml: String) {
        Files.createDirectories(target.parent)
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(xml.toByteArray(Charsets.UTF_8)) }
        Files.write(target, out.toByteArray())
    }

    /** Minimal valid 16-bit PCM mono WAV at 44.1 kHz — 64 sample-frames of silence. */
    private fun writeWav(target: Path) {
        Files.createDirectories(target.parent)
        val sampleCount = 64
        val byteRate = 44100 * 2
        val dataSize = sampleCount * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1)               // PCM
        buf.putShort(1)               // mono
        buf.putInt(44100)
        buf.putInt(byteRate)
        buf.putShort(2)               // block align
        buf.putShort(16)              // bits per sample
        buf.put("data".toByteArray())
        buf.putInt(dataSize)
        repeat(sampleCount) { buf.putShort(0) }
        Files.write(target, buf.array())
    }
}
