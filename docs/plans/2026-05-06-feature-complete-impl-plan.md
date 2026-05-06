# Sketchbook v1 Feature-Complete Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. **The user has standing guidance: no per-PR checkpoints — drive through PRs D → E → F → G in order, opening + merging the PR at the end of each PR's tasks.**

**Goal:** Take the Kotlin/Compose desktop from "every screen reads from the catalog" (PR-C) to "every advertised feature actually works" — no in-memory placeholders, no stubs, no write-only paths.

**Architecture:** Four sequential PRs (D, E, F, G) per the [design doc](2026-05-06-feature-complete-design.md). PR-D is small glue (settings persistence + proposal-action dispatch). PR-E adds the sample corpus and missing-sample auto-match. PR-F is the sync engine (Materializer + PullPoller wiring + real lease/lock). PR-G is polish (detail-panel binding, journal viewer, dedup stat).

**Tech Stack:** Kotlin 2.3.21, Compose Multiplatform 1.11.0-rc01, SQLDelight 2.3.2 (FTS5), kotlinx-serialization-json, Ktor 3.4.3, GCS via existing `DirectGcsBackend`, Metro DI 1.0.0.

**Conventions for every task:**
- Run from repo root: `./gradlew.bat <task>` (Windows) — Linux/Mac use `./gradlew`.
- After each task, run the affected module's `:jvmTest` and `:compileKotlinJvm`.
- Commits per task. PR opens after the last task in a phase and merges via `gh pr merge <N> --squash --admin` (per `feedback_local_build_authority` memory: local build pass = merge gate).
- "Modify: file:Lstart-Lend" line ranges may have shifted slightly — find the unambiguous neighborhood by surrounding code, not numeric line.

**Existing facts the executor must know:**
- `ProjectRepository.archive(id, archived)` and `ProjectRepository.setTags(id, tags)` already exist and write journal entries.
- `ActionRecord` sealed interface in `:shared:actions/Action.kt` has `ArchiveProject`, `SetTags`, `RenameProject`, etc. — these are the wire format, not the proposal action format. **Don't conflate them with `ProposalAction`** (which is `(type: String, args: JsonObject)` in `ProposalsRepository.kt`).
- `SqlProposalsRepository.observe()` (post-PR-C) emits proposals with `action.type == "ArchiveProject"` and `args.project_id` (Long). v1 only generates this one kind.
- `SnapshotPipeline` (in `:shared:sync`) handles upload + manifest CAS. Its inverse — laydown — does **not** exist yet; that's PR-F.
- `PullPoller` exists at `shared/sync/.../PullPoller.kt` and is unwired.
- `JvmScanner` runs the project walk; sample-corpus scan needs to follow it.
- `samples` table exists in `Catalog.sq` with **zero queries against it** — they're added in PR-E.
- The desktop graph (`DesktopAppGraph`) is the wire-up site; it lives at `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt`.

---

## PR-D: Settings persistence + proposal execution

**Goal:** Settings survive a restart; approving a proposal mutates the catalog.

**Branch:** `pr-D-settings-and-proposal-exec`

### Task D.1: Add `kotlinx.serialization` to `:app-desktop` (if not already)

**Files:** Modify `app-desktop/build.gradle.kts` (jvmMain dependencies block)

Verify with `grep "kotlinx.serialization" app-desktop/build.gradle.kts`. If missing, add `implementation(libs.kotlinx.serialization.json)` under `jvmMain.dependencies`.

Commit only if changed: `chore(desktop): kotlinx-serialization for settings persistence`.

### Task D.2: Implement `PreferencesSettingsRepository`

**Files:** Create `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/repo/PreferencesSettingsRepository.kt`

Use `java.util.prefs.Preferences.userNodeForPackage(SettingsRepository::class.java)`. Schema (string keys, all values string-encoded):

- `library_roots_v1` → JSON array. Each element is `{"kind":"projects|user_samples|external","path":..., "alias":?, "external_kind":?}`.
- `cloud_credential_json` → raw JSON string or null.
- `cloud_bucket` → bucket name or null.
- `self_contained_uuids_v1` → JSON array of UUID strings.
- `cache_max_bytes` → long-as-string. `cache_lru_enabled` → "true"|"false".

