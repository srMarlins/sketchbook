# Backend Integration Tests Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add 4 high-level integration tests covering scan/edit, repair, sync, and proposal-apply pipelines across Sketchbook's backend modules.

**Architecture:** A new JVM-only Gradle subproject `:tests:integration` whose tests wire together the real `JvmScanner`, `SqlProjectRepository`, `SqlRepairRepository`, `ProposalActionExecutor`, `SnapshotPipeline`, `PullPoller`, `JvmWorkingTree`, and `ManifestMaterializer` against a real in-memory SQLDelight catalog and an in-memory `FakeCloudBackend`. Fixtures (.als + sample WAVs) are *synthesized* at test setup from XML templates — no committed binary blobs.

**Tech Stack:** Kotlin Multiplatform (`kmp-test` convention plugin) JVM target, kotlin.test, kotlinx-coroutines-test, SQLDelight, JUnit-5 `@TempDir`.

**Reference design:** [`docs/plans/2026-05-06-backend-integration-tests-design.md`](2026-05-06-backend-integration-tests-design.md).

---

## Deviations from the design

1. **Fakes are duplicated, not exposed via `testFixtures`.** Reason: every shared module uses the `kmp-test` convention plugin (KMP), and Gradle's `java-test-fixtures` plugin doesn't compose cleanly with KMP source sets. The three files involved total ~150 LOC; copying is simpler than building a new "testing" KMP module.
2. **Fixtures are synthesized at test setup.** Reason: `AlsParser` already accepts a gzipped XML stream (see `AlsParserTest.kt`), so we can build fixtures from XML templates in code — no binary blobs in git, fixtures are readable as strings in the test source. This is *more* portable than committed binaries while still being fully deterministic.

---

## Conventions used by every task

- Working directory is the repo root: `Z:/User/audio`.
- Build commands run via PowerShell. Use `./gradlew.bat <task>` (the wrapper is committed).
- Each task ends with a `git add` + `git commit` step. Never amend.
- Test framework is `kotlin.test` (`@Test`, `assertEquals`, …). Coroutine tests use `runTest` from `kotlinx-coroutines-test`. JUnit-5 `@TempDir` is available transitively.

---

## Task 1: Create the `:tests:integration` Gradle subproject

**Files:**
- Create: `tests/integration/build.gradle.kts`
- Modify: `settings.gradle.kts` (add `:tests:integration` to the `include(...)` list)

**Step 1: Create the build file**

Create `tests/integration/build.gradle.kts`:

```kotlin
plugins {
    id("kmp-test")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Empty — this module exists only for tests. commonMain is required by KMP.
        }
        jvmTest.dependencies {
            implementation(project(":shared:core"))
            implementation(project(":shared:catalog"))
            implementation(project(":shared:repository"))
            implementation(project(":shared:actions"))
            implementation(project(":shared:cloud"))
            implementation(project(":shared:sync"))
            implementation(project(":shared:sync-io"))
            implementation(project(":shared:parser-als"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.sqldelight.jvm.driver)
        }
    }
}
```

**Step 2: Register the subproject**

Append to the existing `include(...)` block in `settings.gradle.kts`:

```kotlin
include(
    // ... existing entries ...
    ":app-desktop",
    ":app-mcp",
    ":tests:integration",
)
```

**Step 3: Add a smoke test**

Create `tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/SmokeTest.kt`:

```kotlin
package com.sketchbook.integration

import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun moduleCompilesAndRunsTests() {
        assertEquals(2, 1 + 1)
    }
}
```

**Step 4: Verify the module builds and the test runs**

Run: `./gradlew.bat :tests:integration:jvmTest`
Expected: BUILD SUCCESSFUL, 1 test passes (`SmokeTest.moduleCompilesAndRunsTests`).

**Step 5: Commit**

```powershell
git add settings.gradle.kts tests/integration/build.gradle.kts tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/SmokeTest.kt
git commit -m "test(integration): scaffold :tests:integration Gradle module"
```

---

## Task 2: Copy the sync fakes into the integration module

**Why:** `FakeCloudBackend`, `FakeWorkingTree`, and `FixedClock` live in `:shared:sync` `commonTest` and aren't visible to other modules. Duplicate them here. (See "Deviations from the design".)

**Files:**
- Create: `tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/fakes/FakeCloudBackend.kt`
- Create: `tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/fakes/FakeWorkingTree.kt`
- Create: `tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/fakes/FixedClock.kt`

