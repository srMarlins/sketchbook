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
     */
    fun rewriteSamplePaths(gzipped: ByteArray, mapping: Map<String, String>): ByteArray {
        if (mapping.isEmpty()) return gzipped
        val outBuf = ByteArrayOutputStream(gzipped.size + 1024)
        GZIPOutputStream(outBuf).use { gzOut ->
            GZIPInputStream(ByteArrayInputStream(gzipped)).use { gzIn ->
                val reader = INPUT_FACTORY.createXMLStreamReader(gzIn, "UTF-8")
                val writer = OUTPUT_FACTORY.createXMLStreamWriter(gzOut, "UTF-8")
                try {
                    identityWithRewrite(reader, writer, mapping)
                    writer.flush()
                } catch (t: Throwable) {
                    // On failure: do NOT flush partial output. Close reader/writer best-effort
                    // (any close() exceptions are recorded as suppressed) and rethrow so the
                    // caller never sees a half-written ByteArray + gzip trailer.
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

    private fun identityWithRewrite(
        reader: XMLStreamReader,
        writer: XMLStreamWriter,
        mapping: Map<String, String>,
    ) {
        var inSampleRef = 0
        // First event when XMLStreamReader is constructed is START_DOCUMENT — surface it explicitly
        // so we emit `<?xml version="1.0" encoding="UTF-8"?>` before walking.
        if (reader.eventType == XMLStreamConstants.START_DOCUMENT) {
            writer.writeStartDocument(reader.encoding ?: "UTF-8", reader.version ?: "1.0")
        }
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    val name = reader.localName
                    if (name == "SampleRef") inSampleRef++
                    writer.writeStartElement(name)
                    for (i in 0 until reader.attributeCount) {
                        val attrLocal = reader.getAttributeLocalName(i)
                        var attrValue = reader.getAttributeValue(i)
                        if (inSampleRef > 0 &&
                            (name == "Path" || name == "RelativePath") &&
                            attrLocal == "Value"
                        ) {
                            mapping[attrValue]?.let { attrValue = it }
                        }
                        writer.writeAttribute(attrLocal, attrValue)
                    }
                }
                XMLStreamConstants.END_ELEMENT -> {
                    if (reader.localName == "SampleRef") inSampleRef--
                    writer.writeEndElement()
                }
                XMLStreamConstants.CHARACTERS -> writer.writeCharacters(reader.text)
                XMLStreamConstants.CDATA -> writer.writeCData(reader.text)
                XMLStreamConstants.COMMENT -> writer.writeComment(reader.text)
                XMLStreamConstants.PROCESSING_INSTRUCTION ->
                    writer.writeProcessingInstruction(reader.piTarget, reader.piData ?: "")
                XMLStreamConstants.SPACE -> writer.writeCharacters(reader.text)
                XMLStreamConstants.END_DOCUMENT -> writer.writeEndDocument()
                else -> { /* DTD, ENTITY_REFERENCE, etc. — .als never uses these */ }
            }
        }
    }
}
