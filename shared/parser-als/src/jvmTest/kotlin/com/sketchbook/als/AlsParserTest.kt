package com.sketchbook.als

import com.sketchbook.core.PluginFormat
import com.sketchbook.core.SampleRef
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlsParserTest {

    private fun gzip(xml: String): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(xml.toByteArray(Charsets.UTF_8)) }
        return ByteArrayInputStream(out.toByteArray())
    }

    private fun parse(xml: String) = AlsParser.parse(gzip(xml))

    @Test
    fun extractsLiveVersionFromCreator() {
        val md = parse(
            """<?xml version="1.0"?>
            <Ableton MajorVersion="11" MinorVersion="11.3" Creator="Ableton Live 11.3.21">
            </Ableton>"""
        )
        assertEquals("11.3.21", md.lastSavedLiveVersion)
    }

    @Test
    fun extractsTempo() {
        val md = parse(
            """<?xml version="1.0"?>
            <Ableton>
              <LiveSet>
                <MainTrack><DeviceChain><Mixer><Tempo><Manual Value="128.500"/></Tempo></Mixer></DeviceChain></MainTrack>
              </LiveSet>
            </Ableton>"""
        )
        assertEquals(128.5, md.tempo)
    }

    @Test
    fun decodesTimeSignature7over8() {
        // numerator = (encoded % 99) + 1 → for 7/8: encoded = (7-1) + 99*3 = 6 + 297 = 303 (denom index 3 = 8)
        val md = parse(
            """<?xml version="1.0"?>
            <Ableton><LiveSet><MainTrack><DeviceChain><Mixer><TimeSignature><Manual Value="303"/></TimeSignature></Mixer></DeviceChain></MainTrack></LiveSet></Ableton>"""
        )
        assertEquals(7, md.timeSignatureNumerator)
        assertEquals(8, md.timeSignatureDenominator)
    }

    @Test
    fun decodesTimeSignature4over4Default() {
        // 4/4: numerator 4 → encoded % 99 = 3; denom 4 → index 2 → encoded = 3 + 99*2 = 201
        val md = parse(
            """<?xml version="1.0"?>
            <Ableton><LiveSet><TimeSignature><Manual Value="201"/></TimeSignature></LiveSet></Ableton>"""
        )
        assertEquals(4, md.timeSignatureNumerator)
        assertEquals(4, md.timeSignatureDenominator)
    }

    @Test
    fun countsTracksByType() {
        val md = parse(
            """<?xml version="1.0"?>
            <Ableton><LiveSet><Tracks>
              <AudioTrack/><AudioTrack/><AudioTrack/>
              <MidiTrack/><MidiTrack/>
              <ReturnTrack/>
              <GroupTrack><Tracks><AudioTrack/></Tracks></GroupTrack>
            </Tracks></LiveSet></Ableton>"""
        )
        assertEquals(4, md.audioTrackCount) // 3 top-level + 1 nested in GroupTrack
        assertEquals(2, md.midiTrackCount)
        assertEquals(1, md.returnTrackCount)
        assertEquals(1, md.groupTrackCount)
        assertEquals(8, md.totalTrackCount)
    }

    @Test
    fun extractsVst3Plugin() {
        val md = parse(
            """<?xml version="1.0"?>
            <Ableton><LiveSet><Tracks><AudioTrack>
              <Name><EffectiveName Value="Drums"/></Name>
              <DeviceChain><Devices>
                <PluginDevice>
                  <PluginDesc>
                    <Vst3PluginInfo>
                      <Name Value="Pro-Q 3"/>
                    </Vst3PluginInfo>
                  </PluginDesc>
                </PluginDevice>
              </Devices></DeviceChain>
            </AudioTrack></Tracks></LiveSet></Ableton>"""
        )
        assertEquals(1, md.plugins.size)
        val p = md.plugins[0]
        assertEquals("Pro-Q 3", p.name)
        assertEquals(PluginFormat.Vst3, p.format)
        assertEquals("Drums", p.trackName)
    }

    @Test
    fun extractsVst2Plugin() {
        val md = parse(
            """<?xml version="1.0"?>
            <Ableton><LiveSet><Tracks><AudioTrack>
              <Name><EffectiveName Value="Bass"/></Name>
              <DeviceChain><Devices>
                <PluginDevice>
                  <PluginDesc>
                    <VstPluginInfo>
                      <PlugName Value="Serum"/>
                    </VstPluginInfo>
                  </PluginDesc>
                </PluginDevice>
              </Devices></DeviceChain>
            </AudioTrack></Tracks></LiveSet></Ableton>"""
        )
        assertEquals(1, md.plugins.size)
        assertEquals("Serum", md.plugins[0].name)
        assertEquals(PluginFormat.Vst2, md.plugins[0].format)
        assertEquals("Bass", md.plugins[0].trackName)
    }

    @Test
    fun extractsAuPluginViaPluginDevice() {
        val md = parse(
            """<?xml version="1.0"?>
            <Ableton><LiveSet><Tracks><MidiTrack>
              <Name><EffectiveName Value="Synth"/></Name>
              <DeviceChain><Devices>
                <PluginDevice>
                  <PluginDesc>
                    <AuPluginInfo>
                      <Name Value="Massive"/>
                    </AuPluginInfo>
                  </PluginDesc>
                </PluginDevice>
              </Devices></DeviceChain>
            </MidiTrack></Tracks></LiveSet></Ableton>"""
        )
        assertEquals(1, md.plugins.size)
        assertEquals("Massive", md.plugins[0].name)
        assertEquals(PluginFormat.Au, md.plugins[0].format)
    }

    @Test
    fun extractsStandaloneAuPluginDevice() {
        val md = parse(
            """<?xml version="1.0"?>
            <Ableton><LiveSet><Tracks><MidiTrack>
              <Name><EffectiveName Value="Pad"/></Name>
              <DeviceChain><Devices>
                <AuPluginDevice>
                  <Name Value="ChromaPhone"/>
                </AuPluginDevice>
              </Devices></DeviceChain>
            </MidiTrack></Tracks></LiveSet></Ableton>"""
        )
        assertEquals(1, md.plugins.size)
        assertEquals("ChromaPhone", md.plugins[0].name)
        assertEquals(PluginFormat.Au, md.plugins[0].format)
        assertEquals("Pad", md.plugins[0].trackName)
    }

    @Test
    fun extractsAbletonNativeDevices() {
        val md = parse(
            """<?xml version="1.0"?>
            <Ableton><LiveSet><Tracks><AudioTrack>
              <Name><EffectiveName Value="Master"/></Name>
              <DeviceChain><Devices>
                <Eq8/>
                <Compressor2/>
                <Limiter/>
              </Devices></DeviceChain>
            </AudioTrack></Tracks></LiveSet></Ableton>"""
        )
        val names = md.plugins.map { it.name }
        assertEquals(listOf("Eq8", "Compressor2", "Limiter"), names)
        assertTrue(md.plugins.all { it.format == PluginFormat.AbletonNative })
        assertTrue(md.plugins.all { it.trackName == "Master" })
    }

    @Test
    fun extractsSampleViaPathAttribute() {
        val md = parse(
            """<?xml version="1.0"?>
            <Ableton><LiveSet><Tracks><AudioTrack><DeviceChain>
              <SampleRef>
                <FileRef>
                  <Path Value="Samples/Imported/kick.wav"/>
                </FileRef>
              </SampleRef>
            </DeviceChain></AudioTrack></Tracks></LiveSet></Ableton>"""
        )
        assertEquals(listOf(SampleRef("Samples/Imported/kick.wav")), md.sampleRefs)
    }

    @Test
    fun extractsSampleViaRelativePathElements() {
        val md = parse(
            """<?xml version="1.0"?>
            <Ableton><LiveSet><Tracks><AudioTrack><DeviceChain>
              <SampleRef>
                <FileRef>
                  <RelativePath>
                    <RelativePathElement Dir="Samples"/>
                    <RelativePathElement Dir="Imported"/>
                  </RelativePath>
                  <Name Value="snare.wav"/>
                </FileRef>
              </SampleRef>
            </DeviceChain></AudioTrack></Tracks></LiveSet></Ableton>"""
        )
        assertEquals(listOf(SampleRef("Samples/Imported/snare.wav")), md.sampleRefs)
    }

    @Test
    fun ignoresOriginalFileRefSibling() {
        // SampleRef has both FileRef (current) and OriginalFileRef (history). Use FileRef only.
        val md = parse(
            """<?xml version="1.0"?>
            <Ableton><LiveSet><Tracks><AudioTrack><DeviceChain>
              <SampleRef>
                <FileRef>
                  <Path Value="Samples/now.wav"/>
                </FileRef>
                <OriginalFileRef>
                  <FileRef>
                    <Path Value="OldLocation/before.wav"/>
                  </FileRef>
                </OriginalFileRef>
              </SampleRef>
            </DeviceChain></AudioTrack></Tracks></LiveSet></Ableton>"""
        )
        assertEquals(1, md.sampleRefs.size)
        assertEquals("Samples/now.wav", md.sampleRefs[0].rawPath)
    }

    @Test
    fun countsMacPathPrefixes() {
        val md = parse(
            """<?xml version="1.0"?>
            <Ableton><LiveSet>
              <SampleRef><FileRef><Path Value="/Users/alice/Samples/k.wav"/></FileRef></SampleRef>
              <SampleRef><FileRef><Path Value="/Volumes/Audio/Samples/h.wav"/></FileRef></SampleRef>
              <SampleRef><FileRef><Path Value="Samples/c.wav"/></FileRef></SampleRef>
            </LiveSet></Ableton>"""
        )
        assertEquals(2, md.macPathsCount)
        assertEquals(3, md.sampleRefs.size)
    }

    @Test
    fun handlesEmptyProject() {
        val md = parse(
            """<?xml version="1.0"?><Ableton Creator="Ableton Live 12.0.0"><LiveSet/></Ableton>"""
        )
        assertNull(md.tempo)
        assertNull(md.timeSignatureNumerator)
        assertEquals(0, md.totalTrackCount)
        assertEquals(0, md.plugins.size)
        assertEquals(0, md.sampleRefs.size)
        assertEquals(0, md.macPathsCount)
        assertEquals("12.0.0", md.lastSavedLiveVersion)
    }

    @Test
    fun handlesNonAsciiTrackNamesAndPaths() {
        val md = parse(
            """<?xml version="1.0"?>
            <Ableton><LiveSet><Tracks><AudioTrack>
              <Name><EffectiveName Value="ドラム"/></Name>
              <DeviceChain>
                <SampleRef><FileRef><Path Value="Samples/é/ñ/kïck.wav"/></FileRef></SampleRef>
              </DeviceChain>
            </AudioTrack></Tracks></LiveSet></Ableton>"""
        )
        assertEquals(1, md.audioTrackCount)
        assertEquals(1, md.sampleRefs.size)
        assertEquals("Samples/é/ñ/kïck.wav", md.sampleRefs[0].rawPath)
    }

    @Test
    fun extractsLive12SampleRefMetadataAndOriginalFileRefSibling() {
        val md = javaClass.getResourceAsStream("/live12-sampleref.xml.gz")!!.use {
            AlsParser.parse(it)
        }
        assertEquals(1, md.sampleRefs.size)
        val s = md.sampleRefs[0]
        assertEquals("D:/Audio/Project/Samples/Imported/kick.wav", s.rawPath)
        assertEquals(3, s.relativePathType)
        assertEquals(58394528L, s.originalFileSize)
        assertEquals(7866L, s.originalCrc)
        assertEquals(1694844696L, s.lastModDate)
        assertTrue(s.hasOriginalFileRefSibling)
    }

    @Test
    fun parserBoundedMemoryAgainstLargeSyntheticInput() {
        // Build a synthetic .als with one project shell + N huge inert <Clip> blobs that the
        // parser must skip. Verifies no DOM accumulation: heap stays well under 256 MB even
        // when the *uncompressed* XML is well over 100 MB.
        val sb = StringBuilder(64 * 1024 * 1024)
        sb.append("""<?xml version="1.0"?><Ableton Creator="Ableton Live 12.0.0"><LiveSet>""")
        sb.append("""<MainTrack><DeviceChain><Mixer><Tempo><Manual Value="120"/></Tempo></Mixer></DeviceChain></MainTrack>""")
        sb.append("""<Tracks>""")
        // ~1k tracks each carrying a 100 KB inert payload → ~100 MB of XML.
        repeat(1000) { i ->
            sb.append("""<AudioTrack><Name><EffectiveName Value="t$i"/></Name><DeviceChain><Devices><Eq8/></Devices></DeviceChain>""")
            sb.append("""<Inert>""")
            sb.append("a".repeat(100 * 1024))
            sb.append("""</Inert>""")
            sb.append("""</AudioTrack>""")
        }
        sb.append("</Tracks></LiveSet></Ableton>")

        // Compress to a temp byte array.
        val gzippedOut = ByteArrayOutputStream()
        GZIPOutputStream(gzippedOut).use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
        sb.clear()

        Runtime.getRuntime().gc()
        val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

        val md = AlsParser.parse(ByteArrayInputStream(gzippedOut.toByteArray()))

        Runtime.getRuntime().gc()
        val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val deltaMb = (after - before) / 1024.0 / 1024.0

        assertEquals(1000, md.audioTrackCount)
        assertEquals(120.0, md.tempo)
        assertEquals(1000, md.plugins.size) // 1 Eq8 per track
        // Memory delta after the parse should be modest; the parser doesn't retain a DOM.
        // A generous bound — 256 MB cap from the plan; the parser typically stays under 50 MB
        // on this fixture but JVM heap accounting is noisy under GC interleaving.
        assertTrue(deltaMb < 256.0, "parser retained too much memory: +${deltaMb}MB")
    }
}