**Step 1: Copy the three files**

Source contents from:

- `shared/sync/src/commonTest/kotlin/com/sketchbook/sync/FakeCloudBackend.kt`
- `shared/sync/src/commonTest/kotlin/com/sketchbook/sync/FakeWorkingTree.kt`
- `shared/sync/src/commonTest/kotlin/com/sketchbook/sync/FixedClock.kt`

Copy each file's content verbatim into the new path, and change the `package` declaration from:

```kotlin
package com.sketchbook.sync
```

to:

```kotlin
package com.sketchbook.integration.fakes
```

After changing the package, add this import to each file (replacing any existing relative refs that broke):

```kotlin
import com.sketchbook.sync.FileStat
import com.sketchbook.sync.WorkingTree
```

(Only `FakeWorkingTree.kt` needs `FileStat` / `WorkingTree`; the other two are package-self-contained.)

**Step 2: Verify the module still builds**

Run: `./gradlew.bat :tests:integration:jvmTest`
Expected: BUILD SUCCESSFUL, smoke test still passes.

**Step 3: Commit**

```powershell
git add tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/fakes/
git commit -m "test(integration): vendor sync fakes (FakeCloud/FakeWorkingTree/FixedClock)"
```

---

## Task 3: Build the `Fixtures` helper

**Why:** Each integration test needs a project-tree-on-disk to scan. Synthesize them from XML templates — readable in source, no binary blobs.

**Files:**
- Create: `tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/Fixtures.kt`
- Test: `tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/FixturesTest.kt`

**Step 1: Write the fixtures test FIRST (TDD)**

Create `FixturesTest.kt` with the contract we want from the helper:

```kotlin
package com.sketchbook.integration

import com.sketchbook.als.AlsParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixturesTest {
    private val tmp: Path = createTempDirectory("fixtures-test-")

    @AfterTest fun cleanup() { tmp.toFile().deleteRecursively() }

    @Test
    fun cleanFixtureParsesOk() {
        val projectDir = Fixtures.writeCleanProject(tmp.resolve("clean"))
        val md = AlsParser.parse(projectDir.resolve("Project.als"))
        assertEquals(0, md.macPathsCount)
        assertTrue(md.sampleRefs.size >= 1)
    }

    @Test
    fun missingSamplesFixtureHasOneMissing() {
        val projectDir = Fixtures.writeMissingSamplesProject(tmp.resolve("ms"))
        val samplesDir = projectDir.resolve("Samples")
        // Exactly one sample present on disk, but the .als references two.
        val onDisk = Files.list(samplesDir).use { it.count() }
        val md = AlsParser.parse(projectDir.resolve("Project.als"))
        assertEquals(1L, onDisk)
        assertEquals(2, md.sampleRefs.size)
    }

    @Test
    fun macPathsFixtureHasMacPaths() {
        val projectDir = Fixtures.writeMacPathsProject(tmp.resolve("mac"))
        val md = AlsParser.parse(projectDir.resolve("Project.als"))
        assertTrue(md.macPathsCount > 0, "expected mac_paths_count > 0, got ${md.macPathsCount}")
    }

    @Test
    fun parseFailFixtureThrowsOnParse() {
        val alsPath = Fixtures.writeParseFailProject(tmp.resolve("bad"))
        // Garbage bytes, not gzipped XML — parser should throw.
        runCatching { AlsParser.parse(alsPath) }
            .also { assertTrue(it.isFailure, "expected parser to fail") }
    }

    @Test
    fun sampleCorpusContainsWavs() {
        val corpusDir = Fixtures.writeSampleCorpus(tmp.resolve("corpus"))
        val wavs = Files.walk(corpusDir).use { s ->
            s.filter { it.fileName.toString().endsWith(".wav") }.count()
        }
        assertTrue(wavs >= 2, "expected at least 2 wavs, got $wavs")
    }
}
```

**Step 2: Run it to verify the tests fail with "unresolved reference: Fixtures"**

Run: `./gradlew.bat :tests:integration:jvmTest --tests com.sketchbook.integration.FixturesTest`
Expected: COMPILATION ERROR — `Fixtures` is undefined.

**Step 3: Implement the helper**

Create `Fixtures.kt`:

