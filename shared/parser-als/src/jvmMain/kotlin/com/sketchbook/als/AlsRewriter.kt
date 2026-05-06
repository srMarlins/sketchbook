package com.sketchbook.als

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader
import javax.xml.stream.XMLStreamWriter

/**
 * One pending SampleRef edit. Identifies the SampleRef by its current primary `<Path Value="..."/>`
 * (the one whose grandparent is `<SampleRef>`). All `new*` fields are optional; null means leave
 * the corresponding attribute as-is.
 *
 * **Both the primary FileRef and its `SourceContext/SourceContext/OriginalFileRef/FileRef`
 * sibling within the same SampleRef are updated.** Live re-derives paths from the sibling under
 * some operations; patching only the primary causes silent reverts.
 *
 * **CRC handling:** the Ableton CRC algorithm is unsolved publicly. The recommended pattern is
 * to pass `newOriginalCrc = 0L` whenever the path changes, which forces Live to recompute on its
 * next save. Preserve the existing CRC only when you are confident the bytes are identical.
 */
data class SampleRefEdit(
    val oldPath: String,
    val newPath: String,
    val newRelativePath: String? = null,
    val newRelativePathType: Int? = null,
    val newOriginalFileSize: Long? = null,
    val newOriginalCrc: Long? = null,
    val newLastModDate: Long? = null,
)

private val INPUT_FACTORY: XMLInputFactory = XMLInputFactory.newFactory().apply {
    // Coalesce adjacent text events so we can echo CHARACTERS verbatim.
    setProperty(XMLInputFactory.IS_COALESCING, true)
    setProperty(XMLInputFactory.SUPPORT_DTD, false)
    setProperty("javax.xml.stream.isSupportingExternalEntities", false)
}

private val OUTPUT_FACTORY: XMLOutputFactory = XMLOutputFactory.newFactory()

/**
 * Pure transform that rewrites `<SampleRef>/<FileRef>/<Path Value="..."/>` (and `RelativePath`)
 * attribute values inside a gzipped `.als` document, preserving everything else byte-for-shape
 * (StAX identity copy — encoding/version/comments/PIs flow through; whitespace text nodes do too
 * via coalesced CHARACTERS events).
 *
 * **No DOM is built.** Both read and write streams are bounded by StAX events, mirroring the
 * memory contract documented on [AlsParser] — heap stays small even for 500 MB+ projects.
 *
 * **No new dependencies:** only `javax.xml.stream` (ships with the JVM) and `java.util.zip`.
 *
 * Mapping is a literal `oldValue -> newValue` lookup against the attribute string. The caller
 * decides what counts as a Mac-path repair, a missing-sample relink, etc.; this layer is dumb.
 */
object AlsRewriter {

    /**
     * Rewrites SampleRef path attributes in [gzipped] using [mapping] (literal source -> target).
     * Returns a freshly gzipped byte array. If [mapping] is empty the input is returned as-is.
     *
     * **Thin wrapper** over [rewriteSampleRefs] — each mapping entry becomes a [SampleRefEdit]
     * matched on either the primary `<Path>` or the primary `<RelativePath>` attribute. When the
     * SampleRef matches, both the primary FileRef and its `OriginalFileRef` sibling are updated
     * atomically (sibling-stale-revert is the bug this fixes).
     */
    fun rewriteSamplePaths(gzipped: ByteArray, mapping: Map<String, String>): ByteArray {
        if (mapping.isEmpty()) return gzipped
        val edits = mapping.map { (old, new) -> SampleRefEdit(oldPath = old, newPath = new) }
        return rewriteSampleRefs(gzipped, edits)
    }