Constructor takes the `Preferences` node + an `ioDispatcher` (for the `setX` suspends — Preferences I/O blocks). Hold an internal `MutableStateFlow<Settings>` seeded from `read()` at construction. Each `setX` mutates the prefs node, calls `node.flush()`, then updates the flow.

Provide a `read()` private function that handles missing keys → defaults (mirrors `Settings(...)` defaults in `SettingsRepository.kt`). `cloudConfigured` is derived: `credentialJson != null`.

`upsertRoot` / `removeRoot` need atomic read-modify-write of the JSON list. Take a private `Mutex`.

Implementation hints:
- `kotlinx.serialization.json.Json` instance with `ignoreUnknownKeys = true`, `encodeDefaults = false`.
- Roots serialize via a small `@Serializable data class StoredRoot(...)` mapping to/from `LibraryRoot`. Don't try to derive `@Serializable` directly on `LibraryRoot` (it's defined in `:shared:repository` and we don't want that module to gain a serialization dep).

No public test for this task — covered by D.3.

Commit: `feat(desktop): preferences-backed settings repository`.

### Task D.3: Test `PreferencesSettingsRepository`

**Files:** Create `app-desktop/src/jvmTest/kotlin/com/sketchbook/desktop/repo/PreferencesSettingsRepositoryTest.kt`

Use `java.util.prefs.AbstractPreferences` in-memory? No — easier: use `Preferences.userRoot().node("sketchbook-test-${UUID.randomUUID()}")` and `removeNode()` in `@AfterTest`.

Tests (each ~20 lines):

1. `roundtripsLibraryRoots()` — upsert `Projects("/a")` + `External("/b", alias="splice", kind=Splice)`, build a *second* repo over the same node, assert `observe().first().libraryRoots == [original]`.
2. `roundtripsCredentialAndBucket()` — set both, rebuild, assert `cloudReady == true` + values match.
3. `roundtripsSelfContainedSet()` — toggle a UUID on then off, rebuild, assert empty.
4. `removeRootDropsByPath()` — upsert two roots, remove one, rebuild, assert one remains.

Run: `./gradlew.bat :app-desktop:jvmTest --tests "*.PreferencesSettingsRepositoryTest"`.

Commit (squashed with D.2 if convenient).

### Task D.4: Wire `PreferencesSettingsRepository` in `DesktopAppGraph`

**Files:** Modify `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt`

