# Metro per-module DI + KMP ViewModel + nav3 lifecycle Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** One PR that (a) moves repositories/services to Metro constructor injection with `@ContributesBinding`, (b) replaces the seven `*StateHolder` classes with `androidx.lifecycle.ViewModel` subclasses scoped per `NavEntry`, (c) adopts `collectAsStateWithLifecycle`, and (d) updates the docs that previously rejected `viewmodel-compose`.

**Architecture:** Phased migration in a single PR. Each phase compiles green at its boundary so the build never has a "half-converted" state. No behavior changes — only wiring. Carve-outs (`SyncQueue`, `LockRepository`, `SnapshotRepository`, `SettingsRepository`) stay manually `@Provides`d in `DesktopAppGraph` for a future `CloudScope` PR.

**Tech Stack:** Kotlin 2.3 + CMP 1.11, Metro 1.0, JetBrains lifecycle KMP fork (`org.jetbrains.androidx.lifecycle:*`), nav3 (already in tree), `metrox-viewmodel-compose`, `kotlinx-coroutines-swing`.

**Predecessor design:** [`2026-05-06-metro-kmp-viewmodel-design.md`](2026-05-06-metro-kmp-viewmodel-design.md). Read §2 (target architecture) and §3.2 (carve-outs) before starting. Driving rule: drive through tasks (per `feedback_no_batch_checkpoints`), but commit at each task boundary.

**Dependency policy reminder:** Per `feedback_no_unnecessary_libs`, every new dep needs justification. The seven new deps are documented in design §1; do not add others without amending the design.

---

## Phase 0 — Preflight

### Task 0.1: Verify clean working tree, branch off `main`

**Steps:**

1. Run `git status` — expect clean tree (the design doc was committed at `431f2e7`).
2. Run `git checkout -b feat/metro-kmp-viewmodel`.
3. Run `git log -1 --oneline` — expect `431f2e7 docs(plans): metro per-module DI + KMP ViewModel + nav3 lifecycle design`.

No commit at this task — branch creation only.

### Task 0.2: Add new dependencies to `gradle/libs.versions.toml`

**Files:**
- Modify: `gradle/libs.versions.toml`

**Step 1: Add version pins under `[versions]`**

Insert after the existing `nav3 = "1.0.0-alpha06"` line:

```toml
# Lifecycle (JetBrains KMP fork — used with nav3 for ViewModel scoping)
lifecycle = "2.10.0"
```