    /**
     * Streaming rewrite of one or more SampleRefs. Each [edit] is matched by the current primary
     * `<Path Value="..."/>`; non-matching SampleRefs flow through unchanged. The matching SampleRef's
     * primary FileRef and its OriginalFileRef sibling are both updated.
     *
     * Returns a freshly gzipped byte array. Idempotent when no edit's [SampleRefEdit.oldPath] matches.
     */
    fun rewriteSampleRefs(gzipped: ByteArray, edits: List<SampleRefEdit>): ByteArray {
        if (edits.isEmpty()) return gzipped
        // Pre-scan: skip the rewrite entirely (return input unchanged) if no SampleRef's primary
        // path matches any edit's oldPath. Keeps the no-op case byte-identical and gives callers
        // an idempotency guarantee they can use without round-tripping through the gzip pipe.
        val oldPaths: Set<String> = edits.mapTo(HashSet()) { it.oldPath }
        if (!hasMatchingSampleRef(gzipped, oldPaths)) return gzipped
        val outBuf = ByteArrayOutputStream(gzipped.size + 1024)
        GZIPOutputStream(outBuf).use { gzOut ->
            GZIPInputStream(ByteArrayInputStream(gzipped)).use { gzIn ->
                val reader = INPUT_FACTORY.createXMLStreamReader(gzIn, "UTF-8")
                val writer = OUTPUT_FACTORY.createXMLStreamWriter(gzOut, "UTF-8")
                try {
                    identityWithSampleRefEdits(reader, writer, edits)
                    writer.flush()
                } catch (t: Throwable) {
                    runCatching { writer.close() }.exceptionOrNull()?.let(t::addSuppressed)
                    runCatching { reader.close() }.exceptionOrNull()?.let(t::addSuppressed)
                    throw t
                }
                writer.close()
                reader.close()
            }
        }
        return outBuf.toByteArray()
    }

    /**
     * Streaming pre-scan: returns true iff some SampleRef's primary `<Path Value="…"/>` matches
     * one of [oldPaths]. Bounded heap — only the StAX cursor; no DOM, no full-document buffer.
     */
    private fun hasMatchingSampleRef(gzipped: ByteArray, oldPaths: Set<String>): Boolean {
        if (oldPaths.isEmpty()) return false
        GZIPInputStream(ByteArrayInputStream(gzipped)).use { gzIn ->
            val reader = INPUT_FACTORY.createXMLStreamReader(gzIn, "UTF-8")
            try {
                val stack = ArrayDeque<String>()
                while (reader.hasNext()) {
                    when (reader.next()) {
                        XMLStreamConstants.START_ELEMENT -> {
                            val name = reader.localName
                            // Primary Path or RelativePath: SampleRef → FileRef → (Path|RelativePath)
                            if ((name == "Path" || name == "RelativePath") && stack.size >= 2 &&
                                stack.last() == "FileRef" && stack[stack.size - 2] == "SampleRef"
                            ) {
                                for (i in 0 until reader.attributeCount) {
                                    if (reader.getAttributeLocalName(i) == "Value" &&
                                        reader.getAttributeValue(i) in oldPaths
                                    ) return true
                                }
                            }
                            stack.addLast(name)
                        }
                        XMLStreamConstants.END_ELEMENT -> stack.removeLastOrNull()
                    }
                }
            } finally {
                reader.close()
            }
        }
        return false
    }

    private data class BufferedEvent(
        val type: Int,
        val name: String?,
        val attrs: List<Pair<String, String>>,
        val text: String?,
        val piTarget: String?,
        val piData: String?,
        val docEncoding: String?,
        val docVersion: String?,
    )