Replace `provideSettingsRepository(): SettingsRepository = InMemorySettingsRepository()` with a binding that takes no deps but constructs `PreferencesSettingsRepository(Preferences.userNodeForPackage(SettingsRepository::class.java), Dispatchers.IO)`. Delete `InMemorySettingsRepository.kt` (it's unused after this).

Run `./gradlew.bat :app-desktop:compileKotlinJvm`. Smoke check: launch app once, add a library root, quit, relaunch, confirm root persists in Settings screen.

Commit: `feat(desktop): persist settings via java.util.prefs`.

### Task D.5: Add `:shared:actions` proposal-action executor

**Files:** Create `shared/actions/src/commonMain/kotlin/com/sketchbook/actions/ProposalActionExecutor.kt`

```kotlin
package com.sketchbook.actions

import com.sketchbook.core.ProjectId
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.ProposalAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Maps [ProposalAction.type] strings to mutations on [ProjectRepository]. Approving a proposal
 * is a sequence of these — failing the *first* action aborts the rest and the proposal is left
 * as Pending so the user can retry.
 *
 * Today supports only "ArchiveProject"; new types add to the when-branch with no holder change.
 */
class ProposalActionExecutor(private val projects: ProjectRepository) {

    suspend fun apply(actions: List<ProposalAction>): Result<Unit> {
        for (a in actions) {
            val r: Result<*> = when (a.type) {
                "ArchiveProject" -> projects.archive(ProjectId(a.args.projectIdLong()), archived = true)
                "SetTags" -> {
                    val id = ProjectId(a.args.projectIdLong())
                    val tags = (a.args["tags"] as? kotlinx.serialization.json.JsonArray)
                        ?.map { it.jsonPrimitive.content } ?: emptyList()
                    projects.setTags(id, tags)
                }
                else -> Result.failure(IllegalArgumentException("unknown proposal action ${a.type}"))
            }
            if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
        }
        return Result.success(Unit)
    }

    private fun JsonObject.projectIdLong(): Long =
        this["project_id"]?.jsonPrimitive?.long
            ?: error("ProposalAction.args missing project_id")
}
```

Commit: `feat(actions): proposal action executor (ArchiveProject + SetTags)`.

### Task D.6: Test `ProposalActionExecutor`

**Files:** Create `shared/actions/src/commonTest/kotlin/com/sketchbook/actions/ProposalActionExecutorTest.kt`

Fake `ProjectRepository` (record `archive` calls). Tests:

1. `archiveActionDispatchesToRepoArchive()` — single ArchiveProject action with `project_id=7` → fake records `archive(ProjectId(7), true)`.
2. `unknownActionTypeFailsWithoutPartialEffects()` — list of `[ArchiveProject(1), Bogus]` → fake records archive(1) **then** result is Failure (subsequent actions aren't dispatched, but we're already past the archive — that's fine; the *first* failure aborts).

Run: `./gradlew.bat :shared:actions:jvmTest`.

Commit (squashed with D.5 OK).

### Task D.7: Route approve through executor in holder

**Files:** Modify `shared/feature-proposals/src/commonMain/kotlin/com/sketchbook/featureproposals/ProposalsStateHolder.kt`

Add `executor: ProposalActionExecutor?` (nullable for backward compat with existing tests that pass none) as a constructor param. In `Approve`:

```kotlin
is Intent.Approve -> scope.launch {
    // 1. Look up the live proposal so we have its actions list.
    val proposal = state.value.pending.firstOrNull { it.proposalId == intent.proposalId }
        ?: state.value.resolved.firstOrNull { it.proposalId == intent.proposalId }
    if (proposal == null) {
        _effects.tryEmit(Effect.Failed(intent.proposalId, "proposal not found"))
        return@launch
    }
    // 2. Apply actions first. Skip on failure so we don't ack a no-op.
    val applied = executor?.apply(proposal.actions) ?: Result.success(Unit)
    if (applied.isFailure) {
        _effects.tryEmit(Effect.Failed(intent.proposalId, applied.exceptionOrNull()?.message ?: "apply failed"))
        return@launch
    }
    // 3. Now record the ack.
    val r = repository.approve(intent.proposalId)
    if (r.isSuccess) _effects.tryEmit(Effect.Approved(intent.proposalId))
    else _effects.tryEmit(Effect.Failed(intent.proposalId, r.exceptionOrNull()?.message ?: "approve failed"))
}
```

Update `:shared:feature-proposals/build.gradle.kts` jvm dependencies to include `implementation(project(":shared:actions"))`. Common too if the executor is in commonMain.

Existing tests (`ProposalsStateHolderTest`) pass no executor → still work via the null fallback.

Run: `./gradlew.bat :shared:feature-proposals:jvmTest`.

Commit: `feat(proposals): execute proposal actions before recording ack`.

### Task D.8: Wire executor in `DesktopAppGraph`

**Files:** Modify `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt` and `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt`

Add a `@Provides` for `ProposalActionExecutor` taking `ProjectRepository`. In `RootContent.kt`, the `ProposalsStateHolder(graph.proposalsRepository, graph.appScope)` line — pass `executor = graph.proposalActionExecutor` (add the accessor on the graph interface).

Add `app-desktop/build.gradle.kts` dep on `:shared:actions` if not already present.

Run `./gradlew.bat :app-desktop:compileKotlinJvm`. Smoke: open Proposals, approve one, observe project disappears from Projects list.

Commit: `feat(desktop): wire proposal action executor`.

### Task D.9: PR-D — open + merge

```bash
git push -u origin pr-D-settings-and-proposal-exec
gh pr create --title "PR-D: persist settings + execute proposal actions" --body "..."
./gradlew.bat :shared:repository:jvmTest :shared:feature-proposals:jvmTest :shared:actions:jvmTest :app-desktop:jvmTest :app-desktop:compileKotlinJvm
gh pr merge <N> --squash --admin
```

Use the design doc + this plan to write the PR body summary. Test plan checkboxes: settings round-trip, proposal-approve mutates catalog.

---

## PR-E: Sample corpus + missing-sample auto-match

**Goal:** Scanner populates the `samples` table from UserSamples roots; missing-sample findings carry candidates; user can apply a match.

**Branch:** `pr-E-sample-corpus`

### Task E.1: Add SQL queries for the `samples` corpus table

**Files:** Modify `shared/catalog/src/commonMain/sqldelight/com/sketchbook/catalog/db/Catalog.sq` (append to the queries section)

```sql
-- samples corpus
upsertSample:
INSERT OR REPLACE INTO samples (path, filename, size_bytes, mtime, parent_dir)
VALUES (:path, :filename, :size_bytes, :mtime, :parent_dir);

deleteSamplesUnder:
DELETE FROM samples WHERE parent_dir LIKE :parent_glob;

selectSamplesByFilenameAndSize:
SELECT * FROM samples WHERE filename = :filename AND size_bytes = :size_bytes
ORDER BY mtime DESC LIMIT 5;

selectSamplesByFilename:
SELECT * FROM samples WHERE filename = :filename
ORDER BY mtime DESC LIMIT 5;

countSamples:
SELECT COUNT(*) FROM samples;
```

Run `./gradlew.bat :shared:catalog:generateCommonMainCatalogInterface` to regenerate. Commit: `feat(catalog): samples corpus queries`.

### Task E.2: Implement `JvmSampleScanner`

**Files:** Create `shared/catalog/src/jvmMain/kotlin/com/sketchbook/catalog/JvmSampleScanner.kt`

```kotlin
class JvmSampleScanner(
    private val catalog: Catalog,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Walk [rootPath] (typically a `LibraryRoot.UserSamples`), upserting each audio file into
     * the `samples` table. Idempotent — re-scanning replaces stale rows by path.
     *
     * Audio extensions: wav/aif/aiff/mp3/flac/ogg/m4a — match Python's `_AUDIO_EXTS`.
     */
    suspend fun scan(rootPath: String, onProgress: (done: Int, total: Int?) -> Unit = { _, _ -> }):
        Int = withContext(ioDispatcher) { /* walk + chunk-insert in catalog.transaction */ }
}

private val AUDIO_EXTS = setOf("wav", "aif", "aiff", "mp3", "flac", "ogg", "m4a")
```

Walk via `Files.walk`, batch 200 inserts per transaction, return total count. Skip dotfiles. Don't hash.

Test in `shared/catalog/src/jvmTest/kotlin/com/sketchbook/catalog/JvmSampleScannerTest.kt`: create `@TempDir` with three `.wav`s + a `.txt`, scan, assert table has 3 rows.

Commit: `feat(catalog): jvm sample-corpus scanner`.

### Task E.3: Run sample-scanner after project scan

**Files:** Modify `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt` (the `runScan` / `LaunchedEffect(settingsHolder)` block) and possibly `app-desktop/.../desktop/Scan.kt` if a separate scan helper exists.

After the existing project scan completes, iterate `settingsState.libraryRoots.filterIsInstance<LibraryRoot.UserSamples>()` and call `sampleScanner.scan(root.path)`. Add `JvmSampleScanner` to the graph (`@Provides @SingleIn(AppScope::class)`).

Don't add this to scan progress UI for v1 — sample scanning is fast (no parsing) and runs in the background; surfacing progress would be noise. Just log.

Run `./gradlew.bat :app-desktop:compileKotlinJvm`. Commit: `feat(desktop): scan UserSamples roots into corpus`.

### Task E.4: Auto-match in `SqlRepairRepository.observeFindings`

**Files:** Modify `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/SqlRepairRepository.kt`

In the `combine { mac, miss, _ -> }` block, before constructing `MissingSampleFinding`, look up candidates per row. Algorithm:

```kotlin
val filename = row.sample_path.substringAfterLast('/').substringAfterLast('\\')
val sized = withContext(ioDispatcher) {
    catalog.catalogQueries.selectSamplesByFilenameAndSize(filename = filename, size_bytes = ???).executeAsList()
}
```

**Wrinkle:** the missing-sample row in `project_samples` doesn't carry the original size — that came from the `.als`. The Python parity uses filename-only when size is unknown, filename+size when known. Easiest: query filename+size only when `project_samples.size_bytes` is non-null; else fall back to filename-only. Update `selectMissingSamples` to also project `ps.size_bytes AS sample_size`.

```kotlin
val candidates = if (sized.isNotEmpty()) sized else
    catalog.catalogQueries.selectSamplesByFilename(filename).executeAsList()

val candidateModels = candidates.take(5).map {
    SampleCandidate(path = it.path, filename = it.filename, sizeBytes = it.size_bytes)
}
val autoMatch = candidateModels.firstOrNull()?.takeIf { sized.isNotEmpty() && candidates.size == 1 }
```

(Auto-match only when filename+size matches **exactly one** sample — too risky otherwise.)

Update the docstring at the top of `SqlRepairRepository.kt` to remove the "auto-match suggestions ... not yet computed" caveat.

Commit: `feat(repair): populate missing-sample candidates from corpus`.

### Task E.5: Apply-match query + repository method

**Files:** Modify `Catalog.sq`:

```sql
-- The user has decided this missing-sample reference points at this candidate. The .als itself
-- will be rewritten when the writer ships; for now we record the mapping in project_samples and
-- clear the missing flag. is_missing flips to 0; sample_path is rewritten to the candidate path
-- so subsequent scans see it as found.
applyMissingSampleMatch:
UPDATE project_samples
SET sample_path = :new_path, is_missing = 0
WHERE project_id = :project_id AND sample_path = :old_path;
```

Modify `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/RepairRepository.kt`:

```kotlin
/**
 * Map a missing sample to a candidate the user picked. The .als isn't rewritten here — the
 * catalog records the decision so a future "rewrite .als" pass can act on the ledger.
 */
suspend fun applyMissingSampleMatch(
    projectId: ProjectId,
    missingPath: String,
    candidatePath: String,
): Result<Unit>
```

Implement in `SqlRepairRepository` (transactional UPDATE, then bump `ackTick`). Also bump `ackTick` so the findings flow re-emits.

Commit: `feat(repair): applyMissingSampleMatch persists user decision`.

### Task E.6: Wire apply-match into NeedsAttention UI

**Files:** Modify `shared/feature-needs-attention/src/commonMain/kotlin/com/sketchbook/featureneedsattention/NeedsAttentionStateHolder.kt` and `NeedsAttentionScreen.kt`

Holder: add intent `Intent.ApplyMatch(projectId, missingPath, candidatePath)`; dispatch via `repository.applyMissingSampleMatch`. Effect `Effect.MatchApplied`.

Screen: each missing-sample row already shows the path; add a small chip rendering `state.row.autoMatch?.path` when present, with an "Apply" button that dispatches `ApplyMatch`. If only `candidates` is non-empty (no auto-match), show "N possible matches" with a secondary affordance to expand and pick one. Per `feedback_layer_dont_redesign` and `feedback_color_restraint` memories: keep this as a small inline accent on the existing row, not a new shelf or Material variant.

Run `./gradlew.bat :shared:feature-needs-attention:jvmTest :app-desktop:compileKotlinJvm`.

Commit: `feat(needs-attention): one-click apply-match for missing samples`.

### Task E.7: PR-E — open + merge

```bash
git push -u origin pr-E-sample-corpus
gh pr create --title "PR-E: sample corpus + missing-sample auto-match" --body "..."
./gradlew.bat :shared:catalog:jvmTest :shared:repository:jvmTest :shared:feature-needs-attention:jvmTest :app-desktop:compileKotlinJvm
gh pr merge <N> --squash --admin
```

---

## PR-F: Sync engine completion

**Goal:** `materializeAt` lays down files; `PullPoller` runs; lease/lock is real.

**Branch:** `pr-F-sync-engine`

This is the largest PR. Materializer first (Timeline restore), pull second, lease last.

### Task F.1: Add `Materializer` class in `:shared:sync`

**Files:** Create `shared/sync/src/commonMain/kotlin/com/sketchbook/sync/Materializer.kt`

```kotlin
class Materializer(
    private val cloud: CloudBackend,
    private val tree: WorkingTree,
    private val blobCache: BlobCache, // new interface, see F.2
    private val clock: Clock = Clock.System,
) {
    suspend fun materialize(uuid: ProjectUuid, rev: SnapshotRev): Result<Unit> {
        // 1. Load manifest.
        val manifest = runCatching { cloud.readManifest(uuid, rev) }.getOrElse { return Result.failure(it) }

        // 2. For each file in manifest: ensure blob is cached, copy to a sibling temp path under
        //    tree.root, then atomic-rename over the destination once *all* writes succeed.
        //    Failure mid-way = roll back temp files; never leave the working tree partial.
        val temps = mutableListOf<Pair<Path, Path>>() // (tempPath, finalPath)
        try {
            for ((relPath, mfile) in manifest.files) {
                val finalPath = tree.resolve(relPath)
                val tempPath = finalPath.resolveSibling("${finalPath.name}.materialize-${rev.value}")
                val source = blobCache.getOrFetch(mfile.hash, manifest.blobScope)
                writeAtomic(source, tempPath)
                temps += tempPath to finalPath
            }
            // 3. Commit: rename all temps to finals.
            for ((temp, final) in temps) {
                tree.atomicMove(temp, final)
            }
            return Result.success(Unit)
        } catch (e: Throwable) {
            // Roll back any leftover temps; leave finals untouched.
            for ((temp, _) in temps) tree.deleteIfExists(temp)
            return Result.failure(e)
        }
    }
}
```

`writeAtomic` is a small platform-aware helper. `WorkingTree` already has `resolve()`; add `atomicMove(from, to)` and `deleteIfExists(path)` to the interface (impl in `JvmWorkingTree`: `Files.move(temp, final, ATOMIC_MOVE, REPLACE_EXISTING)`).

Commit: `feat(sync): Materializer with atomic per-file laydown`.

### Task F.2: Add `BlobCache` abstraction

**Files:** Create `shared/sync/src/commonMain/kotlin/com/sketchbook/sync/BlobCache.kt`, JVM impl `shared/sync/src/jvmMain/kotlin/com/sketchbook/sync/JvmBlobCache.kt`

Common interface:

```kotlin
interface BlobCache {
    /** Returns a [RawSource] over the cached blob, fetching from cloud + caching if absent. */
    suspend fun getOrFetch(hash: BlobHash, scope: BlobScope): RawSource
}
```

JVM impl: backed by a directory under `<dataDir>/blob-cache/<hash[0..1]>/<hash>`. On miss, calls `cloud.getBlob(hash, scope)`, streams to a temp file, rename. Updates `blob_cache` SQL table with `(hash, size, last_used)`. Honor `BlobCacheSettings.maxSizeBytes` via LRU eviction when over budget (only run eviction *after* a successful insert to keep hot path fast).

Test: `JvmBlobCacheTest` — fake `CloudBackend.getBlob()` returns a known byte stream; `getOrFetch(hash)` writes it; second call returns the cached copy without hitting cloud.

Commit: `feat(sync): JvmBlobCache with LRU eviction`.

### Task F.3: Wire `Materializer` into `SqlSnapshotRepository.materializeAt`

**Files:** Modify `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/SqlSnapshotRepository.kt`

Currently:
```kotlin
override suspend fun materializeAt(uuid: ProjectUuid, rev: SnapshotRev): Result<Unit> = Result.success(Unit)
```

Replace with a delegate to a `Materializer` provided at construction (the SQL repo gets a new ctor param `materialize: suspend (ProjectUuid, SnapshotRev) -> Result<Unit>` — keep it a function type so common-main doesn't have to depend on `:shared:sync`).

After successful laydown, write a synthetic snapshot row with `kind="auto"`, `label="rewind to rev N"`, `parent_rev = rev`, `rev = currentMax+1`, so the Timeline shows the rewind as a new entry.

Modify `DesktopAppGraph` to provide a `Materializer` and wire it into `SqlSnapshotRepository`'s constructor.

Commit: `feat(snapshot): materializeAt invokes Materializer`.

### Task F.4: Add "Rewind to this snapshot" UI affordance

**Files:** Modify `shared/feature-timeline/src/commonMain/kotlin/com/sketchbook/featuretimeline/TimelineStateHolder.kt` and `TimelineScreen.kt`

Add intent `Intent.Rewind(rev)`, dispatch via `snapshots.materializeAt(uuid, rev)`. Effect `Effect.Rewound(rev)` / `Effect.RewindFailed(reason)`.

Screen: per-row "Rewind" button (small ghost button) opens a confirmation dialog ("This will overwrite local files with the version from <date>. Continue?") then dispatches.

Per memory `feedback_layer_dont_redesign`: small button on existing row, not a redesign of the timeline.

Commit: `feat(timeline): Rewind-to-snapshot affordance`.

### Task F.5: Wire `PullPoller` in `DesktopAppGraph`

**Files:** Modify `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt` and `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/repo/GcsSyncQueue.kt`

In the `GcsSyncQueue.start()` block (or similar lifecycle entry point), launch a coroutine on `appScope`:

```kotlin
appScope.launch {
    syncStateStore.observeAll().collectLatest { syncStates ->
        // For each project that's been pushed at least once (has a sync_state row),
        // subscribe to PullPoller.
        for (s in syncStates) {
            launch {
                pullPoller.subscribe(ProjectUuid(s.project_uuid), startAfter = SnapshotRev(s.local_rev))
                    .collect { newSnapshot ->
                        // Snapshot is already recorded by PullPoller. Update sync_state so
                        // cloud_head_rev tracks the new head, and surface it in queue state.
                        syncStateStore.markCloudHead(newSnapshot.projectUuid, newSnapshot.rev)
                    }
            }
        }
    }
}
```

Add `markCloudHead(uuid, rev)` to `SyncStateStore`. Wire `PullPoller(cloud = ..., snapshots = snapshotRepository)` provider. Disable polling when settings `cloudReady == false` (skip the launch).

Commit: `feat(desktop): wire PullPoller with 30s cadence`.

### Task F.6: Surface "remote ahead" in `SyncQueueState` + UI

**Files:** Modify `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/SyncQueue.kt` (the `ProjectSyncState` enum or sibling type) — add `RemoteAhead` state. Update `GcsSyncQueue.snapshotFor(id)` to return `RemoteAhead` when `cloud_head_rev > local_rev`.

Render the new state in `SyncPill` (RootContent.kt) — reuse an existing tint (per `feedback_color_restraint`, don't add a new one; `tintBlue` works).

Commit: `feat(sync-state): RemoteAhead pill when cloud is ahead`.

### Task F.7: Real `LeaseLockState`-backed `LockRepository`

**Files:** Create `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/repo/LeasedLockRepository.kt` (replace `InMemoryLockRepository`)

```kotlin
class LeasedLockRepository(
    private val cloud: CloudBackend,
    private val syncStateStore: SyncStateStore,
    private val hostId: String,
    private val hostName: String,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) : LockRepository {
    // Per-uuid LeaseLockState (from :shared:sync) seeded from sync_state row on observe().
    // Heartbeat: every 60s while we hold a lease, refreshLock(); on failure, fall back to Stale.
    // observe() emits LockStatus by mapping LeaseLockState → LockStatus.
}
```

`forceTake(uuid)` writes a journal entry (`Action.ForceTakeLock` — add to `Action.kt` with `@SerialName("ForceTakeLock")`) and CAS-acquires the lock with `expectedHead = currentGeneration` (read from sync_state). Heartbeat coroutine cancels on `LockStatus.Free`.

Test in `commonTest` (uses fake CloudBackend): acquire → status flips Ours; tick 60s → refresh called; lose CAS → status drops to Stale.

Commit: `feat(desktop): real lease-locks with heartbeat`.

### Task F.8: Surface `Conflict` state when manifest CAS fails on push

**Files:** Modify `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/repo/GcsSyncQueue.kt`

In `pushNowById`, if `appendManifestHead` returns `Result.failure(SketchbookError.Conflict)`, set the per-project state to `Conflict` and emit. UI already renders `Conflict` pill. Don't auto-merge — write a journal entry `Action.PushConflict(projectId, ourRev, theirRev)` (add to Action.kt) and surface a "remote diverged — pull and re-push" inline message on detail panel.

Commit: `feat(sync): surface Conflict on push CAS failure`.

### Task F.9: Drop `InMemoryLockRepository`

**Files:** Delete `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/repo/InMemoryLockRepository.kt`. Modify `DesktopAppGraph` to provide `LeasedLockRepository` instead.

Commit: `chore(desktop): drop in-memory lock repo`.

### Task F.10: PR-F — open + merge

```bash
git push -u origin pr-F-sync-engine
gh pr create --title "PR-F: sync engine — Materializer, PullPoller, lease locks" --body "..."
./gradlew.bat :shared:sync:jvmTest :shared:repository:jvmTest :shared:feature-timeline:jvmTest :app-desktop:jvmTest :app-desktop:compileKotlinJvm
gh pr merge <N> --squash --admin
```

---

## PR-G: Polish

**Goal:** Detail panel reads parser data; journal viewer screen; Timeline shows new-vs-reused bytes.

**Branch:** `pr-G-polish`

### Task G.1: Bind parser data into Detail panel tabs

**Files:** Modify `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt` (the `DetailTracksTab`, `DetailSamplesTab`, `DetailPluginsTab` composables)

These already render data when supplied — just ensure the holder (`ProjectDetailStateHolder`) loads it. Verify `state.samples` and `state.plugins` populate from `ProjectRepository`. If `loadDetail()` doesn't already query plugins/samples, extend it. The placeholder text "Wire up via repository in PR-18" must be gone.

Commit: `feat(detail): bind real parser data to Tracks/Samples/Plugins tabs`.

### Task G.2: New `JournalScreen`

**Files:** Create `shared/feature-journal/` module (mirror existing feature module shape — see `shared/feature-needs-attention` as template). Create `JournalStateHolder.kt` + `JournalScreen.kt`. Read `JournalRepository.observe()` and render a chronological list with `actor · type · project · timestamp`.

Screens are read-only. Tap a row → navigate to project detail. Per `feedback_no_unnecessary_libs`: plain StateFlow, no MVI lib.

Add `Screen.Journal` to `RootContent.kt`'s `Screen` sealed hierarchy. Add a sidebar entry below "Settings". No badge for v1.

Commit: `feat(journal): read-only journal viewer screen`.

### Task G.3: Track new-vs-reused bytes in `SnapshotPipeline`

**Files:** Modify `shared/sync/src/commonMain/kotlin/com/sketchbook/sync/SnapshotPipeline.kt`

In the "upload blobs" loop, sum bytes for blobs where `headBlob` returned `false` (we actually uploaded vs. skipped because already present). Pass the sum into the resulting `Manifest`'s stats or as a separate field on `SnapshotProgress.Saved`.

Modify `SqlSnapshotRepository.recordSnapshot` to accept the figure and write it into `snapshots.new_bytes` (currently hardcoded `0L`). Update the caller(s) in `GcsSyncQueue`.

Commit: `feat(snapshot): track new vs reused bytes per snapshot`.

### Task G.4: Surface dedup savings on Timeline rows

**Files:** Modify `shared/feature-timeline/src/commonMain/kotlin/com/sketchbook/featuretimeline/TimelineScreen.kt`

Per row subtitle: existing `"${kind} · ${fileCount} files"` becomes `"${kind} · ${fileCount} files · ${formatBytes(newBytes)} new of ${formatBytes(totalBytes)}"`. Add `newBytes` to the `Snapshot` domain class (`:shared:core/Snapshot.kt`).

Commit: `feat(timeline): show new-vs-reused bytes on rev rows`.

### Task G.5: PR-G — open + merge

```bash
git push -u origin pr-G-polish
gh pr create --title "PR-G: detail tabs + journal viewer + dedup stats" --body "..."
./gradlew.bat :app-desktop:compileKotlinJvm  # plus all relevant module tests
gh pr merge <N> --squash --admin
```

---

## Final acceptance check

After PR-G merges, run the design doc's acceptance scenarios manually:

1. Quit + relaunch: settings persist. ✓ PR-D
2. Approve archive proposal: project disappears. ✓ PR-D
3. UserSamples scan + missing-sample apply-match: missing-row clears. ✓ PR-E
4. Rewind from Timeline: working tree reverts. ✓ PR-F
5. Two-machine pull: 30s pickup. ✓ PR-F
6. Detail tabs + journal screen + new-vs-reused. ✓ PR-G

Update memory with any new feedback that surfaces during execution. Done.