And under `# Coroutines / IO / serialization` block, leave `coroutines = "1.10.2"` as-is (we'll reuse it for `kotlinx-coroutines-swing`).

Under `[versions]` for Metro extensions, leave `metro = "1.0.0"` — the `metrox-viewmodel-compose` artifact tracks it.

**Step 2: Add library entries under `[libraries]`**

After the existing `nav3-ui` entry, insert:

```toml
# Lifecycle (JetBrains KMP fork)
lifecycle-viewmodel = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel", version.ref = "lifecycle" }
lifecycle-viewmodel-compose = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-viewmodel-savedstate = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-savedstate", version.ref = "lifecycle" }
lifecycle-viewmodel-navigation3 = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3", version.ref = "lifecycle" }
lifecycle-runtime-compose = { module = "org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }

# Metro × ViewModel integration
metro-viewmodel-compose = { module = "dev.zacsweers.metro:metrox-viewmodel-compose", version.ref = "metro" }
```

After the existing `kotlinx-coroutines-test` entry, insert:

```toml
kotlinx-coroutines-swing = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-swing", version.ref = "coroutines" }
```

**Step 3: Verify** by running `./gradlew help` — should resolve without "unresolved reference in version catalog" errors.

**Step 4: Commit**

```bash
git add gradle/libs.versions.toml
git commit -m "chore(deps): add lifecycle-viewmodel + metro-viewmodel-compose + coroutines-swing"
```

### Task 0.3: Move `AppScope` from `app-desktop` to `shared/core`

**Files:**
- Create: `shared/core/src/commonMain/kotlin/com/sketchbook/core/AppScope.kt`
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt:227-228` — delete the local `AppScope` declaration, replace with import.

**Step 1: Create the new file**

```kotlin
// shared/core/src/commonMain/kotlin/com/sketchbook/core/AppScope.kt
package com.sketchbook.core

/**
 * Application-lifetime scope marker for Metro bindings. Lives in `shared/core` so any module
 * can mark a class `@SingleIn(AppScope::class)` without depending on `app-desktop`.
 *
 * Future scope candidates (NOT introduced in this PR; documented for context):
 *  - `CloudScope` — child of `AppScope`, lifetime tied to cloud-credential availability.
 *  - `ScreenScope` — per-`NavEntry` scope; currently handled implicitly by `lifecycle-viewmodel-navigation3`.
 */
abstract class AppScope private constructor()
```

**Step 2: Add Metro plugin + runtime to `shared/core`**

Modify `shared/core/build.gradle.kts`. Add `alias(libs.plugins.metro)` to the `plugins { ... }` block, and add `implementation(libs.metro.runtime)` to `commonMain.dependencies`. (See Task 1.1 for the exact diff template; this module is the first instance of the pattern.)

**Step 3: Delete the local `AppScope` from `DesktopAppGraph.kt`**

Find:
```kotlin
/** Application-lifetime scope marker for Metro bindings. */
abstract class AppScope private constructor()
```

Replace by deleting these two lines and adding `import com.sketchbook.core.AppScope` to the imports at the top of the file.

**Step 4: Build**

```bash
./gradlew :shared:core:build :app-desktop:compileKotlinJvm
```

Expected: green.

**Step 5: Commit**

```bash
git add shared/core/src/commonMain/kotlin/com/sketchbook/core/AppScope.kt \
        shared/core/build.gradle.kts \
        app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt
git commit -m "refactor(di): move AppScope to shared/core"
```

---

## Phase 1 — Apply Metro plugin to participating modules

### Task 1.1: Add Metro plugin + runtime to all participating modules

**Files (modify each `build.gradle.kts`):**
- `shared/actions/build.gradle.kts`
- `shared/catalog/build.gradle.kts`
- `shared/repository/build.gradle.kts`
- `shared/feature-projects/build.gradle.kts`
- `shared/feature-project-detail/build.gradle.kts`
- `shared/feature-timeline/build.gradle.kts`
- `shared/feature-proposals/build.gradle.kts`
- `shared/feature-needs-attention/build.gradle.kts`
- `shared/feature-settings/build.gradle.kts`
- `shared/feature-journal/build.gradle.kts`

(`shared/core` was already done in Task 0.3. `shared/cloud`, `shared/sync`, `shared/sync-io`, `shared/parser-als`, `shared/mcp-server`, `shared/ui-shared` are NOT participating in this PR — design §3.2 carve-outs.)

**Step 1: Apply the same change to each file**

Add the plugin alias to the `plugins { ... }` block:

```kotlin
plugins {
    id("kmp-compose")  // or "kmp-test" / "kmp-library" depending on module
    alias(libs.plugins.compose.multiplatform)  // only if this module had it
    alias(libs.plugins.compose.compiler)       // only if this module had it
    alias(libs.plugins.metro)
}
```

Add the runtime dep to `commonMain.dependencies`:

```kotlin
commonMain.dependencies {
    // ... existing deps ...
    implementation(libs.metro.runtime)
}
```

**Step 2: Build each affected module**

```bash
./gradlew :shared:actions:build :shared:catalog:build :shared:repository:build \
          :shared:feature-projects:build :shared:feature-project-detail:build \
          :shared:feature-timeline:build :shared:feature-proposals:build \
          :shared:feature-needs-attention:build :shared:feature-settings:build \
          :shared:feature-journal:build
```

Expected: green (Metro is applied but no annotations yet — should be a no-op).

**Step 3: Commit**

```bash
git add shared/actions/build.gradle.kts shared/catalog/build.gradle.kts \
        shared/repository/build.gradle.kts shared/feature-*/build.gradle.kts
git commit -m "build: apply metro plugin + runtime to participating modules"
```

---

## Phase 2 — Constructor injection on services

### Task 2.1: Convert `SqlProjectRepository` to `@Inject` + `@ContributesBinding`, inline `ftsSearch` lambda

**Files:**
- Modify: `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/SqlProjectRepository.kt`
- Test: `shared/repository/src/jvmTest/kotlin/com/sketchbook/repo/SqlProjectRepositoryTest.kt` (constructor calls)

**Note on `CatalogFts`:** The file currently takes `ftsSearch: (String) -> List<Long>` as a callback to keep the class in `commonMain` (the FTS impl is JVM-only). To inject `CatalogFts` directly we need `shared/repository` to depend on `shared/catalog`. Verify that's already the case (`shared/repository/build.gradle.kts` line 9: `implementation(project(":shared:catalog"))` — yes, it is).

**Step 1: Update the class signature and remove the lambda parameter**

Replace lines 33-41 (the current `class SqlProjectRepository(...)` constructor block):

```kotlin
import com.sketchbook.catalog.CatalogFts  // add to imports
import com.sketchbook.core.AppScope        // add to imports
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class SqlProjectRepository(
    private val catalog: Catalog,
    private val ioDispatcher: CoroutineDispatcher,
    private val journal: JournalRepository,
    private val fts: CatalogFts,
    private val clock: Clock = Clock.System,
) : ProjectRepository {

    private val ftsTrigger = MutableStateFlow(0)

    private fun ftsSearch(query: String): List<Long> = fts.search(query).map { it.value }
    // ... rest of the file stays the same; existing `ftsSearch(q)` calls now route through the
    // private method which delegates to the injected CatalogFts.
```

**Note:** `CatalogFts.search(query)` returns `List<ProjectId>` (or `List<Long>` — verify the return type and adjust the `.map` if needed). If the existing call sites use `ftsSearch(q)` as a function reference, no further change is needed; if they pass it as a constructor arg in tests, see Step 3.

**Step 2: Update existing callers in `DesktopAppGraph`**

In `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt`, the manual `provideProjectRepository` factory is now obsolete because `@ContributesBinding` will provide the binding. Mark it for deletion in Phase 5. For now, the factory still compiles; ignore.

**Step 3: Update the JVM test**

`shared/repository/src/jvmTest/kotlin/com/sketchbook/repo/SqlProjectRepositoryTest.kt` instantiates `SqlProjectRepository` directly. Update its constructor call:

```kotlin
val repo = SqlProjectRepository(
    catalog = catalog,
    ioDispatcher = StandardTestDispatcher(),
    journal = InMemoryJournalRepository(),
    fts = CatalogFts(driver),  // was: ftsSearch = { _ -> emptyList() }
    clock = clock,
)
```

If the test previously stubbed `ftsSearch` with `{ _ -> emptyList() }`, replace with a real `CatalogFts(driver)` (the test already creates a SQLDelight driver). Empty FTS results come naturally from an empty catalog.

**Step 4: Update `app-mcp/Main.kt` constructor call**

`app-mcp/src/main/kotlin/com/sketchbook/mcp/app/Main.kt:38-43` constructs `SqlProjectRepository` manually. Change:

```kotlin
val repository = SqlProjectRepository(
    catalog = handle.catalog,
    ioDispatcher = Dispatchers.IO,
    journal = journal,
    fts = fts,  // was: ftsSearch = { query -> fts.search(query) }
)
```

**Step 5: Build and test**

```bash
./gradlew :shared:repository:build :app-desktop:compileKotlinJvm :app-mcp:build
```

Expected: green. Tests pass.

**Step 6: Commit**

```bash
git add shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/SqlProjectRepository.kt \
        shared/repository/src/jvmTest/kotlin/com/sketchbook/repo/SqlProjectRepositoryTest.kt \
        app-mcp/src/main/kotlin/com/sketchbook/mcp/app/Main.kt
git commit -m "refactor(repo): @Inject SqlProjectRepository, inject CatalogFts directly"
```

### Task 2.2: Convert remaining repositories to `@Inject` + `@ContributesBinding`

**Files:**
- Modify: `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/SqlProposalsRepository.kt`
- Modify: `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/SqlRepairRepository.kt`
- Modify: `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/InMemoryJournalRepository.kt`

(`SqlSnapshotRepository` is **NOT** in this task — it takes a materialize lambda from `SwappableSyncQueue` and stays as a manual `@Provides` per design §3.2.)

**Step 1: Add the same annotation triplet to each class**

Pattern (apply to each):

```kotlin
import com.sketchbook.core.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class SqlProposalsRepository(
    private val catalog: Catalog,
    private val ioDispatcher: CoroutineDispatcher,
) : ProposalsRepository { /* ... unchanged body ... */ }
```

Same for `SqlRepairRepository` and `InMemoryJournalRepository`. `InMemoryJournalRepository` has a no-arg constructor — `@Inject constructor()` is implicit when you put `@Inject` on the class.

**Step 2: Update existing constructor calls in tests** if they pass extra args (they typically don't — these have minimal constructors).

**Step 3: Build and test**

```bash
./gradlew :shared:repository:build
```

**Step 4: Commit**

```bash
git add shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/SqlProposalsRepository.kt \
        shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/SqlRepairRepository.kt \
        shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/InMemoryJournalRepository.kt
git commit -m "refactor(repo): @Inject + @ContributesBinding for proposals/repair/journal repos"
```

### Task 2.3: Convert catalog services to `@Inject`

**Files:**
- Modify: `shared/catalog/src/jvmMain/kotlin/com/sketchbook/catalog/JvmScanner.kt`
- Modify: `shared/catalog/src/jvmMain/kotlin/com/sketchbook/catalog/JvmSampleScanner.kt`
- Modify: `shared/catalog/src/jvmMain/kotlin/com/sketchbook/catalog/SyncStateStore.kt`

**Step 1: Add annotations**

These are concrete types (no interface) — `@Inject` + `@SingleIn(AppScope::class)`, no `@ContributesBinding`:

```kotlin
import com.sketchbook.core.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@Inject
class JvmScanner(
    private val catalog: Catalog,
    private val fts: CatalogFts,
) { /* ... existing body ... */ }
```

Same pattern for `JvmSampleScanner` and `SyncStateStore`. Verify each existing constructor — they may have additional params not currently injected (e.g., `JvmSampleScanner` takes only `catalog`; `SyncStateStore` takes only `catalog`).

**Step 2: Build**

```bash
./gradlew :shared:catalog:build
```

**Step 3: Commit**

```bash
git add shared/catalog/src/jvmMain/kotlin/com/sketchbook/catalog/JvmScanner.kt \
        shared/catalog/src/jvmMain/kotlin/com/sketchbook/catalog/JvmSampleScanner.kt \
        shared/catalog/src/jvmMain/kotlin/com/sketchbook/catalog/SyncStateStore.kt
git commit -m "refactor(catalog): @Inject scanners + sync state store"
```

### Task 2.4: Convert `ProposalActionExecutor` to `@Inject`

**Files:**
- Modify: `shared/actions/src/commonMain/kotlin/com/sketchbook/actions/ProposalActionExecutor.kt`

**Step 1: Add annotations**

```kotlin
import com.sketchbook.core.AppScope
import com.sketchbook.repo.ProjectRepository
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@Inject
class ProposalActionExecutor(
    private val projects: ProjectRepository,
) { /* ... existing body ... */ }
```

**Step 2: Build**

```bash
./gradlew :shared:actions:build
```

**Step 3: Commit**

```bash
git add shared/actions/src/commonMain/kotlin/com/sketchbook/actions/ProposalActionExecutor.kt
git commit -m "refactor(actions): @Inject ProposalActionExecutor"
```

### Task 2.5: Verify graph still compiles end-to-end

The `@ContributesBinding`s now collide with the manual `@Provides` factories in `DesktopAppGraph` for the same types. Metro will fail with a duplicate-binding error.

**Step 1: Delete the now-redundant `@Provides` factories from `DesktopAppGraph`**

In `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt`, delete these `@Provides` functions:

- `provideJvmScanner` (lines ~106-107)
- `provideJvmSampleScanner` (lines ~110-111)
- `provideSyncStateStore` (lines ~114)
- `provideJournalRepository` (lines ~117)
- `provideProjectRepository` (lines ~120-129)
- `provideProposalsRepository` (lines ~152-153)
- `provideProposalActionExecutor` (lines ~156-157)
- `provideRepairRepository` (lines ~160-161)
- `provideCatalogFts` (lines ~103) — now `@Inject`'d on `CatalogFts` itself? Actually `CatalogFts` is in `shared/catalog/jvmMain` and has a non-trivial `driver` constructor param; it stays as a manual `@Provides` since `driver` is wired through `CatalogHandle`. **Keep this one.**

The accessors at the top of the interface (`val scanner: JvmScanner`, etc.) STAY — they're just exposing the bindings, now satisfied by `@ContributesBinding` rather than `@Provides`.

**Step 2: Verify the graph still has all required accessors**

The interface should still expose: `appScope`, `catalogHandle`, `catalog`, `catalogFts`, `syncStateStore`, `scanner`, `sampleScanner`, `projectRepository`, `journalRepository`, `snapshotRepository`, `proposalsRepository`, `proposalActionExecutor`, `repairRepository`, `settingsRepository`, `lockRepository`, `syncQueue`. Don't delete accessor declarations — the graph still needs them as the entry points for callers.

**Step 3: Build the full app**

```bash
./gradlew :app-desktop:compileKotlinJvm :app-desktop:test
```

Expected: green. Metro generates the graph impl using `@ContributesBinding`s for the now-implicit bindings. If you see "duplicate binding" errors, you missed deleting a `@Provides` factory.

**Step 4: Commit**

```bash
git add app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt
git commit -m "refactor(app): drop redundant @Provides — bindings now contributed"
```

---

## Phase 3 — State holders → ViewModels

### Task 3.1: Add ViewModel deps to participating modules

**Files (modify):**
- `shared/feature-projects/build.gradle.kts`
- `shared/feature-project-detail/build.gradle.kts`
- `shared/feature-timeline/build.gradle.kts`
- `shared/feature-proposals/build.gradle.kts`
- `shared/feature-needs-attention/build.gradle.kts`
- `shared/feature-settings/build.gradle.kts`
- `shared/feature-journal/build.gradle.kts`
- `app-desktop/build.gradle.kts`

**Step 1: Add to each feature module's `commonMain.dependencies` block:**

```kotlin
implementation(libs.lifecycle.viewmodel)
implementation(libs.lifecycle.viewmodel.compose)
implementation(libs.lifecycle.viewmodel.savedstate)
implementation(libs.lifecycle.runtime.compose)
implementation(libs.metro.viewmodel.compose)
```

**Step 2: Add to `app-desktop/build.gradle.kts` `jvmMain.dependencies`:**

```kotlin
implementation(libs.lifecycle.viewmodel)
implementation(libs.lifecycle.viewmodel.compose)
implementation(libs.lifecycle.viewmodel.navigation3)
implementation(libs.lifecycle.runtime.compose)
implementation(libs.metro.viewmodel.compose)
implementation(libs.kotlinx.coroutines.swing)
```

**Step 3: Build**

```bash
./gradlew :shared:feature-projects:build :app-desktop:compileKotlinJvm
```

(One feature module is enough to validate; the others will compile in subsequent tasks.)

**Step 4: Commit**

```bash
git add shared/feature-*/build.gradle.kts app-desktop/build.gradle.kts
git commit -m "build: add lifecycle/viewmodel + metro-viewmodel-compose to feature modules"
```

### Task 3.2: Convert `ProjectListStateHolder` → `ProjectListViewModel` (with `SavedStateHandle`)

**Files:**
- Rename: `shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/ProjectListStateHolder.kt` → `ProjectListViewModel.kt`
- Modify: `shared/feature-projects/src/commonTest/kotlin/com/sketchbook/featureprojects/ProjectListStateHolderTest.kt` → `ProjectListViewModelTest.kt`
- Modify: `shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/ProjectListScreen.kt` (rename references)
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt` (deferred to Phase 4)

**Step 1: Rename the file via git**

```bash
git mv shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/ProjectListStateHolder.kt \
       shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/ProjectListViewModel.kt
git mv shared/feature-projects/src/commonTest/kotlin/com/sketchbook/featureprojects/ProjectListStateHolderTest.kt \
       shared/feature-projects/src/commonTest/kotlin/com/sketchbook/featureprojects/ProjectListViewModelTest.kt
```

**Step 2: Edit the class — rename, extend `ViewModel`, replace `scope` with `viewModelScope`, use `SavedStateHandle`**

Replace the file contents with:

```kotlin
package com.sketchbook.featureprojects

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectRow
import com.sketchbook.repo.ProjectRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Project-list screen ViewModel. Survives recomposition via `lifecycle-viewmodel-navigation3`'s
 * `ViewModelStoreOwner`; `viewModelScope` cancels when the user navigates away.
 *
 * Scratch UI state (`query`, `searchSelectedIndex`, `zoomShelf`, `gemsShuffleSeed`) lives in
 * [SavedStateHandle] so it survives process restart on supported platforms (window restore on
 * macOS once the app wires up `SavedStateRegistry` flush — out of scope for this PR).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Inject
class ProjectListViewModel(
    private val repository: ProjectRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val query = savedState.getStateFlow(KEY_QUERY, "")
    private val gemsShuffleSeed = savedState.getStateFlow(KEY_GEMS_SEED, 0)
    private val zoomShelf = savedState.getStateFlow(KEY_ZOOM_SHELF, null as String?)
    private val searchSelectedIndex = savedState.getStateFlow(KEY_SEARCH_INDEX, 0)
    // openDetailId is purely transient — no SavedStateHandle entry.
    private val openDetailId = kotlinx.coroutines.flow.MutableStateFlow<ProjectId?>(null)

    private val _effects = MutableSharedFlow<Effect>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    private val rowsFlow: Flow<List<ProjectRow>> =
        query.flatMapLatest { repository.observeProjects(it) }
    private val archivedRowsFlow: Flow<List<ProjectRow>> = repository.observeArchivedProjects()

    val state: StateFlow<State> = combine(
        query,
        rowsFlow,
        archivedRowsFlow,
        gemsShuffleSeed,
        zoomShelf,
        openDetailId,
        searchSelectedIndex,
    ) { values ->
        @Suppress("UNCHECKED_CAST") val q = values[0] as String
        @Suppress("UNCHECKED_CAST") val rows = values[1] as List<ProjectRow>
        @Suppress("UNCHECKED_CAST") val archivedRows = values[2] as List<ProjectRow>
        val seed = values[3] as Int
        val zoomKey = values[4] as String?
        val openId = values[5] as ProjectId?
        val selectedIdx = values[6] as Int

        val zoom = zoomKey?.let { ShelfId.fromKey(it) }
        val groups = deriveProjectGroups(rows)
        val archivedGroups = deriveProjectGroups(archivedRows)
        val buckets = bucketize(groups, archivedGroups)
        val gemsView = if (seed == 0) {
            buckets.forgottenGems
        } else {
            buckets.forgottenGems.shuffled(kotlin.random.Random(seed.toLong() * 7919L))
        }
        val results = if (q.isBlank()) emptyList() else groups.filter { matchesQuery(it, q) }
        val clampedIdx = if (results.isEmpty()) 0 else selectedIdx.coerceIn(0, results.size - 1)

        State(
            query = q,
            rows = rows,
            archivedRows = archivedRows,
            groups = groups,
            buckets = buckets,
            gemsView = gemsView,
            gemsShuffleSeed = seed,
            searchResults = results,
            searchSelectedIndex = clampedIdx,
            zoomShelf = zoom,
            openDetailId = openId,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State(loading = true))

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.Search -> {
                savedState[KEY_QUERY] = intent.query
                savedState[KEY_SEARCH_INDEX] = 0
            }
            is Intent.Open -> _effects.tryEmit(Effect.Navigate(intent.id))
            is Intent.ZoomShelf -> savedState[KEY_ZOOM_SHELF] = intent.shelf?.key
            is Intent.ShuffleGems ->
                savedState[KEY_GEMS_SEED] = (gemsShuffleSeed.value + 1).coerceAtLeast(1)
            is Intent.OpenDetail -> openDetailId.update { intent.id }
            is Intent.CloseDetail -> openDetailId.update { null }
            is Intent.NavigateSearchNext -> {
                val size = state.value.searchResults.size
                if (size > 0) {
                    savedState[KEY_SEARCH_INDEX] =
                        (searchSelectedIndex.value + 1).coerceAtMost(size - 1)
                }
            }
            is Intent.NavigateSearchPrev -> {
                val size = state.value.searchResults.size
                if (size > 0) {
                    savedState[KEY_SEARCH_INDEX] =
                        (searchSelectedIndex.value - 1).coerceAtLeast(0)
                }
            }
            is Intent.OpenSelectedSearch -> {
                val s = state.value
                s.searchResults.getOrNull(s.searchSelectedIndex)?.let { group ->
                    openDetailId.update { group.representative.id }
                }
            }
        }
    }

    @Immutable
    data class State(
        val query: String = "",
        val rows: List<ProjectRow> = emptyList(),
        val archivedRows: List<ProjectRow> = emptyList(),
        val groups: List<ProjectGroup> = emptyList(),
        val buckets: Buckets = Buckets.EMPTY,
        val gemsView: List<ProjectGroup> = emptyList(),
        val gemsShuffleSeed: Int = 0,
        val searchResults: List<ProjectGroup> = emptyList(),
        val searchSelectedIndex: Int = 0,
        val zoomShelf: ShelfId? = null,
        val openDetailId: ProjectId? = null,
        val loading: Boolean = true,
    )

    sealed interface Intent {
        data class Search(val query: String) : Intent
        data class Open(val id: ProjectId) : Intent
        data class ZoomShelf(val shelf: ShelfId?) : Intent
        data object ShuffleGems : Intent
        data class OpenDetail(val id: ProjectId) : Intent
        data object CloseDetail : Intent
        data object NavigateSearchNext : Intent
        data object NavigateSearchPrev : Intent
        data object OpenSelectedSearch : Intent
    }

    sealed interface Effect {
        data class Navigate(val id: ProjectId) : Effect
    }

    private companion object {
        const val KEY_QUERY = "ProjectListViewModel.query"
        const val KEY_GEMS_SEED = "ProjectListViewModel.gemsSeed"
        const val KEY_ZOOM_SHELF = "ProjectListViewModel.zoomShelf"
        const val KEY_SEARCH_INDEX = "ProjectListViewModel.searchIndex"
    }
}
```

**Note on `ShelfId`:** Storing `ShelfId?` directly in `SavedStateHandle` requires `Parcelable`/serialization support not present in the KMP fork. Persist its key (`String?`) and reconstruct via `ShelfId.fromKey`. **You may need to add a `fun ShelfId.Companion.fromKey(s: String): ShelfId?` factory** if it doesn't exist — check `ProjectGroups.kt`. If `ShelfId` is a `data class(val key: String)`, the inverse is trivial; if it's a sealed hierarchy, switch on the key.

**Step 3: Update `ProjectListScreen.kt` references**

In `ProjectListScreen.kt`, search-and-replace `ProjectListStateHolder` → `ProjectListViewModel`. Most callers will go through `RootContent.kt` (Phase 4).

**Step 4: Update tests**

`ProjectListViewModelTest.kt` — replace constructor calls:

```kotlin
// before:
val holder = ProjectListStateHolder(repository = fake, scope = backgroundScope)

// after:
val vm = ProjectListViewModel(
    repository = fake,
    savedState = SavedStateHandle(),
)
```

Add `Dispatchers.setMain(StandardTestDispatcher(testScheduler))` in a `@BeforeTest` and `Dispatchers.resetMain()` in `@AfterTest`. Standard pattern — see [coroutines-test docs](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/kotlinx.coroutines.test/-dispatchers/index.html).

**Step 5: Build**

```bash
./gradlew :shared:feature-projects:build
```

Expected: green.

**Step 6: Commit**

```bash
git add shared/feature-projects
git commit -m "refactor(feature-projects): StateHolder -> ViewModel + SavedStateHandle"
```

### Task 3.3: Convert `ProjectDetailStateHolder` → `ProjectDetailViewModel` (with `@AssistedInject`)

**Files:**
- Rename: `shared/feature-project-detail/src/commonMain/kotlin/com/sketchbook/featuredetail/ProjectDetailStateHolder.kt` → `ProjectDetailViewModel.kt`
- Test: rename equivalent.

**Step 1: Same `git mv` pattern as Task 3.2.**

**Step 2: Edit class — `@AssistedInject` for `projectId`**

Add the assisted-injection wrapper:

```kotlin
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sketchbook.core.ProjectId
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject

@AssistedInject
class ProjectDetailViewModel(
    @Assisted private val projectId: ProjectId,
    private val projects: ProjectRepository,
    private val snapshots: SnapshotRepository,
    private val locks: LockRepository,
) : ViewModel() {

    @AssistedFactory
    fun interface Factory {
        operator fun invoke(projectId: ProjectId): ProjectDetailViewModel
    }

    // ... existing logic, using viewModelScope and the injected projectId ...
}
```

If the existing `ProjectDetailStateHolder` took `projectId` via an `Open(id)` intent rather than via constructor, the migration requires:
- Removing the `Open(id)`-as-init pattern
- Reading the id from the constructor
- Detail screen composable now takes the id from the `NavEntry<Screen.ProjectDetail>` key

This is a mechanical conversion but read the existing class carefully before editing — preserve the side-panel open/close intents and the "lock taken" effects.

**Step 3: Update tests** (same `Dispatchers.setMain` pattern).

**Step 4: Build + commit.**

```bash
./gradlew :shared:feature-project-detail:build
git add shared/feature-project-detail
git commit -m "refactor(feature-project-detail): @AssistedInject ProjectDetailViewModel"
```

### Tasks 3.4–3.8: Convert remaining state holders

Apply the same pattern to:
- `TimelineStateHolder` → `TimelineViewModel` (`shared/feature-timeline`) — no SavedState; pure ViewModel.
- `ProposalsStateHolder` → `ProposalsViewModel` (`shared/feature-proposals`) — no SavedState in this PR; once bulk filters land per `2026-05-06-proposals-needs-journal-ux-plan.md`, add SavedState for filter state.
- `NeedsAttentionStateHolder` → `NeedsAttentionViewModel` (`shared/feature-needs-attention`).
- `SettingsStateHolder` → `SettingsViewModel` (`shared/feature-settings`).
- `JournalStateHolder` → `JournalViewModel` (`shared/feature-journal`).

Per task:
1. `git mv` the source and test file.
2. Replace `class FooStateHolder(repo, scope)` with `@Inject class FooViewModel(repo) : ViewModel()`.
3. Inline `viewModelScope` in place of the `scope` parameter.
4. Mark `State` `@Immutable`.
5. Update tests (`Dispatchers.setMain`, instantiate ViewModel directly).
6. Build that module: `./gradlew :shared:feature-X:build`.
7. Commit: `refactor(feature-X): StateHolder -> ViewModel`.

**One commit per state holder** — keeps the diff reviewable and bisectable.

---

## Phase 4 — Compose call sites

### Task 4.1: Wire `NavDisplay` entryDecorators

**Files:**
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt:281` (the `NavDisplay(...)` call)

**Step 1: Add the decorators**

Before:
```kotlin
NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider = { /* ... */ },
)
```

After:
```kotlin
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberSavedStateNavEntryDecorator
import androidx.navigation3.ui.rememberSceneSetupNavEntryDecorator

NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryDecorators = listOf(
        rememberSceneSetupNavEntryDecorator(),
        rememberSavedStateNavEntryDecorator(),
        rememberViewModelStoreNavEntryDecorator(),
    ),
    entryProvider = { /* ... */ },
)
```

**Note:** Confirm artifact paths against the actual JetBrains nav3 API at `androidx.navigation3.runtime.*` and `androidx.lifecycle.viewmodel.navigation3.*`. Decorator names are exact per the JetBrains CMP nav3 doc.

**Step 2: Build**

```bash
./gradlew :app-desktop:compileKotlinJvm
```

Expected: green. (Decorators are a no-op until the next task replaces `remember { ... }` calls.)

**Step 3: Commit**

```bash
git add app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt
git commit -m "feat(nav3): add SavedState + ViewModelStore entry decorators"
```

### Task 4.2: Replace `remember { *StateHolder(...) }` with `metroViewModel<*ViewModel>()`

**Files:**
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt`

