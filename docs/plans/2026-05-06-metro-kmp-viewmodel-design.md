# Metro per-module DI + KMP ViewModel + nav3 lifecycle scoping — design

**Goal:** Eliminate the manual `@Provides` factory boilerplate in `DesktopAppGraph`, give every screen a real per-screen lifecycle, and codify what belongs in DI vs in an `object`. One PR, top to bottom.

**Predecessors:**
- [`2026-05-05-sync-versioning-design.md`](2026-05-05-sync-versioning-design.md) — original architecture (§2.1 rejected `viewmodel-compose`; §2.6 sketched Metro wiring)
- [`2026-05-06-post-review-plan.md`](2026-05-06-post-review-plan.md) — 1.0 readiness work in flight

**Tech additions (only):**
- `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel` (KMP ViewModel)
- `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose` (Compose helpers)
- `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3` (nav3 ViewModel scoping)
- `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-savedstate` (SavedStateHandle)
- `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose` (`collectAsStateWithLifecycle`)
- `org.jetbrains.kotlinx:kotlinx-coroutines-swing` (desktop EDT dispatch for `viewModelScope`)
- `dev.zacsweers.metro:metrox-viewmodel-compose` (Metro × ViewModel injection)

---

## 1. Why now

### 1.1 Concrete smells

Three real problems in the current tree, with file:line refs:

1. **`RootContent.kt:100-128` hand-wires 7 state holders** with `remember { FooStateHolder(graph.repo, graph.appScope) }`. Adding a constructor parameter doesn't propagate from the compiler — it requires editing the app module. The state holders are remembered at the *root* composable, so they live for the app's lifetime; their `stateIn(scope, Eagerly)` flow combines keep collecting from repositories for screens the user has navigated away from.

2. **`DesktopAppGraph.kt:142, 177, 244` does `as? SwappableSyncQueue` three times.** The `SyncQueue` interface is too narrow — consumers need `currentMaterializer` and `currentCloud`, which aren't on the interface, so they downcast to the impl type. (Out of scope for this PR; addressed in the future `CloudScope` PR.)

3. **`app-mcp/Main.kt:35-45` duplicates the catalog/repository wiring** that `DesktopAppGraph` has. If `SqlProjectRepository`'s constructor changes, both call sites need manual updates. (Out of scope for this PR; addressed in the future `McpAppGraph` PR.)

### 1.2 The reversed decision

The original `2026-05-05-sync-versioning-design.md` §2.1 rejected `androidx.lifecycle:viewmodel-compose` because it would "couple us to Android-isms" and "we can replace this with Decompose/Navigation 3 later if a real need emerges; v1 doesn't need it."

That decision is reversed because its premises don't hold anymore:

- Navigation 3 was adopted (`libs.nav3.ui` in `gradle/libs.versions.toml`; `NavDisplay`, `NavBackStack`, `rememberNavBackStack` are in production in `Main.kt:45` and `RootContent.kt:281`). The "no AndroidX coupling" rationale is moot — the app already imports `androidx.navigation3.runtime.*`.
- The state-holder lifetime cost predicted by the design doc has shown up: 7 forever-alive state holders at root composition.
- JetBrains publishes `org.jetbrains.androidx.lifecycle:*` as a KMP-clean fork specifically for Compose Multiplatform; this is the canonical pattern for CMP + nav3 in 2026, not an Android-ism leak.

### 1.3 What we're not doing

- **No `expect`/`actual` refactor** of `Hasher`, `BlobInstaller`, `AlsParser`, `Os`, `JvmScanner`, etc. They're `jvmMain` today; refactoring them to common with platform actuals is the right move *when* a second target lands. The repo has been JVM-only for a year. YAGNI.
- **No `kotlinx.io.files.Path` migration** off `java.nio.file.Path`. Same reason.
- **No `compose-resources` adoption** — separate concern, not DI.
- **No detekt rule enforcement** — the policy doc captures the rule; detekt setup is its own decision and shouldn't ride on this PR.
- **No MCP `Tools` multibinding refactor** — wait until `McpAppGraph` lands.
- **No `CloudScope` introduction** — touches the sync engine, deserves its own PR.