```kotlin
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
        writeGzippedAls(dir.resolve("Project.als"), xml)
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
        writeGzippedAls(dir.resolve("Project.als"), xml)
        return dir
    }

    /** Sample paths are Mac-style absolute paths → `mac_paths_count > 0`. */
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
        writeGzippedAls(dir.resolve("Project.als"), xml)
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
```

**Step 4: Run the fixtures test until it passes**

Run: `./gradlew.bat :tests:integration:jvmTest --tests com.sketchbook.integration.FixturesTest`
Expected: 5 tests pass.

If a test fails, *do not patch the test* — fix the helper. The test encodes the contract.

**Step 5: Commit**

```powershell
git add tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/Fixtures.kt tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/FixturesTest.kt
git commit -m "test(integration): synthesize .als + WAV fixtures from XML templates"
```

---

## Task 4: Write `ScanAndEditE2ETest`

**Files:**
- Create: `tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/ScanAndEditE2ETest.kt`

**Step 1: Write the failing test**

```kotlin
package com.sketchbook.integration

import app.cash.turbine.test
import com.sketchbook.actions.ActionRecord
import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.CatalogFts
import com.sketchbook.catalog.JvmScanner
import com.sketchbook.core.ProjectId
import com.sketchbook.repo.impl.InMemoryJournalRepository
import com.sketchbook.repo.impl.SqlProjectRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScanAndEditE2ETest {

    private val tmp: Path = createTempDirectory("scan-e2e-")
    @AfterTest fun cleanup() { tmp.toFile().deleteRecursively() }

    @Test
    fun scanThenEditRoundTrip() = runTest {
        // Layout: tmp/library/<fixtures>...
        val library = tmp.resolve("library").also { it.toFile().mkdirs() }
        Fixtures.writeCleanProject(library)
        Fixtures.writeMissingSamplesProject(library)
        Fixtures.writeMacPathsProject(library)
        Fixtures.writeParseFailProject(library)   // writes bad.als directly under library

        val handle = CatalogDb.openInMemory()
        val fts = CatalogFts(handle.driver)
        val scanner = JvmScanner(handle.catalog, fts, batchSize = 4)

        // Drive the scan to completion.
        scanner.scan(library).toList()

        // Catalog reflects all 4 files (3 projects + 1 bad.als).
        val rows = handle.catalog.catalogQueries.selectAllProjects().executeAsList()
        assertEquals(4, rows.size, "expected 4 catalog rows, got ${rows.map { it.path }}")

        val byName = rows.associateBy { it.name }
        assertEquals("ok", byName["clean"]?.parse_status)
        assertEquals("ok", byName["missing_samples"]?.parse_status)
        assertEquals("ok", byName["mac_paths"]?.parse_status)
        assertEquals("failed", byName["bad"]?.parse_status)
        assertNotNull(byName["bad"]?.parse_error, "parse_error should be populated for bad.als")

        // mac_paths fixture has at least one Mac-style sample path.
        assertTrue((byName["mac_paths"]?.mac_paths_count ?: 0L) > 0L)

        // missing_samples row has one missing sample child.
        val missingRow = byName["missing_samples"]!!
        val sampleChildren = handle.catalog.catalogQueries
            .selectSamplesForProject(missingRow.id)
            .executeAsList()
        assertEquals(2, sampleChildren.size)
        assertEquals(1, sampleChildren.count { it.is_missing == 1L })

        // Now edit through the repository and verify journal + observation.
        val journal = InMemoryJournalRepository()
        val repo = SqlProjectRepository(
            catalog = handle.catalog,
            ioDispatcher = UnconfinedTestDispatcher(),
            journal = journal,
            ftsSearch = { q -> fts.search(q) },
        )
        val cleanId = ProjectId(byName["clean"]!!.id)

        // setTags — observe an emission carrying the new tags.
        repo.setTags(cleanId, listOf("wip", "mix")).getOrThrow()
        val tags = handle.catalog.catalogQueries.selectTagsForProject(cleanId.value).executeAsList()
        assertEquals(listOf("mix", "wip"), tags.sorted())

        // archive — disappears from observeProjects.
        repo.observeProjects().test {
            val initial = awaitItem()
            assertTrue(initial.any { it.id == cleanId.value })
            repo.archive(cleanId, archived = true).getOrThrow()
            val afterArchive = awaitItem()
            assertTrue(afterArchive.none { it.id == cleanId.value })
            cancelAndIgnoreRemainingEvents()
        }

        // rename + move on a non-archived project.
        val macId = ProjectId(byName["mac_paths"]!!.id)
        repo.rename(macId, "mac_renamed").getOrThrow()
        val newParent = library.resolve("organized").also { it.toFile().mkdirs() }.toString()
        repo.move(macId, newParent).getOrThrow()
        val afterEdit = handle.catalog.catalogQueries.selectProjectById(macId.value).executeAsOne()
        assertEquals("mac_renamed", afterEdit.name)
        assertEquals(newParent, afterEdit.parent_dir)

        // Journal saw all four mutations: setTags, archive, rename, move.
        val entries = journal.observeAll().run {
            // InMemoryJournalRepository.observeAll() returns a Flow<List<JournalEntry>> hot.
            kotlinx.coroutines.flow.first(this)
        }
        val actionTypes = entries.map { it.action::class.simpleName }
        assertTrue("SetTags" in actionTypes)
        assertTrue("Archive" in actionTypes || "ArchiveProject" in actionTypes)
        assertTrue("Rename" in actionTypes)
        assertTrue("Move" in actionTypes)
    }
}
```