**Step 1: Restructure `RootContent` so each ViewModel is acquired inside its `NavEntry`**

The current pattern remembers all 7 holders at the root, then passes them down into the `NavDisplay`'s `entryProvider`. The new pattern moves each `metroViewModel()` call inside the entry-provider lambda for its destination.

For each existing entry:

```kotlin
entryProvider = { key ->
    when (key) {
        is Screen.Projects -> NavEntry(key) {
            val vm: ProjectListViewModel = metroViewModel()
            ProjectListScreen(vm = vm, /* ... */)
        }
        is Screen.ProjectDetail -> NavEntry(key) {
            val factory: ProjectDetailViewModel.Factory = graph.projectDetailViewModelFactory
            val vm: ProjectDetailViewModel = metroViewModel { factory(key.projectId) }
            ProjectDetailScreen(vm = vm, /* ... */)
        }
        is Screen.Timeline -> NavEntry(key) {
            val vm: TimelineViewModel = metroViewModel()
            TimelineScreen(vm = vm, /* ... */)
        }
        // ... etc for Proposals, NeedsAttention, Settings, Journal ...
    }
}
```

**Step 2: Add the assisted-factory accessor to the graph**

In `DesktopAppGraph.kt`, add:

```kotlin
val projectDetailViewModelFactory: ProjectDetailViewModel.Factory
```

