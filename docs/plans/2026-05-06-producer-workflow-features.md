# Sketchbook Producer-Workflow Features Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task.

**Goal:** Five post-1.0 features picked from the 2026-05-06 feature audit that are *not* covered by the post-review plan (PR-L through PR-V) — closing the "missing-sample queue is theatre" gap, plus four layered Browse / Detail / Timeline additions that leverage data already in the catalog.

**Architecture:** Five sequential PRs (`PR-W` through `PR-BB`), each independently shippable. PR-W is a 1.0-credibility fix and should be considered for promotion *into* PR-L (see header note in §PR-W). The rest are cosmetic + small-data-model additions that follow the existing chip / popover / card layering pattern.

**Tech Stack:** Kotlin 2.3 + Compose Multiplatform 1.11, SQLDelight 2.3 + FTS5, Metro DI, Ktor 3.2 CIO, kotlinx.io, kotlinx.serialization, kotlin.test + Kotest + Turbine, StAX (`javax.xml.stream`) for .als read/write. **No new dependencies.**

**Predecessors:**
- [`2026-05-06-post-review-plan.md`](2026-05-06-post-review-plan.md) — PRs L–V (1.0 gate + 7 features)
- [`2026-05-06-feature-complete-design.md`](2026-05-06-feature-complete-design.md) — what 1.0 promised
- [`2026-05-05-sync-versioning-design.md`](2026-05-05-sync-versioning-design.md) — sync architecture

**PR sequence:**
| PR  | Theme                                | Severity        | Depends on |
|-----|--------------------------------------|-----------------|------------|
| W   | .als path rewriter (relink + macpath)| **1.0 blocker** (recommend folding into PR-L) | PR-L L7 (Undo) — but ideally lands earlier |
| X   | Key extract + Key/Tempo filter chips | feature         | none       |
| Z   | Snapshot label + quick-capture hotkey| feature         | PR-O O3 (host_name on snapshots), PR-O O4 (CoalesceJob) |
| AA  | Always-on plugin pivot               | feature         | PR-S pattern (mirror) |
| BB  | Library health score chip            | feature         | PR-T (`is_installed` on plugins) |

---

## PR-W: .als path rewriter (close the half-finished surface)

**Header note for reviewer:** PR-L L7 ships a 5-second Undo on the Needs-Attention "Pick" action and PR-O O6 journals it. Neither rewrites the `.als`. Until this PR ships, every "fixed" missing sample re-breaks the moment the user opens the project in Live. Consider bumping this PR into PR-L as a 1.0 blocker (rename to **L10**). The plan below is written as a standalone PR; resequencing is a copy-paste exercise.

**Closes:** the credibility gap behind `feature-needs-attention/.../NeedsAttentionScreen.kt` and the Mac-path repair flow.

### Task W1: Surface the gzipped XML stream API

**Files:**
- Modify: `shared/parser-als/src/jvmMain/kotlin/com/sketchbook/als/AlsParser.kt`
- Create: `shared/parser-als/src/jvmMain/kotlin/com/sketchbook/als/AlsRewriter.kt`
- Create: `shared/parser-als/src/jvmTest/kotlin/com/sketchbook/als/AlsRewriterTest.kt`

**Step 1: Pin a synthetic gzipped-XML fixture as a failing test**

Create a tiny `.als`-shape document by hand (see `AlsParserTest.kt` for the existing fixture pattern) with a single `<SampleRef>` whose `<Path Value="/old/missing.wav"/>` should be rewritten to `/new/found.wav`. Stick the fixture in `shared/parser-als/src/jvmTest/resources/rewriter/oneSampleRef.als.xml` and gzip-encode it in the test.

```kotlin
@Test
fun `rewrites SampleRef Path Value while preserving everything else`() {
    val original = gzipBytesOf("rewriter/oneSampleRef.als.xml")
    val rewritten = AlsRewriter.rewriteSamplePaths(
        original,
        mapping = mapOf("/old/missing.wav" to "/new/found.wav")
    )
    val text = ungzipToString(rewritten)
    assertContains(text, """<Path Value="/new/found.wav"""")
    assertFalse("/old/missing.wav" in text, "old path must be gone")
    assertTrue(text.startsWith("<?xml"), "XML preamble preserved")
}
```