    private fun identityWithSampleRefEdits(
        reader: XMLStreamReader,
        writer: XMLStreamWriter,
        edits: List<SampleRefEdit>,
    ) {
        val byOldPath: Map<String, SampleRefEdit> = edits.associateBy { it.oldPath }
        if (reader.eventType == XMLStreamConstants.START_DOCUMENT) {
            writer.writeStartDocument(reader.encoding ?: "UTF-8", reader.version ?: "1.0")
        }
        var depthInsideSampleRef = 0
        val buffer = ArrayList<BufferedEvent>(64)

        fun captureCurrent(): BufferedEvent =
            when (reader.eventType) {
                XMLStreamConstants.START_ELEMENT -> BufferedEvent(
                    type = XMLStreamConstants.START_ELEMENT,
                    name = reader.localName,
                    attrs = (0 until reader.attributeCount).map {
                        reader.getAttributeLocalName(it) to reader.getAttributeValue(it)
                    },
                    text = null, piTarget = null, piData = null, docEncoding = null, docVersion = null,
                )
                XMLStreamConstants.END_ELEMENT -> BufferedEvent(
                    XMLStreamConstants.END_ELEMENT, reader.localName, emptyList(),
                    null, null, null, null, null,
                )
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.SPACE -> BufferedEvent(
                    reader.eventType, null, emptyList(), reader.text, null, null, null, null,
                )
                XMLStreamConstants.CDATA -> BufferedEvent(
                    XMLStreamConstants.CDATA, null, emptyList(), reader.text, null, null, null, null,
                )
                XMLStreamConstants.COMMENT -> BufferedEvent(
                    XMLStreamConstants.COMMENT, null, emptyList(), reader.text, null, null, null, null,
                )
                XMLStreamConstants.PROCESSING_INSTRUCTION -> BufferedEvent(
                    XMLStreamConstants.PROCESSING_INSTRUCTION, null, emptyList(), null,
                    reader.piTarget, reader.piData, null, null,
                )
                else -> BufferedEvent(reader.eventType, null, emptyList(), null, null, null, null, null)
            }

        fun emit(e: BufferedEvent) {
            when (e.type) {
                XMLStreamConstants.START_ELEMENT -> {
                    writer.writeStartElement(e.name)
                    for ((k, v) in e.attrs) writer.writeAttribute(k, v)
                }
                XMLStreamConstants.END_ELEMENT -> writer.writeEndElement()
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.SPACE -> writer.writeCharacters(e.text!!)
                XMLStreamConstants.CDATA -> writer.writeCData(e.text!!)
                XMLStreamConstants.COMMENT -> writer.writeComment(e.text!!)
                XMLStreamConstants.PROCESSING_INSTRUCTION ->
                    writer.writeProcessingInstruction(e.piTarget!!, e.piData ?: "")
                XMLStreamConstants.END_DOCUMENT -> writer.writeEndDocument()
            }
        }

        while (reader.hasNext()) {
            val type = reader.next()
            if (type == XMLStreamConstants.START_ELEMENT && reader.localName == "SampleRef") {
                depthInsideSampleRef = 1
                buffer.clear()
                buffer += captureCurrent()
                continue
            }
            if (depthInsideSampleRef > 0) {
                buffer += captureCurrent()
                if (type == XMLStreamConstants.START_ELEMENT) depthInsideSampleRef++
                if (type == XMLStreamConstants.END_ELEMENT) {
                    depthInsideSampleRef--
                    if (depthInsideSampleRef == 0) {
                        // Buffer holds the entire <SampleRef>...</SampleRef>. Match by either
                        // the primary `<Path>` or the primary `<RelativePath>` (the ones whose
                        // grandparent is SampleRef — NOT the OriginalFileRef sibling). Path takes
                        // precedence; RelativePath is the fallback so callers that pass a relative
                        // mapping (e.g. legacy Mac-path repair) still match.
                        val (primaryPath, primaryRelative) = findPrimaryPathAndRelative(buffer)
                        val edit = primaryPath?.let { byOldPath[it] }
                            ?: primaryRelative?.let { byOldPath[it] }
                        val patched = if (edit != null) {
                            applyEdit(buffer, edit, primaryPath, primaryRelative)
                        } else buffer
                        patched.forEach(::emit)
                        buffer.clear()
                    }
                }
                continue
            }
            // Outside any SampleRef — identity pass-through.
            when (type) {
                XMLStreamConstants.START_ELEMENT, XMLStreamConstants.END_ELEMENT,
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA,
                XMLStreamConstants.COMMENT, XMLStreamConstants.PROCESSING_INSTRUCTION,
                XMLStreamConstants.SPACE, XMLStreamConstants.END_DOCUMENT -> emit(captureCurrent())
            }
        }
    }