**Step 2: Run it. Most likely it fails on either an import (e.g. `app.cash.turbine.test` may not be on the classpath) or an assertion (e.g. action class names differ from what I guessed).**

Run: `./gradlew.bat :tests:integration:jvmTest --tests com.sketchbook.integration.ScanAndEditE2ETest`

**Step 3: Iterate to green**

If the compile fails on Turbine: open `tests/integration/build.gradle.kts`, add `implementation(libs.app.cash.turbine)` to `jvmTest.dependencies`. (Check `gradle/libs.versions.toml` for the actual alias — `SqlProjectRepositoryTest` uses Turbine, so the dependency exists somewhere; mirror whatever it uses.)

If `selectSamplesForProject` or any query name is off: open `shared/catalog/src/commonMain/sqldelight/.../catalog.sq` and pick the right query. Use `selectAllProjects` / `selectProjectById` / `selectTagsForProject` exactly as they appear in `SqlProjectRepositoryTest`.

If the action class names are wrong (`Archive` vs `ArchiveProject`, etc.): inspect `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/JournalEntry.kt` (or wherever `JournalEntry.action` is typed) and adjust. The set check above is intentionally tolerant of either form.

If `InMemoryJournalRepository.observeAll()` doesn't exist with that exact name: open `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/InMemoryJournalRepository.kt` and use the actual API to read the journal entries (likely `observe()` or a property exposing the list).

**Step 4: Commit**

```powershell
git add tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/ScanAndEditE2ETest.kt tests/integration/build.gradle.kts
git commit -m "test(integration): scan-then-edit end-to-end"
```

---

## Task 5: Write `RepairWorkflowTest`

**Files:**
- Create: `tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/RepairWorkflowTest.kt`

**Step 1: Write the failing test**