**Step 2: Run test → FAIL (class doesn't exist)**

```
./gradlew :shared:parser-als:jvmTest --tests AlsRewriterTest
```

Expected: `AlsRewriter` unresolved.

**Step 3: Implement `AlsRewriter` as a StAX identity transform with overrides**

`shared/parser-als/src/jvmMain/kotlin/com/sketchbook/als/AlsRewriter.kt`:

```kotlin
object AlsRewriter {
    fun rewriteSamplePaths(gzipped: ByteArray, mapping: Map<String, String>): ByteArray {
        if (mapping.isEmpty()) return gzipped
        val outBuf = ByteArrayOutputStream(gzipped.size + 1024)
        GZIPOutputStream(outBuf).use { gzOut ->
            GZIPInputStream(ByteArrayInputStream(gzipped)).use { gzIn ->
                val factoryIn = XMLInputFactory.newFactory().apply {
                    setProperty(XMLInputFactory.IS_COALESCING, true)
                }
                val factoryOut = XMLOutputFactory.newFactory()
                val reader = factoryIn.createXMLStreamReader(gzIn, "UTF-8")
                val writer = factoryOut.createXMLStreamWriter(gzOut, "UTF-8")
                identityWithRewrite(reader, writer, mapping)
                writer.flush()
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
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_DOCUMENT -> writer.writeStartDocument(reader.encoding ?: "UTF-8", reader.version ?: "1.0")
                XMLStreamConstants.START_ELEMENT -> {
                    val name = reader.localName
                    if (name == "SampleRef") inSampleRef++
                    writer.writeStartElement(name)
                    for (i in 0 until reader.attributeCount) {
                        val attrLocal = reader.getAttributeLocalName(i)
                        var attrValue = reader.getAttributeValue(i)
                        // Rewrite when we're inside SampleRef and on a Path/RelativePath element's Value attribute
                        if (inSampleRef > 0 && (name == "Path" || name == "RelativePath") && attrLocal == "Value") {
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
                XMLStreamConstants.END_DOCUMENT -> writer.writeEndDocument()
                XMLStreamConstants.COMMENT -> writer.writeComment(reader.text)
                XMLStreamConstants.PROCESSING_INSTRUCTION -> writer.writeProcessingInstruction(reader.piTarget, reader.piData ?: "")
                else -> {} // skip whitespace events; coalescing handles text
            }
        }
    }
}
```

**Step 4: Run test → PASS**

**Step 5: Add a "preserves Mac-path repair targets too" test**

Mac-path findings rewrite `Macintosh HD:/Users/...` style paths to POSIX. Same rewriter, different mapping. Add a second fixture + test asserting both styles round-trip.

**Step 6: Commit**

```
git add shared/parser-als/src/jvmMain/kotlin/com/sketchbook/als/AlsRewriter.kt \
        shared/parser-als/src/jvmTest \
        shared/parser-als/src/jvmTest/resources/rewriter
git commit -m "feat(parser): AlsRewriter — StAX identity transform with SampleRef path overrides"
```

### Task W2: Atomic disk writer with snapshot-before

**Files:**
- Create: `shared/sync-io/src/jvmMain/kotlin/com/sketchbook/syncio/AlsPatcher.kt`
- Create: `shared/sync-io/src/jvmTest/kotlin/com/sketchbook/syncio/AlsPatcherTest.kt`

**Step 1: Write failing test for atomic replace + busy-file detection**

```kotlin
@Test
fun `patcher writes new als atomically and leaves no temp files on success`() {
    val tmpDir = createTempDirectory("patcher")
    val als = tmpDir.resolve("MyTrack.als")
    Files.write(als, gzipBytesOf("rewriter/oneSampleRef.als.xml"))
    val before = Files.getLastModifiedTime(als)

    val result = AlsPatcher().patch(
        als,
        mapping = mapOf("/old/missing.wav" to "/new/found.wav"),
    )
    assertEquals(AlsPatcher.Outcome.Patched, result)
    assertContains(ungzipToString(Files.readAllBytes(als)), "/new/found.wav")
    assertNotEquals(before, Files.getLastModifiedTime(als))
    assertEquals(0, Files.list(tmpDir).filter { it.fileName.toString().endsWith(".tmp") }.count())
}

@Test
fun `patcher skips when file is held by another process`() {
    // simulate via WorkingTreeBusyException pattern (PR-I)
    val tmpDir = createTempDirectory("patcher")
    val als = tmpDir.resolve("Open.als").also { Files.write(it, gzipBytesOf("rewriter/oneSampleRef.als.xml")) }
    val patcher = AlsPatcher(busyDetector = { _ -> true })
    assertEquals(AlsPatcher.Outcome.SkippedBusy, patcher.patch(als, mapOf("/old/missing.wav" to "/new/x.wav")))
}
```

**Step 2: Implement**

```kotlin
class AlsPatcher(
    private val busyDetector: (Path) -> Boolean = ::isFileLockedByAnotherProcess,
) {
    sealed interface Outcome {
        object Patched : Outcome
        object NoChange : Outcome
        object SkippedBusy : Outcome
        data class Failed(val cause: Throwable) : Outcome
    }

    fun patch(als: Path, mapping: Map<String, String>): Outcome {
        if (mapping.isEmpty()) return Outcome.NoChange
        if (busyDetector(als)) return Outcome.SkippedBusy
        return runCatching {
            val original = Files.readAllBytes(als)
            val rewritten = AlsRewriter.rewriteSamplePaths(original, mapping)
            if (rewritten.contentEquals(original)) return Outcome.NoChange
            val temp = als.resolveSibling("${als.fileName}.patcher-tmp")
            Files.write(temp, rewritten, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            Files.move(temp, als, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Outcome.Patched
        }.getOrElse { Outcome.Failed(it) }
    }
}

private fun isFileLockedByAnotherProcess(p: Path): Boolean = runCatching {
    FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE).use { ch ->
        ch.tryLock()?.use { } ?: return true
        false
    }
}.getOrDefault(false)
```

(`isFileLockedByAnotherProcess` mirrors the busy-detection pattern from PR-I.)

**Step 3: Run tests → PASS**

**Step 4: Commit**

```
git commit -am "feat(syncio): AlsPatcher with atomic replace + busy detection"
```

### Task W3: Wire patcher into the missing-sample Apply path

**Files:**
- Modify: `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/SqlRepairRepository.kt:149-168` (the `applyMissingSampleMatch` body)
- Modify: `shared/repository/src/jvmMain/kotlin/com/sketchbook/repo/impl/JvmRepairRepository.kt` if a JVM-only sibling exists (verify with `Glob`); otherwise inject the patcher into `SqlRepairRepository` via the JVM graph
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt` to provide `AlsPatcher`

**Step 1: Failing integration test**

`shared/repository/src/jvmTest/kotlin/com/sketchbook/repo/impl/SqlRepairRepositoryAlsRewriteTest.kt`:

```kotlin
@Test
fun `apply match rewrites the on-disk als and journals the change`() = runTest {
    val tmp = createTempDirectory("repo-rewrite")
    val als = tmp.resolve("My Project/My.als").also {
        Files.createDirectories(it.parent)
        Files.write(it, gzipBytesOf("rewriter/oneSampleRef.als.xml"))
    }
    val (db, clock) = inMemoryCatalog()
    val journal = SqlJournalRepository(db, clock, ioDispatcher = StandardTestDispatcher(testScheduler))
    val repo = SqlRepairRepository(db, clock, journal, AlsPatcher(), ioDispatcher = StandardTestDispatcher(testScheduler))
    val projectId = seedProjectWithMissingSample(db, als, missing = "/old/missing.wav")

    repo.applyMissingSampleMatch(
        projectId = projectId,
        missingPath = "/old/missing.wav",
        candidatePath = "/new/found.wav",
    )

    val text = ungzipToString(Files.readAllBytes(als))
    assertContains(text, "/new/found.wav")
    val entries = journal.observe(limit = 5).first()
    assertEquals("MissingSampleMapped", entries.first().actionType)
    val payload = Json.parseToJsonElement(entries.first().payloadJson).jsonObject
    assertEquals("Patched", payload["als_outcome"]?.jsonPrimitive?.content)
}

@Test
fun `apply match on busy als records SkippedBusy and leaves catalog updated`() = runTest {
    // patcher returns SkippedBusy → catalog row still updated (so user UX is consistent),
    // but journal payload says als_outcome=SkippedBusy and finding stays in repair_acks for retry.
}
```

**Step 2: Run → FAIL** (current `applyMissingSampleMatch` only updates `project_samples.is_missing`).

**Step 3: Implement**

In `SqlRepairRepository`:

```kotlin
class SqlRepairRepository(
    private val db: Catalog,
    private val clock: Clock,
    private val journal: JournalRepository,
    private val patcher: AlsPatcher,                   // NEW
    private val ioDispatcher: CoroutineDispatcher,
) : RepairRepository {

    override suspend fun applyMissingSampleMatch(
        projectId: ProjectId,
        missingPath: String,
        candidatePath: String,
    ): Unit = withContext(ioDispatcher) {
        val alsPath = db.projectsQueries.selectProjectPath(projectId.value).executeAsOne().toPath()
        val outcome = patcher.patch(alsPath, mapOf(missingPath to candidatePath))

        db.transaction {
            // Catalog update happens regardless of als outcome — user picked, we honor it; retry is a separate flow.
            db.projectSamplesQueries.markSampleResolved(projectId.value, missingPath, candidatePath)
            db.repairAcksQueries.insertAck(/* ... */)
        }
        journal.append(JournalEntry(
            actor = "user",
            actionType = "MissingSampleMapped",
            projectId = projectId,
            payloadJson = """{"missing":"${missingPath.jsonEscape()}","candidate":"${candidatePath.jsonEscape()}","als_outcome":"${outcome::class.simpleName}"}""",
        ))
    }
}
```

(Mac-path repair gets the same treatment in the `applyMacPathRepair` method — find it via `Grep` and wire identically. Use the same `AlsPatcher` with the Mac-path mapping.)

**Step 4: Update DesktopAppGraph** — instantiate `AlsPatcher()` and inject it into `SqlRepairRepository`.

**Step 5: Run tests → PASS**

**Step 6: Manual verification**
- `./gradlew :app-desktop:run`
- Create a synthetic missing-sample finding (or use an existing one)
- Click Pick / accept candidate
- Open the affected project in Ableton — sample should resolve

**Step 7: Commit**

```
git commit -am "feat(repair): rewrite .als on Apply; journal als outcome"
```

### Task W4: Snapshot-before-patch + Undo extends to disk

**Files:**
- Modify: `shared/repository/.../SqlRepairRepository.kt` (the `applyMissingSampleMatch` from W3)
- Modify: PR-L L7's `UndoMatch` handler

**Step 1: Failing test** — apply a match, immediately undo, assert the .als content is back to the pre-patch bytes.

**Step 2: Implement**
- Before calling `patcher.patch`, capture `originalBytes = Files.readAllBytes(als)`.
- Stage `originalBytes` into the existing snapshot blob store (use `BlobInstaller` or whatever `:shared:sync-io` exposes — verify path) keyed by `(projectId, mappingHash, timestamp)`.
- On `UndoMatch`, look up the staged bytes and write them back via `AlsPatcher` (which already does atomic replace; just call `patch` with empty mapping after first restoring the bytes manually, or extend `AlsPatcher` with a `restore(als, bytes)` method).

The simplest path: extend `AlsPatcher` with `restore(als: Path, bytes: ByteArray): Outcome` that does the same atomic-temp-rename dance. Add a unit test for it.

**Step 3: Run tests → PASS**

**Step 4: Commit**

```
git commit -am "feat(repair): snapshot-before-patch; Undo restores .als bytes"
```

### Task W5: Mac-path repair uses the same pipe

**Files:**
- Modify: `shared/repository/.../SqlRepairRepository.kt` — `applyMacPathRepair` (or whatever the symbol is named — `Grep` for `MacPath` first)
- Modify: `shared/feature-needs-attention/.../NeedsAttentionScreen.kt:145` (where the Mac-path "Acknowledge" button currently lives)

**Step 1: Failing test** — fixture with a `Macintosh HD:/Users/x/Sample.wav` path; assert post-repair the path is `/Users/x/Sample.wav` (or the user-configured POSIX root).

**Step 2: Implement** — reuse `AlsRewriter` (it already rewrites any `<Path Value=...>` inside `<SampleRef>`). The repair function maps each Mac-style path to its POSIX equivalent and passes the map to `AlsPatcher.patch`.

**Step 3: UI change** — rename the button from "Acknowledge" to "Repair paths"; keep the same `ButtonVariant.Primary` token.

**Step 4: Run tests + manual verify → PASS**

**Step 5: Commit**

```
git commit -am "feat(repair): Mac-path repair rewrites .als; rename button to Repair paths"
```

### Task W6: PR-W wrap-up

**Step 1:** `./gradlew check`. PASS.

**Step 2:** Manual end-to-end with a real project (one of the user's KSHMR-flavored sketches): introduce a missing sample, run the app, apply candidate, open in Live, verify sample resolves. Repeat for Mac-path repair using a `Projects/2024/captaincrunch Project/...` fixture (memory: that project came from macOS).

**Step 3:** Push `PR-W: .als path rewriter (relink + macpath repair actually fix the file)`.

---

## PR-X: Key extraction + Key/Tempo filter chips

**Closes:** the chip-row gap on Browse — "what's in F# minor at 140?" is the most common producer query and the data is mostly already there.

**Background:** `projects.tempo` is parsed and persisted (`AlsParser.kt:113,178`). `projects.key` column exists (`Catalog.sq:17`) but is **never written** — the parser doesn't extract it and the `INSERT OR REPLACE` at `Catalog.sq:212` doesn't list `key` in its columns. PR-X fixes both.

### Task X1: Parse the project key

**Files:**
- Modify: `shared/parser-als/src/jvmMain/kotlin/com/sketchbook/als/AlsParser.kt`
- Modify: `shared/parser-als/src/jvmMain/kotlin/com/sketchbook/als/ParseResult.kt` (or wherever `ProjectMetadata` lives — `Grep` first)

**Step 1: Failing test** — fixture `.als` with `<ScaleInformation>...<RootNote Value="2"/><Name Value="Minor"/>...</ScaleInformation>` (Live's encoding for D minor; root note is semitones-from-C). Assert `metadata.keySignature == "D Minor"`.

```kotlin
@Test
fun `parses ScaleInformation into key signature string`() {
    val result = AlsParser.parse(gzipBytesOf("parser/dminor.als.xml"))
    assertEquals("D Minor", result.metadata.keySignature)
}
```

**Step 2: Run → FAIL** (`keySignature` not on `ProjectMetadata`).

**Step 3: Implement**

Add to `ProjectMetadata`: `val keySignature: String? = null`.

Extend `AlsParser.kt:178` switch:

```kotlin
"RootNote" -> if (rootNote == null) rootNote = v.toIntOrNull()
"Name"     -> if (scaleName == null && reader.path.contains("ScaleInformation")) scaleName = v
```

(`reader.path` here is shorthand — replicate the existing in-element tracking pattern from the parser; if the parser uses a Deque<String> of element names, push on START and pop on END.)

After parse, derive:

```kotlin
val keySignature: String? = if (rootNote != null && scaleName != null) {
    val pitches = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    "${pitches[((rootNote!! % 12) + 12) % 12]} ${scaleName!!.replaceFirstChar { it.titlecase() }}"
} else null
```

**Step 4: Run → PASS**

**Step 5: Commit**

```
git commit -am "feat(parser): extract project key signature from ScaleInformation"
```

### Task X2: Persist key on insert

**Files:**
- Modify: `shared/catalog/src/commonMain/sqldelight/com/sketchbook/catalog/db/Catalog.sq:212-213` (the `insertProject` mutation)
- Modify: `shared/catalog/src/jvmMain/kotlin/com/sketchbook/catalog/JvmScanner.kt` to pass `keySignature`

**Step 1:** Add `key` to the column list and `:key` to the VALUES clause in `insertProject`.

**Step 2:** Update `JvmScanner.persistOk` to bind `metadata.keySignature` to `:key`.

**Step 3:** Add an index: `CREATE INDEX idx_projects_key ON projects(key);` (cheap, helps the chip filter).

**Step 4:** Migration `(N+1).sqm`: `CREATE INDEX IF NOT EXISTS idx_projects_key ON projects(key);` (no schema-shape change since the column already exists). If the column needs a backfill run, add a one-off `UPDATE projects SET key = NULL WHERE key = ''` to be safe.

**Step 5:** Run `./gradlew :shared:catalog:verifySqlDelightMigrations`. PASS.

**Step 6:** Smoke test: rescan the existing library; `sqlite3 ~/.sketchbook/catalog.db "select count(*) from projects where key is not null"` should be > 0.

**Step 7: Commit**

```
git commit -am "feat(catalog): persist project key on scan; index key column"
```

### Task X3: Filter chips on Browse toolbar

**Files:**
- Modify: `shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/ProjectListScreen.kt` (toolbar area)
- Modify: `shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/ProjectListStateHolder.kt`
- Modify: `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/ProjectRepository.kt` (filter signature)

**Step 1: Failing state-holder test**

```kotlin
@Test
fun `tempo and key filters narrow the row set`() = runTest {
    val rows = listOf(
        fakeRow(name = "a", tempo = 140.0, key = "F# Minor"),
        fakeRow(name = "b", tempo = 128.0, key = "F# Minor"),
        fakeRow(name = "c", tempo = 140.0, key = "C Major"),
    )
    val holder = ProjectListStateHolder(repo = FakeProjectRepo(rows), ...)
    holder.dispatch(Intent.SetTempoRange(135.0..145.0))
    holder.dispatch(Intent.SetKeyFilter("F# Minor"))
    val visible = holder.state.first { it.tempoRange != null && it.keyFilter == "F# Minor" }.visibleRows
    assertEquals(listOf("a"), visible.map { it.name })
}
```

**Step 2: Implement**

State additions:

```kotlin
data class ProjectListUiState(
    // existing fields ...
    val tempoRange: ClosedFloatingPointRange<Double>? = null,
    val keyFilter: String? = null,
)
```

Filter happens in the `derived`-state combine added by PR-M Task M3 (or in the same `combine` if M3 hasn't shipped yet). Apply `tempoRange` and `keyFilter` on `rows` before grouping.

UI: in the existing toolbar Row above shelves, add two chips between the search field and the bucket selector:

- **Tempo chip** — label: "Tempo: any" / "Tempo: 130–145" depending on state. Click opens a small Popup with two `OutlinedTextField`s for min/max BPM; `accentSecondary` border.
- **Key chip** — label: "Key: any" / "Key: F# Minor". Click opens a Popup with a vertically-scrolling list of distinct keys (run `selectDistinctKeys` query against the catalog).

Both chips: per `feedback_color_restraint`, no new colors; reuse the same chip token already used elsewhere on the toolbar.

**Step 3: Add `selectDistinctKeys` SQL**

```sql
selectDistinctKeys:
SELECT DISTINCT key FROM projects WHERE key IS NOT NULL ORDER BY key;
```

**Step 4: Run state-holder test + manual visual** → PASS.

**Step 5: Commit**

```
git commit -am "feat(projects): tempo + key filter chips on Browse toolbar"
```

### Task X4: PR-X wrap-up

Push `PR-X: key extraction + key/tempo filter chips on Browse`.

---

## PR-Z: Snapshot label + quick-capture hotkey

**Goal:** Two complementary affordances:
1. Inline label edit on any snapshot row in Timeline (rename a take after the fact).
2. Global hotkey (`Ctrl+Shift+S` / `Cmd+Shift+S`) that forces a snapshot of the *current* project state and prompts for a label.

PR-O O4 (CoalesceJob) handles auto-promotion based on idle time; PR-Z is the user-driven counterpart.

### Task Z1: `updateSnapshotLabel` SQL + repository

**Files:**
- Modify: `Catalog.sq`
- Modify: `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/SqlSnapshotRepository.kt`

**Step 1:** SQL:

```sql
updateSnapshotLabel:
UPDATE snapshots SET label = ?, kind = 'named' WHERE project_uuid = ? AND rev = ?;
```

(Auto labels become Named on edit — consistent with O4.)

**Step 2:** Repository method `setSnapshotLabel(projectUuid, rev, label)` + journal entry `SnapshotRelabeled`.

**Step 3: Test** — set label, observe via `observeSnapshots`, expect updated label and `kind = Named`.

**Step 4: Commit**

```
git commit -am "feat(snapshot): updateSnapshotLabel + Named promotion on edit"
```

### Task Z2: Inline label edit on Timeline

**Files:**
- Modify: `shared/feature-timeline/src/commonMain/kotlin/com/sketchbook/featuretimeline/TimelineScreen.kt`
- Modify: `shared/feature-timeline/.../TimelineStateHolder.kt`

**Step 1:** Per-row pencil icon next to the existing label (or label-placeholder) — click swaps the row into an inline `BasicTextField`. Save on Enter or blur; Esc cancels. Use the same inline-edit pattern as the project-name field in `DetailPanelContent`.

**Step 2:** Holder gets `Intent.RelabelSnapshot(rev, newLabel)` → calls repository.

**Step 3:** Test — dispatch intent, observe label change in state.

**Step 4: Commit**

```
git commit -am "feat(timeline): inline label edit per snapshot row"
```

### Task Z3: Quick-capture hotkey

**Files:**
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt` (key event dispatch)
- Create: `shared/sync/src/commonMain/kotlin/com/sketchbook/sync/ForceSnapshotUseCase.kt`

**Step 1: Failing test** for `ForceSnapshotUseCase` — given a project uuid, calls into the existing `SnapshotPipeline` machinery to record a `Named` snapshot of current bytes regardless of dirty flag.

```kotlin
@Test
fun `force snapshot writes a named row even if working tree is clean`() = runTest {
    val pipeline = FakeSnapshotPipeline()
    val useCase = ForceSnapshotUseCase(pipeline, syncStateStore)
    useCase.invoke(projectUuid, label = "demo for jay")
    assertEquals(1, pipeline.recordedSnapshots.size)
    assertEquals("named", pipeline.recordedSnapshots[0].kind)
    assertEquals("demo for jay", pipeline.recordedSnapshots[0].label)
}
```

**Step 2:** Implement using `SnapshotPipeline` + the manifest path it already produces. Don't bypass the pipeline — go through it so blob upload and journaling happen normally.

**Step 3: Wire hotkey in `RootContent.kt`** at the root `Modifier.onPreviewKeyEvent`:

```kotlin
.onPreviewKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.isShiftPressed && e.key == Key.S) {
        val current = currentProjectId() ?: return@onPreviewKeyEvent false
        showQuickCaptureDialog(current)
        true
    } else false
}
```

(`isCtrlPressed` resolves to Cmd on Mac via Compose's KeyShortcut — verify in the existing codebase; if not, branch on `hostOs`.)

**Step 4: Quick-capture dialog** — Single `AlertDialog` with one text field for the label and Save / Cancel. Save → `ForceSnapshotUseCase`. Use the existing dialog style (find by `Grep` for `AlertDialog` in `app-desktop/`).

**Step 5: Manual smoke** — open project, hit hotkey, type label, confirm. Open Timeline. Named snapshot appears.

**Step 6: Commit**

```
git commit -am "feat(snapshot): Ctrl+Shift+S force-named snapshot with label"
```

### Task Z4: PR-Z wrap-up

Push `PR-Z: snapshot labeling + quick-capture hotkey`.

---

## PR-AA: Always-on plugin pivot

**Goal:** Mirror PR-S sample family tree, but for plugins. From any plugin row in DetailPanelContent's Plugins tab, popover lists every other project using that plugin.

PR-T's "Plugin risk forecast" only surfaces this pivot **when a plugin is missing**. PR-AA makes it always available, so the user can always answer "what else am I doing with Serum?"

### Task AA1: Inverse query

**Files:**
- Modify: `Catalog.sq`

**Step 1:** Add SQL:

```sql
selectProjectsUsingPlugin:
SELECT DISTINCT p.id, p.name, p.path, p.last_modified
FROM projects p
JOIN project_plugins pp ON pp.project_id = p.id
WHERE pp.name = ? AND (pp.version = ? OR ? IS NULL)
ORDER BY p.last_modified DESC;
```

(Version-tolerant: passing `null` matches any version.)

**Step 2:** Expose `PluginRepository.observeProjectsUsing(name, version)`.

**Step 3:** Test — seed three projects (A: Serum 1.35, B: Serum 1.36, C: Vital), assert `observeProjectsUsing("Serum", null)` returns A+B; `observeProjectsUsing("Serum", "1.35")` returns A only.

**Step 4: Commit**

```
git commit -am "feat(plugins): inverse query for projects-using-plugin"
```

### Task AA2: Popover UI

**Files:**
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt` (DetailPluginsTab)

**Step 1:** Each plugin row gets a chevron button (or made fully clickable). Click → `Popup` anchored to the row showing up to 10 other projects with their thumbnails (PR-P leverage if shipped, else just names + relative time). Click a row → switch the detail panel to that project.

**Step 2:** Two-layer header in the popover:
- "Other projects using **Serum**" (any version), and
- "...with this version (1.35)" — small toggle pill.

**Step 3:** Layered, no new colors — match PR-S popover styling exactly.

**Step 4: Visual smoke** — `./gradlew :app-desktop:run`; click any plugin in any project; popover should list other consumers.

**Step 5: Commit**

```
git commit -am "ui(detail): always-on plugin pivot popover"
```

### Task AA3: PR-AA wrap-up

Push `PR-AA: always-on plugin pivot`.

---

## PR-BB: Library health score chip

**Goal:** Single sidebar chip: "Library health: 87%". Click → small breakdown popup. Aggregates four signals already in the catalog:

- `% synced` = projects with `dirty=0 AND remote_rev=local_rev` over total non-archived.
- `% sample-clean` = projects with zero `project_samples.is_missing=1`.
- `% plugin-clean` = projects with zero plugins where `is_installed=0` (depends on PR-T).
- `% non-stuck` = projects where stage ≠ `Stuck` (depends on PR-R).

Composite: equal-weighted average of the four signals.

**Hard dependency:** PR-T (`is_installed`) and PR-R (`stage_inferred`) must ship first. If either slips, PR-BB ships with the missing dimensions excluded from the average — the breakdown popup just doesn't list them.

### Task BB1: `selectLibraryHealth` aggregate

**Files:**
- Modify: `Catalog.sq`

**Step 1:** SQL:

```sql
selectLibraryHealth:
SELECT
    COUNT(*) AS total,
    SUM(CASE WHEN s.dirty = 0 AND s.remote_rev = s.local_rev THEN 1 ELSE 0 END) AS synced,
    SUM(CASE WHEN ms.missing_count = 0 OR ms.missing_count IS NULL THEN 1 ELSE 0 END) AS sample_clean,
    SUM(CASE WHEN mp.missing_plugin_count = 0 OR mp.missing_plugin_count IS NULL THEN 1 ELSE 0 END) AS plugin_clean,
    SUM(CASE WHEN COALESCE(p.stage_override, p.stage_inferred) <> 'Stuck' OR COALESCE(p.stage_override, p.stage_inferred) IS NULL THEN 1 ELSE 0 END) AS non_stuck
FROM projects p
LEFT JOIN sync_state s ON s.uuid = p.uuid
LEFT JOIN (SELECT project_id, COUNT(*) AS missing_count FROM project_samples WHERE is_missing = 1 GROUP BY project_id) ms ON ms.project_id = p.id
LEFT JOIN (SELECT project_id, COUNT(*) AS missing_plugin_count FROM project_plugins WHERE is_installed = 0 GROUP BY project_id) mp ON mp.project_id = p.id
WHERE p.archived = 0;
```

**Step 2:** Test with seeded fixture (10 projects, varied conditions) — assert each component.

**Step 3: Commit**

```
git commit -am "feat(catalog): selectLibraryHealth aggregate"
```

### Task BB2: Health chip + breakdown popup

**Files:**
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt` (sidebar)

**Step 1:** Sidebar chip below "Sync" entry: "Health: 87%". The number color is `accentAction` if ≥ 90%, `accentWarning` if 70–89%, `accentDanger` if < 70% — reusing existing tokens.

**Step 2:** Click → popup with bullet rows:
- "Synced: 92% (1,498 / 1,628)"
- "Samples clean: 85%"
- "Plugins installed: 78%"  *(only if PR-T shipped)*
- "Not stuck: 91%"  *(only if PR-R shipped)*
Each row clickable → opens the projects list pre-filtered to the failing subset.

**Step 3:** Re-poll the aggregate every 60s and on any catalog mutation observed via `JournalRepository.observe(limit=1)` (any mutation invalidates).

**Step 4: Visual smoke** — observe number changes after archiving a project; mark a sample missing; confirm health drops accordingly.

**Step 5: Commit**

```
git commit -am "feat(sidebar): library health score chip with breakdown popup"
```

### Task BB3: PR-BB wrap-up

Push `PR-BB: library health score chip`.

---

## Notes on execution discipline

- **No batch checkpoints within a PR.** (Per `feedback_no_batch_checkpoints`.) Drive through tasks within a PR and visually verify as you go.
- **One PR at a time.** (Per `feedback_one_pr_at_a_time`.) Don't open W and X concurrently.
- **Local build is the gate.** When `./gradlew check` passes, merge with `--admin`. (Per `feedback_local_build_authority`.)
- **No new colors.** Reuse `accentAction`, `accentSecondary`, `accentWarning`, `accentDanger`, `inkMuted`. (Per `feedback_color_restraint`.)
- **No new libraries.**
- **Layer onto existing UI; never redesign.** Every UI addition is a chip, popover, card, or sidebar entry. (Per `feedback_layer_dont_redesign`.)
- **Browser-verify visually as you go** — every UI-touching task should `./gradlew :app-desktop:run` once before commit.

## Recommended sequencing

1. **PR-W first** (or fold into PR-L) — credibility fix; everything else is gravy.
2. PR-X and PR-Z in any order — independent, small, high-frequency value.
3. PR-AA after PR-S ships (mirror its popover pattern).
4. PR-BB last — depends on PR-T and PR-R, so it's a natural cap on the post-1.0 wave.