    /**
     * Returns `(primaryPath, primaryRelativePath)` from the buffered SampleRef events. Each is the
     * `Value` attribute of the `<Path>` / `<RelativePath>` whose direct parent is `<FileRef>` and
     * whose grandparent is `<SampleRef>` (the primary FileRef — NOT the OriginalFileRef sibling).
     */
    private fun findPrimaryPathAndRelative(buf: List<BufferedEvent>): Pair<String?, String?> {
        val stack = ArrayDeque<String>()
        var path: String? = null
        var relative: String? = null
        for (e in buf) {
            when (e.type) {
                XMLStreamConstants.START_ELEMENT -> {
                    val name = e.name!!
                    if (stack.size >= 2 && stack.last() == "FileRef" && stack[stack.size - 2] == "SampleRef") {
                        if (name == "Path" && path == null) {
                            path = e.attrs.firstOrNull { it.first == "Value" }?.second
                        } else if (name == "RelativePath" && relative == null) {
                            relative = e.attrs.firstOrNull { it.first == "Value" }?.second
                        }
                    }
                    stack.addLast(name)
                }
                XMLStreamConstants.END_ELEMENT -> stack.removeLastOrNull()
            }
        }
        return path to relative
    }

    /**
     * Returns a new buffer with the SampleRef's primary FileRef and its OriginalFileRef sibling
     * updated according to [edit]:
     *  - `Path` is rewritten to `edit.newPath` only when the SampleRef matched on Path
     *    (i.e. `edit.oldPath == primaryPath`); otherwise Path is left as-is so a
     *    RelativePath-only mapping doesn't clobber the absolute path.
     *  - `RelativePath` is rewritten to `edit.newRelativePath` if non-null, else to
     *    `edit.newPath` only when the SampleRef matched on RelativePath
     *    (`edit.oldPath == primaryRelativePath`).
     *  - `RelativePathType`/`OriginalFileSize`/`OriginalCrc` inside any `FileRef` are updated
     *    when the corresponding `new*` field is non-null.
     *  - `LastModDate` directly under `SampleRef` is updated when `newLastModDate` is non-null.
     */
    private fun applyEdit(
        buf: List<BufferedEvent>,
        edit: SampleRefEdit,
        primaryPath: String?,
        primaryRelative: String?,
    ): List<BufferedEvent> {
        val matchedOnPath = primaryPath != null && primaryPath == edit.oldPath
        val matchedOnRelative = !matchedOnPath && primaryRelative != null && primaryRelative == edit.oldPath
        val newRelativeToWrite: String? = edit.newRelativePath
            ?: if (matchedOnRelative) edit.newPath else null
        val stack = ArrayDeque<String>()
        return buf.map { e ->
            when (e.type) {
                XMLStreamConstants.START_ELEMENT -> {
                    val name = e.name!!
                    val parent = stack.lastOrNull()
                    val newAttrs = when {
                        name == "Path" && parent == "FileRef" && matchedOnPath ->
                            e.attrs.map { if (it.first == "Value") "Value" to edit.newPath else it }
                        name == "RelativePath" && parent == "FileRef" && newRelativeToWrite != null ->
                            e.attrs.map { if (it.first == "Value") "Value" to newRelativeToWrite else it }
                        name == "RelativePathType" && parent == "FileRef" && edit.newRelativePathType != null ->
                            e.attrs.map { if (it.first == "Value") "Value" to edit.newRelativePathType.toString() else it }
                        name == "OriginalFileSize" && parent == "FileRef" && edit.newOriginalFileSize != null ->
                            e.attrs.map { if (it.first == "Value") "Value" to edit.newOriginalFileSize.toString() else it }
                        name == "OriginalCrc" && parent == "FileRef" && edit.newOriginalCrc != null ->
                            e.attrs.map { if (it.first == "Value") "Value" to edit.newOriginalCrc.toString() else it }
                        name == "LastModDate" && parent == "SampleRef" && edit.newLastModDate != null ->
                            e.attrs.map { if (it.first == "Value") "Value" to edit.newLastModDate.toString() else it }
                        else -> e.attrs
                    }
                    stack.addLast(name)
                    e.copy(attrs = newAttrs)
                }
                XMLStreamConstants.END_ELEMENT -> {
                    stack.removeLastOrNull()
                    e
                }
                else -> e
            }
        }
    }

}