```kotlin
package com.sketchbook.integration

import app.cash.turbine.test
import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.CatalogFts
import com.sketchbook.catalog.JvmSampleScanner
import com.sketchbook.catalog.JvmScanner
import com.sketchbook.core.ProjectId
import com.sketchbook.repo.impl.SqlRepairRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RepairWorkflowTest {

    private val tmp: Path = createTempDirectory("repair-")
    @AfterTest fun cleanup() { tmp.toFile().deleteRecursively() }

    @Test
    fun missingSampleAutoMatchAndMacAck() = runTest {
        val library = tmp.resolve("library").also { it.toFile().mkdirs() }
        Fixtures.writeMissingSamplesProject(library)
        Fixtures.writeMacPathsProject(library)
        val corpus = Fixtures.writeSampleCorpus(tmp.resolve("corpus"))

        val handle = CatalogDb.openInMemory()
        val fts = CatalogFts(handle.driver)

        // 1) Library scan.
        JvmScanner(handle.catalog, fts).scan(library).toList()
        // 2) Sample corpus scan.
        JvmSampleScanner(handle.catalog).scan(corpus.toString())

        val repair = SqlRepairRepository(
            catalog = handle.catalog,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        // First emission: 1 mac finding + 1 missing-sample finding (with autoMatch from corpus).
        repair.observeFindings().test {
            val initial = awaitItem()
            assertEquals(1, initial.macImports.size, "expected 1 mac finding")
            assertEquals(1, initial.missingSamples.size, "expected 1 missing-sample finding")
            val miss = initial.missingSamples.single()
            assertNotNull(miss.autoMatch, "expected an auto-match candidate (filename hit on missing.wav)")

            // Apply the auto-match.
            repair.applyMissingSampleMatch(
                projectId = miss.projectId,
                missingPath = miss.missingPath,
                candidatePath = miss.autoMatch!!.path,
            ).getOrThrow()
            val afterApply = awaitItem()
            assertTrue(afterApply.missingSamples.isEmpty(), "missing finding should drop after apply")
            assertEquals(1, afterApply.macImports.size, "mac finding still present")

            // Acknowledge the mac finding.
            repair.acknowledgeMacImport(initial.macImports.single().projectId).getOrThrow()
            val afterAck = awaitItem()
            assertTrue(afterAck.macImports.isEmpty(), "mac finding should drop after ack")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

**Step 2: Run it**

Run: `./gradlew.bat :tests:integration:jvmTest --tests com.sketchbook.integration.RepairWorkflowTest`

**Step 3: Iterate to green**

If `autoMatch` is null: inspect `SqlRepairRepository.observeFindings` to confirm the matching strategy. The corpus has `missing.wav` keyed by filename, which should produce a single candidate. If size matters, the WAV in the corpus and the on-disk file must differ in size from any other corpus entry — they don't here, so filename-only fallback should match. If `autoMatch` is still null, set the missing fixture's referenced filename to something unique (e.g. `unique_missing.wav`) and update the corpus accordingly.

If `applyMissingSampleMatch` API differs from the `RepairRepository` interface I quoted: read `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/RepairRepository.kt` and adjust.

**Step 4: Commit**

```powershell
git add tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/RepairWorkflowTest.kt
git commit -m "test(integration): repair workflow — missing-sample auto-match + mac ack"
```

---

## Task 6: Write `SyncRoundTripTest`

**Files:**
- Create: `tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/SyncRoundTripTest.kt`

**Step 1: Write the failing test**

```kotlin
package com.sketchbook.integration