Metro generates the factory binding from the `@AssistedInject` declaration; no `@Provides` needed.

**Step 3: Move per-screen `LaunchedEffect(holder) { holder.effects.collect { ... } }` into each `NavEntry`'s content**

Today these are at the top of `RootContent`. Move each one into the body of its corresponding `NavEntry { }` block so it's scoped to the screen's lifecycle.

**Step 4: Delete the obsolete `remember { Foo(...) }` blocks** at the top of `RootContent` (lines ~100-128).

**Step 5: Build and run**

```bash
./gradlew :app-desktop:run
```

Manually navigate Projects → Detail → Timeline and back. Confirm no crashes, screens load.

**Step 6: Commit**

```bash
git add app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt \
        app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt
git commit -m "feat(app): per-NavEntry ViewModels via metroViewModel()"
```

### Task 4.3: Replace `collectAsState` with `collectAsStateWithLifecycle`

**Files:** every `*Screen.kt` in `shared/feature-*/src/commonMain/` plus `RootContent.kt`.

**Step 1: Add the import**

```kotlin
import androidx.lifecycle.compose.collectAsStateWithLifecycle
```

**Step 2: Replace each call site**

```kotlin
// before
val state by vm.state.collectAsState()

// after
val state by vm.state.collectAsStateWithLifecycle()
```