---

## 2. Target architecture

### 2.1 Metro idioms (post-PR)

**Stateful service with an interface — `@ContributesBinding`:**

```kotlin
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class SqlProjectRepository(
    private val catalog: Catalog,
    private val journal: JournalRepository,
    private val fts: CatalogFts,
    private val ioDispatcher: CoroutineDispatcher,
) : ProjectRepository { ... }
```

The `ProjectRepository` binding is contributed automatically — no `@Provides` factory in `DesktopAppGraph`.

**Stateful service exposed by concrete type — `@Inject` only:**

```kotlin
@SingleIn(AppScope::class)
@Inject
class JvmScanner(
    private val catalog: Catalog,
    private val fts: CatalogFts,
) { ... }
```

Graph accessor (`val scanner: JvmScanner`) is enough — Metro generates the factory from `@Inject`.

**Per-module `@ContributesTo` interface — only when factory bindings are non-trivial:**

```kotlin
@ContributesTo(AppScope::class)
interface RepositoryBindings {
    @Provides fun provideArchiveAge(): Duration = (30L * 18).days
}
```

Used sparingly — most bindings end up as constructor `@Inject`.

**View-model — `@Inject` ViewModel + `metroViewModel()`:**

```kotlin
@Inject
class ProjectListViewModel(
    private val repository: ProjectRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val query: MutableStateFlow<String> = savedState.getStateFlow(KEY_QUERY, "")
    // viewModelScope replaces the manually-passed CoroutineScope
}
```

In the screen composable:
```kotlin
val vm: ProjectListViewModel = metroViewModel()
val state by vm.state.collectAsStateWithLifecycle()
```

**Assisted ViewModel — `@AssistedInject` for nav-arg injection:**

```kotlin
@AssistedInject
class ProjectDetailViewModel(
    @Assisted val projectId: ProjectId,
    private val projects: ProjectRepository,
    private val locks: LockRepository,
    private val snapshots: SnapshotRepository,
) : ViewModel() {
    @AssistedFactory fun interface Factory {
        operator fun invoke(projectId: ProjectId): ProjectDetailViewModel
    }
}
```

In the `NavEntry<Screen.ProjectDetail>` composable:
```kotlin
val factory: ProjectDetailViewModel.Factory = graph.projectDetailViewModelFactory
val vm: ProjectDetailViewModel = metroViewModel { factory(screen.projectId) }
```

### 2.2 Scopes

Single scope in this PR: `AppScope` (already exists in `app-desktop`; moves to `shared/core`).

`ViewModel`s are **not** annotated `@SingleIn(...)` — they're per-`NavEntry` via `lifecycle-viewmodel-navigation3`'s `ViewModelStoreOwner`. Constructor `@Inject` is enough; the `ViewModelStore` owns the lifetime.

Future scopes (NOT in this PR, documented for context):
- `CloudScope` — child of `AppScope`, lives while cloud creds are configured. Holds `CloudBackend`, `ManifestMaterializer`, the cloud half of `LockRepository`. Deletes the three `as?` casts.

### 2.3 Lifecycle scoping (nav3)

`NavDisplay` gains:

