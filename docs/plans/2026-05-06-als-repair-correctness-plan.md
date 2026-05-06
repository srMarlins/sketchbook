# .als Repair Correctness (Live 11/12) Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task.

**Goal:** Make Sketchbook's `.als` repair pipeline match Ableton's actual SampleRef contract for Live 11/12, so a repaired project is byte-correct against Live's load-time resolution chain.

**Architecture:** Extend the existing streaming StAX parser to extract the full SampleRef metadata Ableton keys on (`RelativePathType`, `OriginalFileSize`, `OriginalCrc`, `LastModDate`, plus presence of the `SourceContext.OriginalFileRef` sibling). Replace the rewriter's literal `Map<String,String>` API with an edit-list API that updates a primary `FileRef` and its `OriginalFileRef` sibling **atomically** within the same SampleRef. Add post-patch re-parse validation and a stale `.patcher-tmp` janitor to the disk patcher. No schema migration — repair re-parses the .als on demand to fetch the new fields, keeping the PR focused.

**Tech Stack:** Kotlin/JVM, StAX (`javax.xml.stream`, ships with JVM), `java.util.zip.GZIP{Input,Output}Stream`, JUnit, SQLDelight (no schema changes this PR). No new dependencies.

**Scope:**
- ✅ **Live 11 & 12** — flat `Path` / `RelativePath` value attributes, `OriginalCrc`/`OriginalFileSize` flat fields, `LastModDate` on `<SampleRef>`. This is the dominant case in the user's 1,628-project library.
- ⏭️ **Live 10 (and 9)** — deferred to a follow-up plan. Live ≤10 stores absolute paths as a binary `<Data>` blob (UTF-16-LE on Windows; HFS variable-length struct with separate volume name on macOS) and splits relative paths into `<RelativePathElement Id="n" Dir="..."/>` chains, with a `<SearchHint>` block. The codec boundary in this plan is clean enough to add Live ≤10 additively.

**Reference research:** see prior conversation synthesis. Key findings: (1) most SampleRefs have **two** `<FileRef>` blocks (primary + `SourceContext.SourceContext.OriginalFileRef.FileRef`) and patching only the primary causes silent reverts; (2) `OriginalCrc` is a 16-bit value Ableton's algorithm has not been publicly cracked — treat as opaque; safest is to set it to `0` on path changes so Live recomputes on next save; (3) Live's filename-first matching means a stale `OriginalFileSize` won't cause load failure today but is a future regression vector.

**Out of scope (follow-up plans):**
- Catalog schema migration to persist new fields (defer until matcher changes need them indexed).
- Match-priority chain improvements (`LivePackId` skip, project-relative anchor walk, drive-letter map for Mac→Windows).
- Sample index rebuild keyed on `(size, prefix_hash)`.
- Cracking Ableton's CRC algorithm with `delsum`/`crcbeagle`.

---

## Task 1: Extend SampleRef data model

**Files:**
- Modify: `shared/core/src/commonMain/kotlin/com/sketchbook/core/Project.kt:74-76`

**Step 1: Write the failing test**

Add to `shared/core/src/commonTest/kotlin/com/sketchbook/core/SampleRefTest.kt` (create if missing):

```kotlin
package com.sketchbook.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SampleRefTest {
    @Test fun defaultsAreNullExceptRawPath() {
        val s = SampleRef(rawPath = "x.wav")
        assertEquals("x.wav", s.rawPath)
        assertNull(s.relativePathType)
        assertNull(s.originalFileSize)
        assertNull(s.originalCrc)
        assertNull(s.lastModDate)
        assertEquals(false, s.hasOriginalFileRefSibling)
    }
}
```