If any call had an explicit initial value (`collectAsState(initial = ...)`), pass the same value to `collectAsStateWithLifecycle(initialValue = ...)` — the parameter name differs.

**Step 3: Search for any remaining `collectAsState` in our code**

```bash
git grep -n "collectAsState\b" -- 'shared/**/*.kt' 'app-desktop/**/*.kt'
```

Expected: no results inside our `commonMain` / `jvmMain`. (Compose's own internals may still use it; we're only changing our call sites.)

**Step 4: Build and smoke test**

```bash
./gradlew :app-desktop:run
```

With the app running, minimize the window; on a Mac/Windows task manager, confirm CPU drops near zero (was previously kept ~5-10% by `stateIn`/`combine` flow collection).

**Step 5: Commit**

```bash
git commit -am "feat(ui): collectAsStateWithLifecycle for window-aware flow collection"
```

### Task 4.4: Mark `State` data classes `@Immutable`

This was incorporated into Tasks 3.2-3.8. If any `*ViewModel.State` is missing `@Immutable`, add it now and commit:

```bash
git grep -L "@Immutable" -- 'shared/feature-*/src/commonMain/**/*ViewModel.kt'
```

Expected: empty list. If not, add the annotation.

---

## Phase 5 — Graph cleanup

### Task 5.1: Final pass on `DesktopAppGraph`