```kotlin
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

Each `NavEntry` now owns a `ViewModelStoreOwner` and a `SavedStateRegistryOwner`. When a destination leaves the back stack, its `ViewModelStore` is cleared, `onCleared()` runs on each ViewModel, and `viewModelScope` cancels.

### 2.4 Compose flow collection

Replace every `collectAsState(...)` in `*Screen.kt` and `RootContent.kt` with `collectAsStateWithLifecycle(...)`. This pauses collection when the window is hidden (minimized, on another desktop, etc.) and restarts on reattach. Same correctness story as Android, applies to desktop windows.

### 2.5 Compose stability

Mark every `*ViewModel.State` data class `@Immutable`:

```kotlin
@Immutable
data class State(
    val query: String = "",
    val rows: List<ProjectRow> = emptyList(),
    /* ... */
)
```

This lets Compose elide recomposition when the StateFlow emits a value that's `==` to the previous one. Cheap; ~7 annotations.

### 2.6 SavedStateHandle scope

Use `SavedStateHandle` for scratch state that should survive process restart:

- `ProjectListViewModel`: `query`, `searchSelectedIndex`, `zoomShelf`, `gemsShuffleSeed`
- `TimelineViewModel`: any cursor/scroll position
- `ProposalsViewModel` / `NeedsAttentionViewModel`: filter state once we add bulk filters

`SavedStateHandle.getStateFlow(key, default)` is a 1-line drop-in for `MutableStateFlow(default)`.

For the desktop window, persistence to disk requires a `SavedStateRegistry` writer that flushes on window close — out of scope for this PR; default is in-memory and clears on app exit, which matches today's behavior. The annotation surface (`SavedStateHandle`) goes in now so the disk persistence is a follow-up refactor inside the ViewModel, not a structural change.

---

## 3. PR scope

### 3.1 What changes

**Module Gradle wiring:**
- Add `metro` plugin + `metro.runtime` dep to: `shared/core`, `shared/actions`, `shared/catalog`, `shared/repository`, all 7 `shared/feature-*` modules.
- Add the seven new lifecycle/coroutines deps to `gradle/libs.versions.toml`.
- `app-desktop` and feature modules add `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose`, `metrox-viewmodel-compose`.
- `app-desktop` adds `kotlinx-coroutines-swing`.

**Move `AppScope` to `shared/core`:**
- New file `shared/core/src/commonMain/kotlin/com/sketchbook/core/AppScope.kt` — `abstract class AppScope private constructor()`.
- Update import in `DesktopAppGraph.kt` and any other reference.

**Repository module (`shared/repository`):**
- `SqlProjectRepository`, `SqlProposalsRepository`, `SqlRepairRepository`, `SqlSnapshotRepository`, `InMemoryJournalRepository`: add `@SingleIn(AppScope::class)` + `@Inject` constructor + `@ContributesBinding(AppScope::class)`.
- `SqlSnapshotRepository` retains its `materialize` lambda parameter — graph still hand-provides it (cloud-coupled).
- `SqlProjectRepository` swaps the `ftsSearch: (String) -> Flow<List<ProjectId>>` lambda for a `CatalogFts` constructor parameter; calls `fts.search(query)` directly.

**Catalog module (`shared/catalog`):**
- `JvmScanner`, `JvmSampleScanner`, `SyncStateStore`: add `@SingleIn(AppScope::class)` + `@Inject` constructor.
- `CatalogDb`, `CatalogFts`, `CatalogHandle`: stay as-is. `CatalogDb` is still an `object` factory called by the graph (path resolution is desktop-specific and lives in `DesktopAppGraph`).

**Actions module (`shared/actions`):**
- `ProposalActionExecutor`: `@Inject` constructor.

**Feature modules — state holders → ViewModels:**
- Each `*StateHolder` is renamed `*ViewModel`, extends `androidx.lifecycle.ViewModel`, gets `@Inject` constructor.
- The `scope: CoroutineScope` constructor parameter is removed; `viewModelScope` replaces internal references.
- `*StateHolder.State` becomes `*ViewModel.State`, marked `@Immutable`.
- `ProjectListViewModel` adds `SavedStateHandle` parameter; `query` / `searchSelectedIndex` / `zoomShelf` / `gemsShuffleSeed` migrate from `MutableStateFlow(default)` to `savedState.getStateFlow(KEY, default)`.
- `ProjectDetailViewModel` uses `@AssistedInject` with `@Assisted projectId: ProjectId`; an `@AssistedFactory` interface is exposed.

**Compose call sites:**
- `RootContent.kt`: delete the 7× `remember { FooStateHolder(...) }` blocks. Each `NavEntry` resolves its ViewModel via `metroViewModel<FooViewModel>()`. The `LaunchedEffect(holder) { holder.effects.collect { ... } }` blocks move into the per-`NavEntry` content composable so they're scoped to the screen.
- All `collectAsState(...)` calls become `collectAsStateWithLifecycle(...)`.
- `NavDisplay` gains the `entryDecorators` list (saved-state decorator + viewmodel-store decorator).

**`DesktopAppGraph` shrink:**
- Remove `@Provides` factories for: `JournalRepository`, `ProjectRepository`, `ProposalsRepository`, `RepairRepository`, `JvmScanner`, `JvmSampleScanner`, `SyncStateStore`, `CatalogFts`, `ProposalActionExecutor`. (Now contributed.)
- Keep `@Provides` for: `appScope`, `catalogHandle`, `catalog`, `settingsRepository`, `lockRepository`, `syncQueue`, `snapshotRepository`. (Cloud-coupled or platform-specific — future `CloudScope` PR.)
- Add ViewModel factory accessors for screens that need explicit factories (`ProjectDetailViewModel.Factory`).
- Net file size: ~200 → ~50 lines.

**Tests:**
- Existing `*StateHolderTest` files: rename to `*ViewModelTest`, replace the `backgroundScope`-passing constructor with `runTest { val vm = ... ; vm.dispatch(...) }`. Use `Dispatchers.setMain(StandardTestDispatcher())` (with `kotlinx-coroutines-test`) so `viewModelScope` is driven deterministically.
- Repository tests in `shared/repository/src/jvmTest/`: unchanged (already constructor-injected with hand-built fakes).

**Documentation:**
- Update `CONTRIBUTING.md:40` — remove `viewmodel-compose` from the avoided-libraries line; add brief "decision update" note.
- Update `docs/ai/CLAUDE.md:33` — state-holder pattern section now references `ViewModel + @Inject + metroViewModel()`. Remove `viewmodel-compose` from `:65-71` avoid list.
- Update `docs/ai/COPILOT.md:15` — same.
- Update `docs/ai/JUNIE.md:34` — same.
- Append a "decision update" section to `docs/plans/2026-05-05-sync-versioning-design.md` §2.1 explaining the reversal.
- Add new file `docs/architecture/dependency-injection.md` codifying:
  - When to use `@Inject` + `@ContributesBinding` vs `@Provides` vs `@ContributesTo`.
  - Scope policy (`AppScope` for app-lifetime, ViewModels per nav entry, future `CloudScope` for cred-coupled bindings).
  - The forbidden `object` patterns (no `var | lateinit | MutableStateFlow | MutableSharedFlow | Mutex | AtomicReference | ConcurrentHashMap` inside `object` declarations in main source sets).
  - Pure-function namespaces (`Hasher`, `AlsParser`, `BlobInstaller`, `JournalJson`, `EffortScore`) are explicitly allowed.
  - Pattern to use when adding a non-JVM target (`expect`/`actual` for platform-specific bindings).

### 3.2 What stays manual in `DesktopAppGraph`

These are deliberate carve-outs for the future `CloudScope` PR:
- `SyncQueue` (concrete `SwappableSyncQueue` — orchestrates cloud creds, takes 9 constructor params)
- `LockRepository` (depends on the cloud closure for lease grants)
- `SnapshotRepository` (takes a materialize lambda from the swappable queue)
- `SettingsRepository` (desktop-specific impl backed by `java.util.prefs.Preferences`)
- `appScope`, `catalogHandle`, `catalog` (platform/lifetime concerns)

### 3.3 Out of scope (follow-up PRs)

- **`McpAppGraph`** — replace `app-mcp/Main.kt`'s hand-wiring with a graph that aggregates the same per-module bindings.
- **`CloudScope`** — child graph for cred-coupled bindings; deletes the `as? SwappableSyncQueue` casts.
- **MCP `Tools` multibindings** — `@ContributesIntoMap` for the tool registry.
- **Detekt rule** for forbidden `object` patterns.

---

## 4. Risks & mitigations

**Risk: `metrox-viewmodel-compose` integration friction.** Metro's ViewModel artifact is recent (0.10.x). Mitigation: build Sweers' `compose-navigation-app` sample first to validate the pattern works on JVM/desktop; fall back to manual `ViewModelStoreOwner` resolution if the artifact has issues.

**Risk: `kotlinx-coroutines-swing` interferes with existing dispatchers.** Setting it as a dependency is enough — it just registers the Swing dispatcher. `Dispatchers.IO` and `Dispatchers.Default` are unaffected. Low risk.

**Risk: `SavedStateHandle` flow keys collide with Compose `rememberSaveable` if introduced later.** Mitigation: prefix all keys with the ViewModel class name (`ProjectListViewModel.QUERY`, etc.).

**Risk: `viewModelScope` death cancels in-flight work the user expected to complete.** Specifically: `ProjectDetailViewModel.dispatch(Move)` triggers a repository write — if the user navigates away mid-write, the coroutine dies. Mitigation: writes that must complete go on `appScope` (still injectable as a graph accessor), not `viewModelScope`. Document this carve-out in the policy doc.

**Risk: Tests get harder to read with `Dispatchers.setMain` setup.** Mitigation: add a `MainDispatcherRule`-equivalent JUnit 5 extension at `tests/integration/src/main/kotlin/.../ViewModelTestRule.kt`; or use a top-level helper.

---

## 5. Verification plan

After implementation:

1. **`./gradlew build` clean.** All modules compile, all existing tests pass.
2. **Manual smoke on desktop:**
   - Launch app. Navigate Projects → Project Detail → Timeline → Settings → back to Projects. Confirm state restored (search query persisted across same-screen revisit; cleared between *different* projects in detail view).
   - Open `htop` / Activity Monitor. Confirm CPU drops to ~0% when the window is minimized (proves `collectAsStateWithLifecycle` is pausing flows).
   - Trigger a long-running scan, navigate away mid-scan, confirm scan completes (proves it's on `appScope`, not `viewModelScope`).
3. **`DesktopAppGraph.kt` line count.** From ~325 lines (current) down to ~120 lines (target — much of the file is the `startBackgroundPull` / `autoMaterializeAfterPull` helpers, which stay).
4. **No `as?` casts removed** — the three `SwappableSyncQueue` casts remain; their cleanup is explicitly deferred. (Sanity check: confirm we haven't accidentally touched them.)
5. **`docs/architecture/dependency-injection.md` exists and is committed.**

---

## 6. References

- [Metro design doc](https://zacsweers.github.io/metro/latest/designdoc.html)
- [Metro injection types](https://zacsweers.github.io/metro/latest/injection-types/)
- [Metro `compose-navigation-app` sample](https://github.com/ZacSweers/metro/tree/main/samples/compose-navigation-app)
- [Metro `AssistedViewModel` discussion #1460](https://github.com/ZacSweers/metro/discussions/1460)
- [JetBrains: Common ViewModel for CMP](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-viewmodel.html)
- [JetBrains: Navigation 3 in Compose Multiplatform](https://kotlinlang.org/docs/multiplatform/compose-navigation-3.html)
- [Domen Lanišnik: Scope of ViewModels in Compose Navigation 3](https://medium.com/@domen.lanisnik/scope-of-viewmodels-in-compose-navigation-3-fb0de3aa84e5)
- [Touchlab: Is AndroidX ViewModel the best choice for KMP?](https://touchlab.co/kmp-view-models)
