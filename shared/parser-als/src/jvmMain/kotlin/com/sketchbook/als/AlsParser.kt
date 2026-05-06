package com.sketchbook.als

import com.sketchbook.core.PluginFormat
import com.sketchbook.core.PluginRef
import com.sketchbook.core.ProjectMetadata
import com.sketchbook.core.SampleRef
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import javax.xml.XMLConstants
import javax.xml.namespace.QName
import javax.xml.stream.XMLEventReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.events.EndElement
import javax.xml.stream.events.StartElement

/**
 * Streaming parser for Ableton Live `.als` projects.
 *
 * **Memory contract:** uses a StAX event reader on a gunzipped stream. No DOM is built; the only
 * state retained across the file is a small set of accumulators (counters, plugin/sample lists,
 * a track-ancestor stack). Heap usage stays bounded regardless of file size — verified up to the
 * 543 MB project that previously OOM'd a Python lxml DOM parse.
 *
 * Output mirrors the Python reference parser (`packages/core/audio_core/parser/als.py`) field for
 * field, modulo casing.
 */
object AlsParser {

    private val factory: XMLInputFactory = XMLInputFactory.newInstance().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty("javax.xml.stream.isSupportingExternalEntities", false)
        setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
        // Caps entity expansion regardless of DTD settings; defense-in-depth on user-supplied .als.
        runCatching { setProperty(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        // .als never uses CDATA for bulk data and the Driver ignores Characters events anyway.
        setProperty(XMLInputFactory.IS_COALESCING, false)
    }

    private val creatorRegex = Regex("""Ableton Live\s+([\d.]+)""")

    /** Live's encoded time-signature value uses this denominator table; numerator = (val % 99) + 1. */
    private val denomTable = intArrayOf(1, 2, 4, 8, 16, 32, 64)

    private val trackTags = setOf(
        "MidiTrack", "AudioTrack", "ReturnTrack", "GroupTrack", "MasterTrack", "MainTrack",
    )

    private val nativeDeviceTags = setOf(
        "Eq8", "EqEight", "Compressor2", "Compressor", "Limiter", "Saturator",
        "AutoFilter", "Reverb", "Delay", "Echo", "Operator", "Wavetable",
        "Simpler", "Sampler", "DrumGroupDevice", "InstrumentRack",
        "AudioEffectGroupDevice", "MidiEffectGroupDevice", "InstrumentGroupDevice",
        "Utility", "Gate", "Glue", "MultibandDynamics", "Spectrum", "Tuner",
        "Phaser", "Chorus", "Flanger", "AutoPan", "BeatRepeat", "Vocoder",
        "FrequencyShifter", "GrainDelay", "DrumBuss", "Pedal", "Amp", "Cabinet",
        "Erosion", "Overdrive",
    )

    private val macPathPrefixes = listOf("/Volumes/", "/Users/", "/Library/", "/Applications/", "/private/")

    fun parse(path: Path): ProjectMetadata {
        if (!Files.isRegularFile(path)) throw IOException("not a file: $path")
        return Files.newInputStream(path).use { parse(it) }
    }

    /** Parse a gzipped `.als` byte stream. Caller owns the stream lifecycle. */
    fun parse(gzipped: InputStream): ProjectMetadata {
        // XMLEventReader.close() does NOT close its underlying stream; close gz ourselves so
        // native zlib handles aren't leaked across a bulk scan.
        GZIPInputStream(BufferedInputStream(gzipped)).use { gz ->
            val reader = factory.createXMLEventReader(gz)
            try {
                return Driver(reader).run()
            } finally {
                reader.close()
            }
        }
    }

    // ---- internals ----

    private class TrackFrame(val tag: String, var name: String? = null)

    private class PendingPlugin(
        val track: String?,
        val forcedFormat: PluginFormat? = null,
    ) {
        var format: PluginFormat? = null
        var name: String? = null
    }

    private class PendingSample {
        var collectedPath: String? = null
        val relParts: MutableList<String> = mutableListOf()
        var relName: String? = null

        var relativePathType: Int? = null
        var originalFileSize: Long? = null
        var originalCrc: Long? = null
        var lastModDate: Long? = null
        var hasOriginalFileRefSibling: Boolean = false

        /** True while StAX cursor is inside the primary FileRef (the direct SampleRef child). */
        var insidePrimaryFileRef: Boolean = false

        fun resolvedPath(): String? {
            collectedPath?.let { return it }
            val joined = (relParts + listOfNotNull(relName)).joinToString("/")
            return joined.ifEmpty { null }
        }
    }

    private class Driver(private val reader: XMLEventReader) {

        private val pathStack = ArrayDeque<String>()
        private val trackStack = ArrayDeque<TrackFrame>()

        private var tempo: Double? = null
        private var tsValue: Int? = null
        private var liveVersion: String? = null
        private var rootNote: Int? = null
        private var scaleName: String? = null
        private var audio = 0
        private var midi = 0
        private var returnTracks = 0
        private var group = 0
        private val plugins = mutableListOf<PluginRef>()
        private val samples = mutableListOf<SampleRef>()
        private var macPaths = 0
        private var pendingPlugin: PendingPlugin? = null
        private var pendingSample: PendingSample? = null

        fun run(): ProjectMetadata {
            while (reader.hasNext()) {
                val event = reader.nextEvent()
                when {
                    event.isStartElement -> handleStart(event.asStartElement())
                    event.isEndElement -> handleEnd(event.asEndElement())
                    else -> {}
                }
            }
            val tn = decodeTimeSignature(tsValue)
            return ProjectMetadata(
                tempo = tempo,
                timeSignatureNumerator = tn?.first,
                timeSignatureDenominator = tn?.second,
                audioTrackCount = audio,
                midiTrackCount = midi,
                returnTrackCount = returnTracks,
                groupTrackCount = group,
                plugins = plugins.toList(),
                sampleRefs = samples.toList(),
                lastSavedLiveVersion = liveVersion,
                macPathsCount = macPaths,
                keySignature = deriveKeySignature(rootNote, scaleName),
            )
        }

        private fun handleStart(start: StartElement) {
            val tag = start.name.localPart
            pathStack.addLast(tag)

            // Live version from root.
            if (tag == "Ableton" && liveVersion == null) {
                start.attr("Creator")?.let { creator ->
                    liveVersion = creatorRegex.find(creator)?.groupValues?.get(1) ?: creator
                }
            }

            // Track open + count.
            if (tag in trackTags) {
                trackStack.addLast(TrackFrame(tag))
                when (tag) {
                    "AudioTrack" -> audio++
                    "MidiTrack" -> midi++
                    "ReturnTrack" -> returnTracks++
                    "GroupTrack" -> group++
                }
            }

            // Tempo / time-sig.
            if (tag == "Manual") {
                val v = start.attr("Value")
                if (v != null) {
                    when (parentTag()) {
                        "Tempo" -> if (tempo == null) tempo = v.toDoubleOrNull()
                        "TimeSignature" -> if (tsValue == null) tsValue = v.toIntOrNull()
                    }
                }
            }

            // Project root key: <ScaleInformation><RootNote Value="N"/><Name Value="Major|Minor|..."/></ScaleInformation>.
            if (parentTag() == "ScaleInformation") {
                when (tag) {
                    "RootNote" -> if (rootNote == null) {
                        rootNote = start.attr("Value")?.toIntOrNull()
                    }
                    "Name" -> if (scaleName == null) {
                        scaleName = start.attr("Value")
                    }
                }
            }

            // Track name: <Track>/<Name>/<EffectiveName Value="..."/>.
            if (tag == "EffectiveName" && parentTag() == "Name") {
                val grand = grandparentTag()
                if (grand != null && grand in trackTags) {
                    val v = start.attr("Value")
                    if (v != null) {
                        for (frame in trackStack.reversed()) {
                            if (frame.tag == grand && frame.name == null) {
                                frame.name = v
                                break
                            }
                        }
                    }
                }
            }

            // Plugin device boundaries.
            when (tag) {
                "PluginDevice" -> pendingPlugin = PendingPlugin(track = currentTrackName())
                "AuPluginDevice" -> if (!isInsidePluginDevice()) {
                    pendingPlugin = PendingPlugin(track = currentTrackName(), forcedFormat = PluginFormat.Au)
                }
                "SampleRef" -> pendingSample = PendingSample()
            }

            // Plugin name extraction.
            pendingPlugin?.let { plug ->
                when (tag) {
                    "Vst3PluginInfo" -> plug.format = PluginFormat.Vst3
                    "VstPluginInfo" -> plug.format = PluginFormat.Vst2
                    "AuPluginInfo" -> if (plug.forcedFormat == null) plug.format = PluginFormat.Au
                    "Name" -> when (parentTag()) {
                        "Vst3PluginInfo", "AuPluginInfo" -> plug.name = start.attr("Value") ?: plug.name
                        "AuPluginDevice" -> if (plug.forcedFormat == PluginFormat.Au) {
                            plug.name = start.attr("Value") ?: plug.name
                        }
                    }
                    "PlugName" -> if (parentTag() == "VstPluginInfo") {
                        plug.name = start.attr("Value") ?: plug.name
                    }
                    "Manufacturer" -> if (plug.name == null &&
                        (parentTag() == "AuPluginInfo" || parentTag() == "AuPluginDevice")
                    ) {
                        plug.name = start.attr("Value") ?: plug.name
                    }
                }
            }

            // Sample path + Live 11/12 metadata extraction.
            pendingSample?.let { samp ->
                // Track primary-FileRef boundaries: the FileRef whose direct parent is SampleRef.
                if (tag == "FileRef" && parentTag() == "SampleRef") {
                    samp.insidePrimaryFileRef = true
                }
                if (tag == "OriginalFileRef" && pathStack.contains("SampleRef")) {
                    samp.hasOriginalFileRefSibling = true
                }
                if (samp.insidePrimaryFileRef && parentTag() == "FileRef") {
                    when (tag) {
                        "Path" -> if (samp.collectedPath == null) {
                            start.attr("Value")?.let { samp.collectedPath = it }
                        }
                        "Name" -> start.attr("Value")?.let { samp.relName = it }
                        "RelativePathType" -> start.attr("Value")?.toIntOrNull()?.let { samp.relativePathType = it }
                        "OriginalFileSize" -> start.attr("Value")?.toLongOrNull()?.let { samp.originalFileSize = it }
                        "OriginalCrc" -> start.attr("Value")?.toLongOrNull()?.let { samp.originalCrc = it }
                    }
                }
                if (tag == "RelativePathElement" && grandparentTag() == "FileRef") {
                    val greatGrand = pathStack.elementAtOrNull(pathStack.size - 4)
                    if (greatGrand == "SampleRef") {
                        start.attr("Dir")?.takeIf { it.isNotEmpty() }?.let { samp.relParts += it }
                    }
                }
                // LastModDate lives on SampleRef directly, not FileRef.
                if (tag == "LastModDate" && parentTag() == "SampleRef") {
                    start.attr("Value")?.toLongOrNull()?.let { samp.lastModDate = it }
                }
            }

            // Native devices (only when not inside a PluginDevice — Devices is the direct parent).
            if (tag in nativeDeviceTags && parentTag() == "Devices" && pendingPlugin == null) {
                plugins += PluginRef(name = tag, format = PluginFormat.AbletonNative, trackName = currentTrackName())
            }

            // Mac-path prefix counter.
            if (tag == "Path" && parentTag() == "FileRef") {
                start.attr("Value")?.let { v ->
                    if (macPathPrefixes.any { v.startsWith(it) }) macPaths++
                }
            }
        }

        private fun handleEnd(end: EndElement) {
            val tag = end.name.localPart

            if (tag == "PluginDevice") {
                pendingPlugin?.let { p ->
                    val fmt = p.forcedFormat ?: p.format ?: PluginFormat.Unknown
                    plugins += PluginRef(name = p.name ?: defaultNameFor(fmt), format = fmt, trackName = p.track)
                }
                pendingPlugin = null
            } else if (tag == "AuPluginDevice" && pendingPlugin?.forcedFormat == PluginFormat.Au) {
                pendingPlugin?.let { p ->
                    plugins += PluginRef(
                        name = p.name ?: defaultNameFor(PluginFormat.Au),
                        format = PluginFormat.Au,
                        trackName = p.track,
                    )
                }
                pendingPlugin = null
            }

            if (tag == "FileRef") {
                pendingSample?.let { if (parentTag() == "SampleRef") it.insidePrimaryFileRef = false }
            }

            if (tag == "SampleRef") {
                pendingSample?.let { ps ->
                    ps.resolvedPath()?.let { path ->
                        samples += SampleRef(
                            rawPath = path,
                            relativePathType = ps.relativePathType,
                            originalFileSize = ps.originalFileSize,
                            originalCrc = ps.originalCrc,
                            lastModDate = ps.lastModDate,
                            hasOriginalFileRefSibling = ps.hasOriginalFileRefSibling,
                        )
                    }
                }
                pendingSample = null
            }

            if (tag in trackTags) {
                // Pop matching innermost frame of this exact tag.
                val list = trackStack.toMutableList()
                for (i in list.indices.reversed()) {
                    if (list[i].tag == tag) {
                        list.removeAt(i)
                        break
                    }
                }
                trackStack.clear()
                trackStack.addAll(list)
            }

            pathStack.removeLastOrNull()
        }

        private fun parentTag(): String? = pathStack.elementAtOrNull(pathStack.size - 2)
        private fun grandparentTag(): String? = pathStack.elementAtOrNull(pathStack.size - 3)
        private fun isInsidePluginDevice(): Boolean = pathStack.contains("PluginDevice")
        private fun currentTrackName(): String? = trackStack.lastOrNull()?.name
    }

    private fun defaultNameFor(fmt: PluginFormat): String = when (fmt) {
        PluginFormat.Vst3 -> "Unknown VST3"
        PluginFormat.Vst2 -> "Unknown VST"
        PluginFormat.Au -> "Unknown AU"
        PluginFormat.AbletonNative -> "Unknown Native"
        PluginFormat.Unknown -> "Unknown"
    }

    /** Live root-note encoding: 0=C ascending chromatic to 11=B. Modulo canonicalises out-of-range values. */
    private val pitchClasses = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    private fun deriveKeySignature(rootNote: Int?, scaleName: String?): String? {
        if (rootNote == null || scaleName.isNullOrBlank()) return null
        val pitch = pitchClasses[((rootNote % 12) + 12) % 12]
        val name = scaleName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return "$pitch $name"
    }

    private fun decodeTimeSignature(encoded: Int?): Pair<Int, Int>? {
        if (encoded == null || encoded < 0) return null
        val numerator = (encoded % 99) + 1
        val denomIndex = encoded / 99
        val denominator = denomTable.getOrNull(denomIndex) ?: return null
        return numerator to denominator
    }
}

private fun StartElement.attr(name: String): String? =
    getAttributeByName(QName(name))?.value