**Files:**
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt`

**Step 1: The graph should now look approximately like this** (only platform-specific or cloud-coupled bindings remain):

```kotlin
@DependencyGraph(scope = AppScope::class)
interface DesktopAppGraph {

    // Accessors — full set still exposed for callers
    val appScope: CoroutineScope
    val catalogHandle: CatalogHandle
    val catalog: Catalog
    val catalogFts: CatalogFts
    val syncStateStore: SyncStateStore
    val scanner: JvmScanner
    val sampleScanner: JvmSampleScanner
    val projectRepository: ProjectRepository
    val journalRepository: JournalRepository
    val snapshotRepository: SnapshotRepository
    val proposalsRepository: ProposalsRepository
    val proposalActionExecutor: ProposalActionExecutor
    val repairRepository: RepairRepository
    val settingsRepository: SettingsRepository
    val lockRepository: LockRepository
    val syncQueue: SyncQueue

    // ViewModel assisted factories
    val projectDetailViewModelFactory: ProjectDetailViewModel.Factory

    // ---- Platform-specific or cloud-coupled bindings ----

    @Provides @SingleIn(AppScope::class)
    fun provideAppScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @SingleIn(AppScope::class)
    fun provideCatalogHandle(): CatalogHandle = CatalogDb.openOnDisk(catalogDbPath())

    @Provides @SingleIn(AppScope::class)
    fun provideCatalog(handle: CatalogHandle): Catalog = handle.catalog

    @Provides @SingleIn(AppScope::class)
    fun provideCatalogFts(handle: CatalogHandle): CatalogFts = CatalogFts(handle.driver)

    @Provides @SingleIn(AppScope::class)
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides @SingleIn(AppScope::class)
    fun provideSettingsRepository(): SettingsRepository = PreferencesSettingsRepository(
        node = Preferences.userNodeForPackage(SettingsRepository::class.java),
        ioDispatcher = Dispatchers.IO,
    )

    // Cloud-coupled — future CloudScope PR
    @Provides @SingleIn(AppScope::class)
    fun provideSyncQueue(/* ... */): SyncQueue = SwappableSyncQueue(/* ... */)

    @Provides @SingleIn(AppScope::class)
    fun provideLockRepository(/* ... */): LockRepository = LeasedLockRepository(/* ... */)

    @Provides @SingleIn(AppScope::class)
    fun provideSnapshotRepository(/* ... */): SnapshotRepository = SqlSnapshotRepository(/* ... */)
}
```

**Step 2: Verify no `@Provides` for the contributed types remain**

```bash
git grep -n "fun provide" app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt
```

Expected output: only the providers listed above (appScope, catalogHandle, catalog, catalogFts, ioDispatcher, settingsRepository, syncQueue, lockRepository, snapshotRepository).

**Step 3: Provide `CoroutineDispatcher` for repositories that take it**

`SqlProjectRepository` and friends take `ioDispatcher: CoroutineDispatcher` as a constructor arg. The graph now needs to provide a `CoroutineDispatcher` binding (the `provideIoDispatcher` above). Verify `Dispatchers.IO` is exposed.

**Step 4: Build full app**

```bash
./gradlew clean build
```

Expected: green. All tests pass.

**Step 5: Commit (if anything changed)**

If the file is already in the target state from Phase 2, this task may be a no-op. Otherwise:

```bash
git commit -am "refactor(app): finalize DesktopAppGraph — only platform/cloud bindings remain"
```

### Task 5.2: Smoke test

**Step 1: Run the app**

```bash
./gradlew :app-desktop:run
```

**Step 2: Manual flow**

1. App launches; library list renders.
2. Type a query in search; navigate away to Settings; come back to Projects. **The search query persists** (proves `SavedStateHandle` is wired).
3. Open a project detail; navigate back; open a different project detail. **Each detail view has its own ViewModel instance** (proves per-`NavEntry` scoping). Adding logging in `ProjectDetailViewModel.init` and `onCleared` is a quick way to verify.
4. Trigger a long-running scan, navigate to Settings mid-scan. **Scan continues** (proves the scan coroutine is on `appScope`, not `viewModelScope`).
5. Minimize the window. **CPU drops to near zero** (proves `collectAsStateWithLifecycle` pauses collection).

**Step 3: No commit** — this is verification.

---

## Phase 6 — Tests

### Task 6.1: Add a `MainDispatcherRule` helper for ViewModel tests

**Files:**
- Create: `shared/ui-shared/src/commonTest/kotlin/com/sketchbook/uishared/test/MainDispatcherSetup.kt`

**Step 1: Write the helper**

```kotlin
package com.sketchbook.uishared.test

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * Sets up [Dispatchers.Main] with a [TestDispatcher] for ViewModel tests where `viewModelScope`
 * (which uses `Dispatchers.Main.immediate`) is involved. Call `setUpMain` from `@BeforeTest`
 * and `tearDownMain` from `@AfterTest`. Returns the dispatcher so tests can advance time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherSetup {
    val dispatcher: TestDispatcher = StandardTestDispatcher()

    fun setUp() { Dispatchers.setMain(dispatcher) }
    fun tearDown() { Dispatchers.resetMain() }
}
```

**Step 2: Make it test-visible**

`shared/ui-shared/build.gradle.kts` — ensure `commonTest` exposes `kotlinx-coroutines-test` (already in tree). The helper is in `commonTest`, so feature-module tests need to depend on `shared/ui-shared`'s test fixtures. Easiest path: copy this small file into each feature module's `commonTest` (it's tiny). DRY violation is ~5 lines × 7 modules = bearable; introducing a `commonTestFixtures` source set is overkill for this PR.

**Step 3: Use it in `ProjectListViewModelTest`**

```kotlin
class ProjectListViewModelTest {
    private val main = MainDispatcherSetup()