**Step 2: Run, verify FAIL** (compile error: properties don't exist)

```bash
./gradlew :shared:core:jvmTest --tests "com.sketchbook.core.SampleRefTest"
```

**Step 3: Implement**

Replace `data class SampleRef` with:

```kotlin
/**
 * Sample reference recovered from a `.als` file. The [rawPath] is whatever Live wrote — relative,
 * absolute, Mac-style, etc. Resolution to a real file (and size/exists) happens later.
 *
 * Live 11/12 SampleRef metadata fields used during repair. All nullable because:
 *  - older Live (≤10) stores them differently or not at all,
 *  - the parser may encounter malformed/partial entries and we don't want to fail the whole scan.
 *
 * [hasOriginalFileRefSibling] reflects whether the parser saw a
 * `<SampleRef>/<SourceContext>/<SourceContext>/<OriginalFileRef>/<FileRef>` block alongside the
 * primary `<FileRef>`. Live re-derives paths from this sibling under some operations, so any
 * repair must rewrite both copies atomically — see AlsRewriter.
 */
data class SampleRef(
    val rawPath: String,
    val relativePathType: Int? = null,
    val originalFileSize: Long? = null,
    val originalCrc: Long? = null,
    val lastModDate: Long? = null,
    val hasOriginalFileRefSibling: Boolean = false,
)
```

**Step 4: Run, verify PASS**

```bash
./gradlew :shared:core:jvmTest --tests "com.sketchbook.core.SampleRefTest"
```

**Step 5: Commit**

```bash
git add shared/core/src/commonMain/kotlin/com/sketchbook/core/Project.kt shared/core/src/commonTest/kotlin/com/sketchbook/core/SampleRefTest.kt
git commit -m "feat(core): extend SampleRef with Live 11/12 metadata fields"
```

---

## Task 2: Parser extracts new fields and OriginalFileRef sibling

**Files:**
- Modify: `shared/parser-als/src/jvmMain/kotlin/com/sketchbook/als/AlsParser.kt:96-106` (PendingSample), `:233-248` (sample extraction), `:283-286` (SampleRef close)
- Test: `shared/parser-als/src/jvmTest/kotlin/com/sketchbook/als/AlsParserTest.kt` (extend)

**Step 1: Write the failing test**

Add a fixture and test. Create `shared/parser-als/src/jvmTest/resources/live12-sampleref.xml.gz` by hand-building the XML below and gzipping it (do this as a one-time helper inside the test using a `@BeforeAll` if a fixture file is awkward to track in git — but prefer dropping it as a resource).

XML to encode (matches abletoolz's documented Live 11+ schema):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Ableton Creator="Ableton Live 12.0.5">
 <LiveSet>
  <Tracks>
   <AudioTrack><Name><EffectiveName Value="A"/></Name>
    <DeviceChain><MainSequencer><Sample><ArrangerAutomation><Events><AudioClip>
     <SampleRef>
      <FileRef>
       <RelativePathType Value="3"/>
       <RelativePath Value="Samples/Imported/kick.wav"/>
       <Path Value="D:/Audio/Project/Samples/Imported/kick.wav"/>
       <Type Value="1"/>
       <LivePackName Value=""/><LivePackId Value=""/>
       <OriginalFileSize Value="58394528"/>
       <OriginalCrc Value="7866"/>
      </FileRef>
      <LastModDate Value="1694844696"/>
      <SourceContext><SourceContext>
       <OriginalFileRef>
        <FileRef>
         <RelativePathType Value="3"/>
         <RelativePath Value="Samples/Imported/kick.wav"/>
         <Path Value="D:/Audio/Project/Samples/Imported/kick.wav"/>
         <Type Value="1"/>
         <LivePackName Value=""/><LivePackId Value=""/>
         <OriginalFileSize Value="58394528"/>
         <OriginalCrc Value="7866"/>
        </FileRef>
       </OriginalFileRef>
      </SourceContext></SourceContext>
      <SampleUsageHint Value="0"/>
     </SampleRef>
    </AudioClip></Events></ArrangerAutomation></Sample></MainSequencer></DeviceChain>
   </AudioTrack>
  </Tracks>
 </LiveSet>
</Ableton>
```

Test:

```kotlin
@Test fun extractsLive12SampleRefMetadataAndOriginalFileRefSibling() {
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
```

**Step 2: Run, verify FAIL** (assertion fails — fields are null/false)

```bash
./gradlew :shared:parser-als:jvmTest --tests "com.sketchbook.als.AlsParserTest.extractsLive12SampleRefMetadataAndOriginalFileRefSibling"
```

**Step 3: Implement**

In `AlsParser.kt`, extend `PendingSample` and tracking. Replace the class:

```kotlin
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
```

In `handleStart`, replace the `pendingSample?.let { ... }` block with:

```kotlin
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
```

In `handleEnd`, before the existing `if (tag == "SampleRef")` block, add:

```kotlin
if (tag == "FileRef") {
    pendingSample?.let { if (parentTag() == "SampleRef") it.insidePrimaryFileRef = false }
}
```

And replace the SampleRef close to populate the new fields:

```kotlin
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
```

**Step 4: Run, verify PASS**

```bash
./gradlew :shared:parser-als:jvmTest --tests "com.sketchbook.als.AlsParserTest"
```

All existing parser tests must still pass.

**Step 5: Commit**

```bash
git add shared/parser-als/
git commit -m "feat(parser-als): extract Live 11/12 SampleRef metadata + OriginalFileRef sibling"
```

---

## Task 3: New AlsRewriter API — `rewriteSampleRefs(edits)`

**Files:**
- Modify: `shared/parser-als/src/jvmMain/kotlin/com/sketchbook/als/AlsRewriter.kt`
- Test: `shared/parser-als/src/jvmTest/kotlin/com/sketchbook/als/AlsRewriterTest.kt`

**Step 1: Write the failing test**

```kotlin
@Test fun rewriteSampleRefsUpdatesPrimaryAndOriginalFileRefSibling() {
    val gz = javaClass.getResourceAsStream("/live12-sampleref.xml.gz")!!.readBytes()
    val edit = SampleRefEdit(
        oldPath = "D:/Audio/Project/Samples/Imported/kick.wav",
        newPath = "E:/NewLocation/kick.wav",
        newRelativePath = "Samples/Imported/kick.wav",
        newRelativePathType = 1,
        newOriginalFileSize = 58394528L,
        newOriginalCrc = 0L,           // force Live to recompute
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
    val xml = java.util.zip.GZIPInputStream(out.inputStream()).readBytes().decodeToString()
    val pathOccurrences = Regex("""<Path Value="([^"]+)"/>""").findAll(xml).map { it.groupValues[1] }.toList()
    assertEquals(2, pathOccurrences.size)
    assertTrue(pathOccurrences.all { it == "E:/NewLocation/kick.wav" })
}

@Test fun rewriteSampleRefsIsIdempotentWhenOldPathDoesNotMatch() {
    val gz = javaClass.getResourceAsStream("/live12-sampleref.xml.gz")!!.readBytes()
    val edit = SampleRefEdit(oldPath = "no-such-path.wav", newPath = "x.wav")
    val out = AlsRewriter.rewriteSampleRefs(gz, listOf(edit))
    // gunzip both sides and compare — byte-equal
    val before = java.util.zip.GZIPInputStream(gz.inputStream()).readBytes()
    val after = java.util.zip.GZIPInputStream(out.inputStream()).readBytes()
    assertContentEquals(before, after)
}
```

**Step 2: Run, verify FAIL** (compile error: `SampleRefEdit` and `rewriteSampleRefs` don't exist)

```bash
./gradlew :shared:parser-als:jvmTest --tests "com.sketchbook.als.AlsRewriterTest"
```

**Step 3: Implement**

Add to `AlsRewriter.kt`:

```kotlin
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
```

Add a new top-level method on `AlsRewriter`:

```kotlin
/**
 * Streaming rewrite of one or more SampleRefs. Each [edit] is matched by the current primary
 * `<Path Value="..."/>`; non-matching SampleRefs flow through unchanged. The matching SampleRef's
 * primary FileRef and its OriginalFileRef sibling are both updated.
 *
 * Returns a freshly gzipped byte array. Idempotent when no edit's [SampleRefEdit.oldPath] matches.
 */
fun rewriteSampleRefs(gzipped: ByteArray, edits: List<SampleRefEdit>): ByteArray {
    if (edits.isEmpty()) return gzipped
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
```

Add the implementation. The trick: scan SampleRefs in document order. For each SampleRef, run a **two-pass** strategy implemented inline by buffering events for the duration of one SampleRef:

```kotlin
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
                    // Buffer now holds the entire <SampleRef>...</SampleRef>. Find primary Path
                    // (the <Path Value=…/> whose ancestor chain is SampleRef → FileRef, NOT
                    // SampleRef → SourceContext → … → OriginalFileRef → FileRef → Path).
                    val primaryPath = findPrimaryPath(buffer)
                    val edit = primaryPath?.let { byOldPath[it] }
                    val patched = if (edit != null) applyEdit(buffer, edit) else buffer
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

/** Returns the value of the `<Path Value="…"/>` whose grandparent is SampleRef. */
private fun findPrimaryPath(buf: List<BufferedEvent>): String? {
    val stack = ArrayDeque<String>()
    for (e in buf) {
        when (e.type) {
            XMLStreamConstants.START_ELEMENT -> {
                val name = e.name!!
                if (name == "Path" && stack.size >= 2 &&
                    stack.last() == "FileRef" && stack[stack.size - 2] == "SampleRef"
                ) {
                    return e.attrs.firstOrNull { it.first == "Value" }?.second
                }
                stack.addLast(name)
            }
            XMLStreamConstants.END_ELEMENT -> stack.removeLastOrNull()
        }
    }
    return null
}

/** Returns a new buffer with every Path/RelativePath/RelativePathType/OriginalFileSize/
 *  OriginalCrc inside any FileRef updated, plus LastModDate on the SampleRef itself. */
private fun applyEdit(buf: List<BufferedEvent>, edit: SampleRefEdit): List<BufferedEvent> {
    val stack = ArrayDeque<String>()
    return buf.map { e ->
        when (e.type) {
            XMLStreamConstants.START_ELEMENT -> {
                val name = e.name!!
                val parent = stack.lastOrNull()
                val newAttrs = when {
                    name == "Path" && parent == "FileRef" ->
                        e.attrs.map { if (it.first == "Value") "Value" to edit.newPath else it }
                    name == "RelativePath" && parent == "FileRef" && edit.newRelativePath != null ->
                        e.attrs.map { if (it.first == "Value") "Value" to edit.newRelativePath else it }
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
```

Memory note: the per-SampleRef buffer is bounded by the size of one SampleRef block (~hundreds of bytes), not the whole project. Heap stays flat.

**Step 4: Run, verify PASS**

```bash
./gradlew :shared:parser-als:jvmTest
```

**Step 5: Commit**

```bash
git add shared/parser-als/
git commit -m "feat(parser-als): rewriteSampleRefs API patches primary FileRef and OriginalFileRef sibling atomically"
```

---

## Task 4: Migrate existing rewriter callers to the new API (keep old API working)

**Files:**
- Modify: `shared/parser-als/src/jvmMain/kotlin/com/sketchbook/als/AlsRewriter.kt:42-65`

**Decision:** Keep the existing `rewriteSamplePaths(mapping: Map<String, String>)` as a thin wrapper that forwards to `rewriteSampleRefs` so we don't break the Mac-path repair path in this PR.

**Step 1: Write the failing test (regression)**

Existing `AlsRewriterTest` Mac-path tests must still pass. Add one explicit guard:

```kotlin
@Test fun rewriteSamplePathsStillWorksAsThinWrapper() {
    val gz = javaClass.getResourceAsStream("/live12-sampleref.xml.gz")!!.readBytes()
    val out = AlsRewriter.rewriteSamplePaths(
        gz, mapOf("D:/Audio/Project/Samples/Imported/kick.wav" to "E:/x/kick.wav"),
    )
    val md = AlsParser.parse(out.inputStream())
    assertEquals("E:/x/kick.wav", md.sampleRefs[0].rawPath)
}
```

**Step 2: Run, verify the test passes against current `rewriteSamplePaths`** (it should — that path already works for primary `<Path>` rewrites). The point of the test is to lock the contract before refactoring.

**Step 3: Refactor `rewriteSamplePaths` to delegate**

Replace the body of `rewriteSamplePaths` with:

```kotlin
fun rewriteSamplePaths(gzipped: ByteArray, mapping: Map<String, String>): ByteArray {
    if (mapping.isEmpty()) return gzipped
    val edits = mapping.map { (old, new) -> SampleRefEdit(oldPath = old, newPath = new) }
    return rewriteSampleRefs(gzipped, edits)
}
```

**Step 4: Run all parser-als tests**

```bash
./gradlew :shared:parser-als:jvmTest
```

All Mac-path tests + new tests must pass. **Notable behavior change:** the old API now also patches the OriginalFileRef sibling. That is the intended fix; verify no test expected the sibling to remain stale (none should — that would have been a bug-in-test).

**Step 5: Commit**

```bash
git add shared/parser-als/
git commit -m "refactor(parser-als): rewriteSamplePaths delegates to rewriteSampleRefs"
```

---

## Task 5: AlsPatcher stale `.patcher-tmp` janitor

**Files:**
- Modify: `shared/sync-io/src/jvmMain/kotlin/com/sketchbook/syncio/AlsPatcher.kt:31-43`
- Test: `shared/sync-io/src/jvmTest/kotlin/com/sketchbook/syncio/AlsPatcherTest.kt`

**Step 1: Write the failing test**

```kotlin
@Test fun patchCleansUpStaleTempFromPriorCrash(@TempDir dir: Path) {
    val als = dir.resolve("a.als")
    Files.write(als, fakeGzippedAls())                  // helper that produces a minimal valid .als
    val staleTmp = dir.resolve("a.als.patcher-tmp")
    Files.write(staleTmp, byteArrayOf(0x00))            // crash leftover
    val patcher = AlsPatcher(busyDetector = { false })
    val outcome = patcher.patch(als, mapping = mapOf("nope" to "nope2"))
    // No-op mapping → NoChange — but the stale tmp must be gone after the call.
    assertFalse(Files.exists(staleTmp))
    assertEquals(AlsPatcher.Outcome.NoChange, outcome)
}
```

**Step 2: Run, verify FAIL** (`staleTmp` still exists)

**Step 3: Implement**

In `AlsPatcher.patch`, before computing `rewritten`, add:

```kotlin
// Janitor: drop any stale temp left by a prior crashed run. CREATE_NEW would otherwise throw.
val temp = als.resolveSibling("${als.fileName}.patcher-tmp")
Files.deleteIfExists(temp)
```

…and remove the inline `val temp = …` declaration further down (use the variable already declared). Apply the same change to `restore`.

**Step 4: Run, verify PASS**

```bash
./gradlew :shared:sync-io:jvmTest --tests "com.sketchbook.syncio.AlsPatcherTest"
```

**Step 5: Commit**

```bash
git add shared/sync-io/
git commit -m "fix(sync-io): AlsPatcher cleans up stale .patcher-tmp from crashed prior runs"
```

---

## Task 6: AlsPatcher post-patch re-parse validation

**Files:**
- Modify: `shared/sync-io/src/jvmMain/kotlin/com/sketchbook/syncio/AlsPatcher.kt:31-43`
- Test: `shared/sync-io/src/jvmTest/kotlin/com/sketchbook/syncio/AlsPatcherTest.kt`

**Step 1: Write the failing test**

Inject a custom `rewriter` so we can simulate corruption:

```kotlin
@Test fun patchRefusesToInstallCorruptedOutput(@TempDir dir: Path) {
    val als = dir.resolve("a.als")
    val originalBytes = fakeGzippedAls()
    Files.write(als, originalBytes)
    val patcher = AlsPatcher(
        busyDetector = { false },
        rewriter = { _, _ -> byteArrayOf(0x1F, 0x8B, 0xFF.toByte()) },  // truncated gzip
    )
    val outcome = patcher.patch(als, mapOf("x" to "y"))
    assertTrue(outcome is AlsPatcher.Outcome.Failed)
    assertContentEquals(originalBytes, Files.readAllBytes(als))         // file unchanged
}
```

**Step 2: Run, verify FAIL** (compile error: `rewriter` parameter doesn't exist; or test asserts that file was overwritten)

**Step 3: Implement**

Inject the rewriter for testability and add validation. Update `AlsPatcher`:

```kotlin
class AlsPatcher(
    private val busyDetector: (Path) -> Boolean = ::isFileLockedByAnotherProcess,
    private val rewriter: (ByteArray, Map<String, String>) -> ByteArray = AlsRewriter::rewriteSamplePaths,
) : AlsPatchService {
    // ... (existing Outcome sealed interface unchanged)

    fun patch(als: Path, mapping: Map<String, String>): Outcome {
        if (mapping.isEmpty()) return Outcome.NoChange
        if (busyDetector(als)) return Outcome.SkippedBusy
        return runCatching {
            val temp = als.resolveSibling("${als.fileName}.patcher-tmp")
            Files.deleteIfExists(temp)
            val original = Files.readAllBytes(als)
            val rewritten = rewriter(original, mapping)
            if (rewritten.contentEquals(original)) return Outcome.NoChange
            // Validation: re-parse the rewritten bytes end-to-end. AlsParser uses StAX; bad gzip
            // or malformed XML throws here, before we touch the original on disk.
            AlsParser.parse(rewritten.inputStream())
            Files.write(temp, rewritten, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            Files.move(temp, als, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Outcome.Patched as Outcome
        }.getOrElse {
            // Make sure no temp leftover survives a validation/move failure.
            runCatching { Files.deleteIfExists(als.resolveSibling("${als.fileName}.patcher-tmp")) }
            Outcome.Failed(it)
        }
    }
    // ... apply matching cleanup-on-failure to restore() ...
}
```

`fakeGzippedAls()` helper — create as a util in test code; smallest valid `.als` is a gzipped XML stub like the live12 fixture, simplified.

**Step 4: Run, verify PASS**

```bash
./gradlew :shared:sync-io:jvmTest
```

**Step 5: Commit**

```bash
git add shared/sync-io/
git commit -m "feat(sync-io): AlsPatcher re-parses patched bytes before atomic swap"
```

---

## Task 7: Repair pipeline uses new fields

**Files:**
- Modify: `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/SqlRepairRepository.kt:146-218` (Mac-path apply path is unchanged; missing-sample apply needs to use `SampleRefEdit`)

**Decision:** This PR focuses on rewriter correctness. The SqlRepairRepository's missing-sample-match path currently passes a `Map<String,String>` mapping. Because Task 4 made that API a thin wrapper, *the repository continues to work without code changes* and now correctly patches the `OriginalFileRef` sibling.

For the next PR (deferred): plumb `SampleRefEdit` through `AlsPatchService` so the repair flow can supply matched-candidate `OriginalFileSize`, set `OriginalCrc=0`, and update `LastModDate`. That requires a service-interface change and a new SQLDelight migration to persist the candidate's stat info.

**Step 1: Add an integration test that proves the existing repair path now also rewrites the sibling**

Extend `SqlRepairRepositoryAlsRewriteTest.kt`:

```kotlin
@Test fun missingSampleMatchPatchesBothFileRefAndOriginalFileRef() {
    // Arrange: repository state with one missing sample, one candidate. Use the live12 fixture.
    // Act: applyMissingSampleMatch(...)
    // Assert: re-parse the patched .als; both Path occurrences point at the candidate.
}
```

(Body follows existing test patterns in that file — copy the setup from the nearest existing test and add the assertion that `<Path Value="…"/>` count is 2 and both equal the candidate path.)

**Step 2: Run, verify PASS** (should already pass since Task 4 made `rewriteSamplePaths` patch the sibling)

```bash
./gradlew :shared:repository:jvmTest --tests "com.sketchbook.repo.impl.SqlRepairRepositoryAlsRewriteTest"
```

If this fails, the regression is in Task 4 — debug there, not here.

**Step 3: Commit**

```bash
git add shared/repository/
git commit -m "test(repository): lock in OriginalFileRef sibling rewrite via the existing repair path"
```

---

## Task 8: Smoke test on a real project

**Step 1:** Pick one .als from the user's library that has known missing samples. Copy it to a scratch directory.

**Step 2:** Run Sketchbook against the scratch project. Trigger Repair Paths. Verify no crash.

**Step 3:** Open the patched .als in Ableton Live 12. Verify samples load without the missing-files dialog.

**Step 4:** Trigger Undo in Sketchbook. Verify the .als bytes match the snapshot. Re-open in Live to confirm the original missing-files state.

**Step 5:** Document outcome in a short comment on the PR.

No commit needed for the smoke test itself; if anything fails, file a fix as a separate task.

---

## Task 9: Verify build + open PR

**Step 1: Run the full build**

```bash
./gradlew build
```

Per memory `feedback_local_build_authority`: local build is the merge gate. If it passes, proceed.

**Step 2: Open PR**

Use the `commit-commands:commit-push-pr` skill or run:

```bash
git push -u origin <branch>
gh pr create --title "feat(als): correct SampleRef rewrite for Live 11/12" --body "$(cat <<'EOF'
## Summary
- AlsParser extracts Live 11/12 SampleRef metadata: RelativePathType, OriginalFileSize, OriginalCrc, LastModDate, plus presence of OriginalFileRef sibling
- AlsRewriter gains rewriteSampleRefs(edits) API that patches BOTH the primary FileRef and the OriginalFileRef sibling atomically — the existing rewriteSamplePaths is now a thin wrapper
- AlsPatcher: stale .patcher-tmp janitor + post-patch re-parse validation before atomic swap

## Why
Ableton resolves missing samples filename-first and aggressively. Most SampleRefs have *two* FileRef blocks; patching only the primary causes silent reverts. Stale OriginalFileSize/CRC are a future regression vector once Live tightens validation.

## Scope
Live 11 & 12 only. Live ≤10 (binary `<Data>` blob, `<RelativePathElement>` chains) is a follow-up plan.

## Test plan
- [ ] ./gradlew build passes locally
- [ ] AlsParserTest covers Live 12 fixture with OriginalFileRef sibling
- [ ] AlsRewriterTest covers idempotency + sibling patch
- [ ] AlsPatcherTest covers stale-tmp cleanup + corruption rejection
- [ ] Smoke test on one real project from the library
EOF
)"
```

Per memory `feedback_local_build_authority`: if local build passed, merge with `--admin`; do not wait on CI.

---

## Follow-up plans (DO NOT DO IN THIS PR)

1. **Live 10 codec** — decode/encode the binary `<Data>` blob (Windows UTF-16-LE; macOS HFS struct with separate volume name) and walk `<RelativePathElement Id="n" Dir="..."/>` chains.
2. **Plumb `SampleRefEdit` through `AlsPatchService`** so repair can pass matched-candidate stats (size, mtime, CRC=0) end-to-end.
3. **Catalog migration** — add `relative_path_type`, `original_file_size`, `original_crc`, `last_mod_date`, `live_pack_id` columns to `project_samples`, plus backfill on next scan.
4. **Match priority chain** — `LivePackId` skip (factory content), project-relative anchor walk (find `Ableton Project Info` parent), drive-letter map for `/Volumes/<name>` → `<letter>:`.
5. **Sample index rebuild** keyed `(size_bytes, sha1_first_1MiB)` — promote existing B2/R2 content hashes where available.
6. **Optional moonshot:** crack Ableton's CRC-16 with `delsum` against a captured corpus of `(file bytes, OriginalCrc)` pairs from the 1,628-project library. Deterministic disambiguation is a real moat.
