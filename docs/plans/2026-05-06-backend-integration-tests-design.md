# Backend integration tests — design

**Date:** 2026-05-06
**Status:** Approved, pending implementation plan

## Goal

Add high-level integration tests that exercise the major backend pipelines —
scan, edit, repair, sync, and proposal apply — across module boundaries with
real disk and a real in-memory SQLite catalog. Today every backend module has
its own unit suite that fakes its neighbours; nothing verifies that the wiring
actually composes.

## Scope

- **In:** Cross-module backend pipelines, real disk + real SQLDelight,
  `FakeCloudBackend` instead of B2/R2.
- **Out:** End-to-end against real cloud (creds, slow, flaky), UI/state-holder
  smoke tests (different layer, different value), and per-module unit tests
  (already covered).

## Module

A new Gradle subproject `:tests:integration`, JVM-only.

```
tests/
  integration/
    build.gradle.kts
    src/jvmTest/kotlin/com/sketchbook/integration/...
    src/jvmTest/resources/fixtures/
```

Dependencies:

- `:shared:catalog` — `CatalogDb`, `JvmScanner`, `JvmSampleScanner`, `CatalogFts`
- `:shared:repository` — `SqlProjectRepository`, `SqlRepairRepository`,
  `SqlProposalsRepository`, `SqlSnapshotRepository`, `InMemoryJournalRepository`
- `:shared:actions` — `ProposalActionExecutor`
- `:shared:sync` — `SnapshotPipeline`, `PullPoller`,
  `testFixtures(project(":shared:sync"))` for the fakes (see below)
- `:shared:sync-io` — `JvmWorkingTree`, `ManifestMaterializer`, `BlobInstaller`,
  `Hasher`
- `:shared:parser-als` — `AlsParser` (used transitively via `JvmScanner`)
- `:shared:cloud` — `CloudBackend` interface

## Test scenarios

Four tests, each one walking a major user-visible workflow:

### 1. `ScanAndEditE2ETest`

- Point `JvmScanner` at the fixtures dir (copied to a `@TempDir` per test).
- Assert catalog rows after scan: parse status, parse error message for the
  parse-fail fixture, `mac_paths_count > 0` for the mac fixture, missing-sample
  count for the missing-samples fixture, plugin/sample child rows, FTS index.
- Through `SqlProjectRepository`:
  - `setTags` — observe persistence + journal entry + new emission on
    `observeProject`.
  - `archive` — project disappears from `observeProjects()`.
  - `rename` — catalog row reflects new name; journal entry recorded.
  - `move` — catalog row reflects new path + parent_dir.
- Verifies the catalog ↔ repository ↔ journal seam end-to-end.

### 2. `RepairWorkflowTest`

- Scan fixtures (mac fixture + missing-samples fixture).
- `JvmSampleScanner` populates the samples corpus from the
  `sample_corpus/` fixture root.
- `SqlRepairRepository.observeFindings` surfaces:
  - one `MacImportFinding` for the mac fixture
  - one `MissingSampleFinding` with an `autoMatch` for the missing fixture
- `applyMissingSampleMatch` rewrites `project_samples.sample_path`, flips
  `is_missing` to 0, the finding drops from the next emission.
- `acknowledgeMacImport` writes a `repair_acks` row, the mac finding drops from
  the next emission.

### 3. `SyncRoundTripTest`

- Two `JvmWorkingTree`s rooted at separate `@TempDir`s — host A and host B —
  on the same `ProjectUuid`. Host A's tree is seeded from the clean fixture.
- One shared `FakeCloudBackend`.
- Host A runs `SnapshotPipeline.run` → assert all blobs uploaded, manifest at
  rev 1, lease released.
- Host B's `PullPoller` sees the new manifest → `ManifestMaterializer` writes
  blobs into host B's tree → byte-for-byte equality with host A.
- Host A modifies one file, runs `SnapshotPipeline` again → assert only the
  changed blob was uploaded (unchanged blob count from `FakeCloudBackend`
  holds steady), new manifest at rev 2 with parent_rev 1.

### 4. `ProposalApplyTest`

- Seed a scanned project.
- Construct a `List<ProposalAction>` covering `SetTags` + `ArchiveProject`.
- `ProposalActionExecutor.apply` succeeds → catalog reflects new tags +
  archived flag; journal has both entries.
- Construct a batch with an unknown action type → `apply` returns
  `Result.failure`, no partial commits visible in the catalog.

## Fixtures

Committed under `tests/integration/src/jvmTest/resources/fixtures/`. Total
target size ~1–2 MB.

| Fixture | Files | Purpose |
|---|---|---|
| `clean/` | `Project.als` (gzipped, valid), `Samples/loop.wav`, `Ableton Project Info/` (empty marker dir) | Happy path — parses ok, no missing samples, no mac paths. |
| `missing_samples/` | `Project.als` referencing two samples, `Samples/found.wav` only | One missing sample → `MissingSampleFinding`. |
| `mac_paths/` | `Project.als` containing Mac-style absolute paths in sample refs | `mac_paths_count > 0` → `MacImportFinding`. |
| `parse_fail.als` | A non-gzipped garbage byte sequence | `AlsParser.parse` throws → `parse_status = "failed"` row. |
| `sample_corpus/` | A few `.wav` files including the one referenced as missing in `missing_samples/Project.als` | Backing corpus for `JvmSampleScanner` so auto-match has a hit. |

Source: trim `.als` bodies from existing small projects in `Projects/` (e.g.
`air Project/`, `ambient_play Project/`). Never copy user-named projects or
content beyond what the parser needs to exercise.

## Build infrastructure

The existing fakes live in `shared/sync/src/commonTest`:

- `FakeCloudBackend.kt`
- `FakeWorkingTree.kt`
- `FixedClock.kt`

`commonTest` isn't visible to other modules. To reuse them:

- Enable the `java-test-fixtures` Gradle plugin on `:shared:sync`.
- Move those three files from `commonTest` to a `testFixtures` source set.
- `:tests:integration` declares `testImplementation(testFixtures(project(":shared:sync")))`.

Existing in-module tests in `shared/sync` keep working — `testFixtures` is
visible to that module's own `commonTest` automatically.

## Determinism

- `kotlinx.coroutines.test.runTest` with `UnconfinedTestDispatcher`.
- All filesystem state under JUnit 5 `@TempDir` per test.
- `CatalogDb.openInMemory()` per test.
- `FixedClock` for the snapshot test.
- No network.

## Non-goals

- Performance benchmarks (separate concern).
- Real cloud round-trip (different test layer).
- UI / state-holder smoke tests (different layer; valuable but separate).
- Coverage of every action type (`SetColorTag`, `Undo`, etc. — add as MCP
  expands).

## Next step

Invoke `writing-plans` to produce the step-by-step implementation plan.