    @BeforeTest fun before() = main.setUp()
    @AfterTest fun after() = main.tearDown()

    @Test fun search_updates_state() = runTest(main.dispatcher) {
        val vm = ProjectListViewModel(repository = FakeRepo(), savedState = SavedStateHandle())
        vm.dispatch(ProjectListViewModel.Intent.Search("foo"))
        // Turbine flow assertions ...
    }
}
```

**Step 4: Build all module tests**

```bash
./gradlew test
```

**Step 5: Commit**

```bash
git add shared/*/src/commonTest/kotlin
git commit -m "test: MainDispatcherSetup helper for ViewModel tests"
```

### Task 6.2: Update each feature module's existing test to use the helper

For each `*ViewModelTest` (renamed from `*StateHolderTest`):
- Add `MainDispatcherSetup` field + `@BeforeTest`/`@AfterTest`.
- Replace `runTest { ... }` with `runTest(main.dispatcher) { ... }` so test time advances on the same dispatcher `viewModelScope` uses.
- Replace `backgroundScope` constructor arg with the new ViewModel constructor (no scope parameter).

One commit per module:

```bash
./gradlew :shared:feature-projects:test
git commit -am "test(feature-projects): wire MainDispatcherSetup"
```

Repeat for the other 6 feature modules.

---

## Phase 7 — Documentation

### Task 7.1: Update CLAUDE.md / COPILOT.md / JUNIE.md / CONTRIBUTING.md

**Files:**
- Modify: `CONTRIBUTING.md:40`
- Modify: `docs/ai/CLAUDE.md:33` (state-holder pattern), `:65-71` (avoided libraries)
- Modify: `docs/ai/COPILOT.md:15`
- Modify: `docs/ai/JUNIE.md:34`

**Step 1: `CONTRIBUTING.md:40`** — remove `viewmodel-compose` from the avoid line:

Before:
```
- Explicitly avoided libraries: MVIKotlin, Decompose, Roborazzi, KAPT, Anvil, Realm Kotlin, Moko-resources, `androidx.lifecycle:viewmodel-compose`, Koin, Room. Reasons live in the design doc §2.1.
```

After:
```
- Explicitly avoided libraries: MVIKotlin, Decompose, Roborazzi, KAPT, Anvil, Realm Kotlin, Moko-resources, Koin, Room. Reasons live in the design doc §2.1. (`viewmodel-compose` was reversed in 2026-05-06; see `docs/plans/2026-05-06-metro-kmp-viewmodel-design.md` §1.2.)
```

**Step 2: `docs/ai/CLAUDE.md:33`** — replace the state-holder pattern paragraph:

Before:
```
Every screen has one state-holder following design-doc §2.4. Sealed `Intent`, data class `State`, sealed `Effect`, `state: StateFlow<State>`, `accept(intent)`, `effects: SharedFlow<Effect>`. Compose collects state, dispatches intents, observes effects via `LaunchedEffect` once at screen root. No MVI library — just plain Kotlin.
```

After:
```
Every screen has one ViewModel (subclass of `androidx.lifecycle.ViewModel`) injected via Metro. Sealed `Intent`, `@Immutable data class State`, sealed `Effect`, `state: StateFlow<State>`, `dispatch(intent)`, `effects: SharedFlow<Effect>`. Acquired in the screen's `NavEntry` via `metroViewModel<FooViewModel>()` so it's scoped to the nav back stack — `viewModelScope` cancels on pop. Compose collects state with `collectAsStateWithLifecycle`, dispatches intents, observes effects via `LaunchedEffect`. No MVI library — just plain Kotlin + lifecycle-viewmodel.
```

**Step 3: `docs/ai/CLAUDE.md:65-71`** — remove `viewmodel-compose` line:

Before:
```
- **Moko-resources / `viewmodel-compose`** — not needed in v1; would couple us to Android-isms.
```

After:
```
- **Moko-resources** — not needed in v1.
```

**Step 4: `docs/ai/COPILOT.md:15`** — remove `viewmodel-compose` from the avoided-libs line. Same edit pattern.

**Step 5: `docs/ai/JUNIE.md:34`** — same.

**Step 6: Commit**

```bash
git add CONTRIBUTING.md docs/ai/CLAUDE.md docs/ai/COPILOT.md docs/ai/JUNIE.md
git commit -m "docs(ai): update state-holder pattern + remove viewmodel-compose from avoid list"
```

### Task 7.2: Append decision-update note to design doc §2.1

**Files:**
- Modify: `docs/plans/2026-05-05-sync-versioning-design.md:69`

**Step 1: Add a note immediately after the `Rejected / avoided:` line:**

```markdown
**Rejected / avoided:** MVIKotlin, Decompose, Roborazzi screenshot tests, KAPT, Anvil, Realm Kotlin, Moko-resources, Koin, Room (FTS5 codegen is Android-only on KMP).

> **Decision update (2026-05-06):** `androidx.lifecycle:viewmodel-compose` was originally on this list ("would couple us to Android-isms"). Reversed in `docs/plans/2026-05-06-metro-kmp-viewmodel-design.md` because (a) Navigation 3 was adopted, making the AndroidX-coupling argument moot; (b) the per-screen lifecycle benefit is real now that we have a multi-destination back stack; (c) JetBrains publishes a KMP-clean fork (`org.jetbrains.androidx.lifecycle:*`) explicitly for CMP.
```

**Step 2: Commit**

```bash
git add docs/plans/2026-05-05-sync-versioning-design.md
git commit -m "docs(design): note 2026-05-06 reversal of viewmodel-compose rejection"
```

### Task 7.3: Create `docs/architecture/dependency-injection.md`

**Files:**
- Create: `docs/architecture/dependency-injection.md`

**Step 1: Write the policy doc**

```markdown
# Dependency Injection — Sketchbook policy

Metro is the DI framework. This doc codifies which bindings live where, what scope they live in, and what's forbidden.

## When to use which annotation

| Situation | Annotation |
|---|---|
| Stateful service implementing an interface | `@Inject` constructor + `@SingleIn(AppScope::class)` + `@ContributesBinding(AppScope::class)` on the class |
| Stateful service exposed by concrete type (no interface) | `@Inject` constructor + `@SingleIn(AppScope::class)` |
| ViewModel | `@Inject` constructor on a `ViewModel` subclass; *no* `@SingleIn` (lifetime owned by `NavEntry`'s `ViewModelStore`) |
| ViewModel needing nav-time arg | `@AssistedInject` + `@AssistedFactory`; expose the factory as a graph accessor |
| Platform-specific binding (paths, native preferences, etc.) | `@Provides` factory in the platform graph (`DesktopAppGraph`) |
| Module-level grouping of related `@Provides` | `@ContributesTo(AppScope::class) interface FooBindings { ... }` |

Anti-patterns:
- Calling Metro `@Provides` functions directly from source code (per Metro docs).
- Using `lazy { }` or `by lazy` to memoize service references — that's what `@SingleIn` is for.
- Member injection (`@Inject lateinit var foo`); use constructor injection.

## Scopes

- **`AppScope`** (`com.sketchbook.core.AppScope`) — application lifetime. Repositories, scanners, executors, `appScope: CoroutineScope`.
- **(future) `CloudScope`** — child of `AppScope`, lifetime tied to cloud-credential availability. Will hold `CloudBackend`, `ManifestMaterializer`, the cloud half of `LockRepository`. Eliminates the current `as? SwappableSyncQueue` casts in `DesktopAppGraph`.
- **(implicit) ViewModel lifetime** — owned by nav3's `ViewModelStoreOwner` per `NavEntry`. Nothing in our code annotates this; it's automatic via `lifecycle-viewmodel-navigation3`.

## What `object` is allowed for

Allowed:
- Pure-function namespaces (`Hasher`, `BlobInstaller`, `AlsParser`, `JournalJson`, `EffortScore`, `SampleResolver`, `CatalogDb`).
- Constants in `companion object`.

Forbidden in `object` declarations (and `companion object` declarations) in main source sets:
- `var`, `lateinit var`
- `MutableStateFlow`, `MutableSharedFlow`
- `Mutex`
- `AtomicReference`, `AtomicInteger`, etc.
- `ConcurrentHashMap`, mutable collections held as state

If you find yourself wanting any of these in an `object`, the type should be a class with `@Inject` constructor + `@SingleIn(AppScope::class)`.

## Adding a non-JVM target

When iOS/Android/native targets are added:
- Promote desktop-specific bindings (`PreferencesSettingsRepository`, `JvmScanner`, `JvmSampleScanner`) to `expect`/`actual` declarations in `commonMain` with platform-specific actuals.
- Each platform's app module gets its own `@DependencyGraph` (e.g., `IosAppGraph`) that includes the same per-module `@ContributesBinding`s.

## What stays manually `@Provides`d in `DesktopAppGraph`

These are deliberate carve-outs (cloud-coupled or platform-specific):
- `appScope` (lifecycle is the app's; not a contributable binding)
- `catalogHandle`, `catalog`, `catalogFts` (path resolution + driver wiring is desktop-specific)
- `settingsRepository` (uses `java.util.prefs.Preferences`)
- `syncQueue`, `lockRepository`, `snapshotRepository` (entangled with `SwappableSyncQueue`'s impl-level capabilities; future `CloudScope` PR cleans these up)
- `ioDispatcher` (binding for `CoroutineDispatcher` is platform-flavored)

## Recommended reading

- [Metro design doc](https://zacsweers.github.io/metro/latest/designdoc.html)
- [`docs/plans/2026-05-06-metro-kmp-viewmodel-design.md`](../plans/2026-05-06-metro-kmp-viewmodel-design.md)
```

**Step 2: Commit**

```bash
git add docs/architecture/dependency-injection.md
git commit -m "docs(architecture): dependency injection policy"
```

---

## Phase 8 — Final verification

### Task 8.1: Full clean build

**Step 1:**

```bash
./gradlew clean build
```

Expected: green. Test count should be ≥ what it was before (no tests deleted; some renamed).

### Task 8.2: Manual smoke test (release-quality)

**Step 1:** Same as Task 5.2 but go through every screen at least once. Confirm:

- All 7 destinations render.
- Search query persists across nav.
- Detail view of project A → back → detail view of project B shows project B's data (not stale A data).
- Mid-scan navigation doesn't cancel the scan.
- Window minimize → CPU near zero → un-minimize → flows resume immediately (no laggy first paint).
- Approve / reject a proposal: still works, journal entry appears.

### Task 8.3: Open the PR

**Step 1:**

```bash
git push -u origin feat/metro-kmp-viewmodel
gh pr create --title "Metro per-module DI + KMP ViewModel + nav3 lifecycle scoping" --body "$(cat <<'EOF'
## Summary
- Repositories and services migrate to Metro `@Inject` + `@ContributesBinding(AppScope::class)`. `DesktopAppGraph` shrinks from ~325 to ~120 lines.
- All 7 `*StateHolder`s become `*ViewModel` subclasses; per-`NavEntry` scoping via `lifecycle-viewmodel-navigation3` clears them on backstack pop.
- `ProjectListViewModel` uses `SavedStateHandle` for scratch UI state. `ProjectDetailViewModel` uses `@AssistedInject` for the nav-time `projectId`.
- `collectAsStateWithLifecycle` replaces `collectAsState` everywhere — flows pause when the window is hidden.
- Reverses the original `viewmodel-compose` rejection from `2026-05-05-sync-versioning-design.md` §2.1; rationale documented inline.

Carve-outs deferred to follow-up PRs:
- `CloudScope` for the `SwappableSyncQueue` impl-leak (3 `as?` casts in `DesktopAppGraph`).
- `McpAppGraph` for `app-mcp/Main.kt`'s hand-wired catalog/repos.

## Test plan
- [ ] `./gradlew clean build` green.
- [ ] App launches; search query persists across navigation.
- [ ] `ProjectDetailViewModel` instances are distinct per project (verified via init/onCleared logging).
- [ ] Window minimize drops CPU to near zero.
- [ ] Mid-scan navigation does not cancel the scan.

Design: `docs/plans/2026-05-06-metro-kmp-viewmodel-design.md`. Plan: `docs/plans/2026-05-06-metro-kmp-viewmodel-plan.md`. Policy: `docs/architecture/dependency-injection.md`.
EOF
)"
```

Per `feedback_local_build_authority` and `feedback_no_branch_protection`: once the local build is green, merge with `--admin` immediately if review isn't blocking on something specific.

---

## Notes for whoever executes this plan

- **Drive through tasks** (per `feedback_no_batch_checkpoints`); commit at each task boundary so the PR has a bisectable history.
- **Visual-verify** UI behavior after Phase 4. Don't claim done until you've run the app and confirmed lifecycle-correct behavior.
- **If you hit a Metro error** about missing bindings, check that the binding's class has `@Inject` constructor + `@ContributesBinding(AppScope::class)` AND that the consuming graph has `scope = AppScope::class`. The most common failure mode is forgetting `@Inject` on a constructor.
- **If `metroViewModel { factory(arg) }` won't compile**, verify `metrox-viewmodel-compose` resolved correctly and that the `@AssistedFactory fun interface` is exposed as a graph accessor (Step 4.2 Step 2).
- **Don't refactor `SyncQueue`, `LockRepository`, `SnapshotRepository`, `SettingsRepository`** in this PR. They're carve-outs for the future `CloudScope` PR. Touching them here is scope creep.
- **Don't touch `app-mcp/Main.kt` beyond the constructor-update line in Task 2.1.** `McpAppGraph` is its own follow-up PR.