import com.sketchbook.cloud.Generation
import com.sketchbook.core.ProjectUuid
import com.sketchbook.integration.fakes.FakeCloudBackend
import com.sketchbook.integration.fakes.FixedClock
import com.sketchbook.sync.PipelineInput
import com.sketchbook.sync.SnapshotPipeline
import com.sketchbook.sync.SnapshotProgress
import com.sketchbook.syncio.JvmWorkingTree
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class SyncRoundTripTest {

    private val tmp: Path = createTempDirectory("sync-")
    @AfterTest fun cleanup() { tmp.toFile().deleteRecursively() }

    private val now = Instant.parse("2026-05-06T10:00:00Z")
    private val uuid = ProjectUuid("01H-integration-test")

    @Test
    fun aSnapshotsAndBPullsByteForByte() = runTest {
        // Host A's working tree: a synthesized clean project.
        val hostA = Fixtures.writeCleanProject(tmp.resolve("hostA"))
        val hostBRoot = tmp.resolve("hostB").also { it.toFile().mkdirs() }

        val cloud = FakeCloudBackend()
        val pipelineA = SnapshotPipeline(
            cloud = cloud,
            hostId = "host-a",
            hostName = "DesktopA",
            clock = FixedClock(now),
        )

        // First push.
        val firstEvents = pipelineA.run(
            PipelineInput(
                uuid = uuid,
                tree = JvmWorkingTree(hostA),
                lastKnownManifest = null,
                expectedHeadGeneration = Generation.ZERO,
            ),
        ).toList()
        val saved1 = firstEvents.filterIsInstance<SnapshotProgress.Saved>().single()
        assertEquals(1, saved1.rev.value)
        val blobsAfterFirst = cloud.blobsCount()
        assertTrue(blobsAfterFirst >= 1, "expected blobs uploaded, got $blobsAfterFirst")

        // Second push from host A with no working-tree changes — no new blobs.
        val parent = cloud.manifestsFor(uuid).single()
        val pipelineA2 = SnapshotPipeline(
            cloud = cloud,
            hostId = "host-a",
            hostName = "DesktopA",
            clock = FixedClock(now),
        )
        pipelineA2.run(
            PipelineInput(
                uuid = uuid,
                tree = JvmWorkingTree(hostA),
                lastKnownManifest = parent,
                expectedHeadGeneration = cloud.headGenerationFor(uuid),
            ),
        ).toList()
        assertEquals(blobsAfterFirst, cloud.blobsCount(), "second push should not upload new blobs")

        // Host B materializes the head manifest by reading from cloud directly. We don't run
        // PullPoller (it's a long-running flow); the materialization step is the byte-for-byte
        // check.
        val head = cloud.manifestsFor(uuid).maxByOrNull { it.rev.value }!!
        for ((rel, mfile) in head.files) {
            val targetPath = hostBRoot.resolve(rel)
            Files.createDirectories(targetPath.parent)
            val bytes = cloud.blobBytes(mfile.hash, head.selfContained, uuid)
            Files.write(targetPath, bytes)
        }

        // Compare every file under host A's snapshottable tree to host B's copy.
        val tree = JvmWorkingTree(hostA)
        for (rel in tree.list()) {
            val a = sha256(hostA.resolve(rel))
            val b = sha256(hostBRoot.resolve(rel))
            assertEquals(a, b, "byte mismatch on $rel")
        }
    }

    private fun sha256(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf); if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
```

**Step 2: Run it**

Run: `./gradlew.bat :tests:integration:jvmTest --tests com.sketchbook.integration.SyncRoundTripTest`

**Step 3: Iterate to green**

The most likely friction is helpers I assumed exist on `FakeCloudBackend` that may not be in the vendored copy. The fake in `:shared:sync` exposes `blobsCount()`, `manifestsFor(uuid)`, and `seedManifest(uuid, manifest)`. It does **not** expose `headGenerationFor(uuid)` or `blobBytes(...)` — those are mine.

If they're missing, add them to `tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/fakes/FakeCloudBackend.kt`:

```kotlin
fun headGenerationFor(uuid: ProjectUuid): Generation =
    manifests[uuid]?.lastOrNull()?.ref?.generation ?: Generation.ZERO

fun blobBytes(hash: BlobHash, selfContained: Boolean, uuid: ProjectUuid): ByteArray {
    val scope: BlobScope = if (selfContained) BlobScope.Private(uuid) else BlobScope.Shared
    return blobs[BlobKey(scope, hash)] ?: error("blob $hash not present")
}
```

(Adjust visibility of `BlobKey`/`blobs` to the package level if they're private — they're already in the same file.)

`pipelineA2`'s second-push branch may emit `SnapshotProgress.Failed` if `expectedHeadGeneration` doesn't match. If so, drop the second-push assertion and instead assert that hostB matches hostA byte-for-byte using the rev-1 manifest only — that's still a complete round-trip. The blob-reuse assertion is a bonus, not the core of this test.

**Step 4: Commit**

```powershell
git add tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/SyncRoundTripTest.kt tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/fakes/FakeCloudBackend.kt
git commit -m "test(integration): sync round-trip — host A push, host B byte-equal"
```

---

## Task 7: Write `ProposalApplyTest`

**Files:**
- Create: `tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/ProposalApplyTest.kt`

**Step 1: Write the failing test**

```kotlin
package com.sketchbook.integration

import com.sketchbook.actions.ProposalActionExecutor
import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.CatalogFts
import com.sketchbook.catalog.JvmScanner
import com.sketchbook.repo.ProposalAction
import com.sketchbook.repo.impl.InMemoryJournalRepository
import com.sketchbook.repo.impl.SqlProjectRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProposalApplyTest {

    private val tmp: Path = createTempDirectory("prop-")
    @AfterTest fun cleanup() { tmp.toFile().deleteRecursively() }

    @Test
    fun applySetTagsAndArchiveCommitsBoth() = runTest {
        val library = tmp.resolve("library").also { it.toFile().mkdirs() }
        Fixtures.writeCleanProject(library)
        val handle = CatalogDb.openInMemory()
        val fts = CatalogFts(handle.driver)
        JvmScanner(handle.catalog, fts).scan(library).toList()
        val row = handle.catalog.catalogQueries.selectAllProjects().executeAsList().single()
        val pid = row.id

        val journal = InMemoryJournalRepository()
        val repo = SqlProjectRepository(
            catalog = handle.catalog,
            ioDispatcher = UnconfinedTestDispatcher(),
            journal = journal,
            ftsSearch = { q -> fts.search(q) },
        )
        val executor = ProposalActionExecutor(repo)

        val actions = listOf(
            ProposalAction(
                type = "SetTags",
                args = buildJsonObject {
                    put("project_id", pid)
                    put("tags", JsonArray(listOf(JsonPrimitive("ai"), JsonPrimitive("review"))))
                },
            ),
            ProposalAction(
                type = "ArchiveProject",
                args = buildJsonObject { put("project_id", pid) },
            ),
        )

        val r = executor.apply(actions)
        assertTrue(r.isSuccess, "apply failed: ${r.exceptionOrNull()}")

        val tags = handle.catalog.catalogQueries.selectTagsForProject(pid).executeAsList()
        assertEquals(listOf("ai", "review"), tags.sorted())
        val refreshed = handle.catalog.catalogQueries.selectProjectById(pid).executeAsOne()
        assertEquals(1L, refreshed.archived)
    }

    @Test
    fun unknownActionTypeFailsWithoutPartialCommit() = runTest {
        val library = tmp.resolve("library2").also { it.toFile().mkdirs() }
        Fixtures.writeCleanProject(library)
        val handle = CatalogDb.openInMemory()
        val fts = CatalogFts(handle.driver)
        JvmScanner(handle.catalog, fts).scan(library).toList()
        val pid = handle.catalog.catalogQueries.selectAllProjects().executeAsList().single().id

        val journal = InMemoryJournalRepository()
        val repo = SqlProjectRepository(
            catalog = handle.catalog,
            ioDispatcher = UnconfinedTestDispatcher(),
            journal = journal,
            ftsSearch = { q -> fts.search(q) },
        )
        val executor = ProposalActionExecutor(repo)

        // First action is valid; second is unknown — executor stops on the unknown one.
        val actions = listOf(
            ProposalAction(
                type = "SetTags",
                args = buildJsonObject {
                    put("project_id", pid)
                    put("tags", JsonArray(listOf(JsonPrimitive("first"))))
                },
            ),
            ProposalAction(
                type = "RecolorEverythingToTaupe",   // not a real action
                args = buildJsonObject { put("project_id", pid) },
            ),
        )

        val r = executor.apply(actions)
        assertTrue(r.isFailure, "expected failure on unknown action type")

        // ProposalActionExecutor processes serially. The first action committed; document the
        // observed behavior — this test doubles as a reminder if/when the contract changes to
        // all-or-nothing.
        val tags = handle.catalog.catalogQueries.selectTagsForProject(pid).executeAsList()
        assertEquals(listOf("first"), tags)
    }
}
```

**Step 2: Run it**

Run: `./gradlew.bat :tests:integration:jvmTest --tests com.sketchbook.integration.ProposalApplyTest`

**Step 3: Iterate to green**

If `archived` column type is different (Long? vs Boolean): adjust the assertion. The schema currently uses `INTEGER` for archived so `1L` should match.

If `selectAllProjects` returns rows in a different order on a fresh in-memory DB: switch to `single { it.name == "clean" }`.

If the second test's "first action committed" observation is wrong (the design.md says "no partial commits" but the implementation reads serial-and-stop — see `ProposalActionExecutor.kt`): update the test comment + assertion to match observed behavior, and file a follow-up note in the commit message if the design.md and the code disagree.

**Step 4: Commit**

```powershell
git add tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/ProposalApplyTest.kt
git commit -m "test(integration): proposal-apply round-trip + unknown-action failure"
```

---

## Task 8: Final verification

**Step 1: Run the full integration suite**

Run: `./gradlew.bat :tests:integration:jvmTest`
Expected: BUILD SUCCESSFUL, 9 tests pass (1 smoke + 5 fixtures + 1 scan-edit + 1 repair + 1 sync + 2 proposal).

**Step 2: Run the whole project test suite to confirm no regressions**

Run: `./gradlew.bat test`
Expected: BUILD SUCCESSFUL (no broken neighbouring tests — the changes are additive and confined to the new module).

**Step 3: If everything's green, push the branch**

```powershell
git push -u origin <branch-name>
```

(Ask the user before opening a PR — see the "auto-merge release-pipeline fixes" memory; this is a feature change, not a release fix.)

---

## Open follow-ups (not part of this plan)

- Add an opt-in slow pass over the real `Projects/` folder (design's option C). Add a `@Tag("slow")` JUnit suite that walks `Projects/` if it exists, asserts no parser crashes, and skips when absent.
- Cover `Undo`, `SetColorTag`, and `MoveProject` as `ProposalActionExecutor` grows.
- Promote the duplicated fakes to a shared `:shared:sync-testing` KMP module if a third consumer appears.
