# Dependency Injection — Sketchbook

**Status:** Adopted 2026-05-06 (Metro KMP ViewModel refactor).
**Stack:** [Metro](https://github.com/ZacSweers/metro) `0.7.x` + [`metrox-viewmodel-compose`](https://github.com/ZacSweers/metro/tree/main/metrox-viewmodel-compose) + JetBrains AndroidX lifecycle KMP fork (`org.jetbrains.androidx.lifecycle:*` 2.10.0).

This doc is the canonical rulebook for DI in this repo. If a PR contradicts it, raise the contradiction; do not silently diverge.

## 1. Scopes

There is exactly one application scope: `com.sketchbook.core.AppScope`.

- Defined in `shared/core/src/commonMain/kotlin/com/sketchbook/core/AppScope.kt` so any module can reference it without a circular dep.
- One root `@DependencyGraph(AppScope::class)` per app shell. Today: `DesktopAppGraph` in `app-desktop`. Future shells (`app-mobile`, `app-mcp`) get their own root graphs that also bind `AppScope`.
- Per-screen scopes are not modelled with `@GraphExtension` today. ViewModels are scoped to their `ViewModelStoreOwner` (the NavEntry / window) by Compose lifecycle — not by Metro. If a future feature needs a true per-screen Metro subgraph, add a `@GraphExtension` then; do not pre-add one.

## 2. Binding services (repos, scanners, coordinators)

Default pattern for any singleton service:

```kotlin
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class SqlProjectRepository(
    private val db: CatalogDb,
    private val fts: ProjectFtsSearcher,
) : ProjectRepository { … }
```

Rules:
- **Bind the interface, not the impl** with `@ContributesBinding`. Callers depend on `ProjectRepository`, not `SqlProjectRepository`.
- **Constructor injection only.** No `companion object` singletons. No service locators.
- **`@SingleIn(AppScope::class)`** on every service that holds state, opens a connection, or starts a coroutine. Stateless adapters can omit it.
- **No qualifier creep.** If two same-typed deps are needed (e.g. two `CoroutineDispatcher`s), keep the rare one as a `@Provides` factory in the graph rather than introducing a qualifier annotation. Revisit if the count grows.

### `commonMain` services that need JVM collaborators

When a `commonMain` impl needs a JVM-only helper, expose a small `fun interface` in `commonMain` and bind the JVM adapter in the app graph. Example: `ProjectFtsSearcher` (commonMain) is implemented by an inline lambda in `DesktopAppGraph` that delegates to `CatalogFts` (jvmMain). This keeps the repo in `commonMain` without dragging SQLDelight JVM types across the seam.

## 3. ViewModels

State holders are KMP `ViewModel`s (`org.jetbrains.androidx.lifecycle:lifecycle-viewmodel`). Acquire via `metrox-viewmodel-compose`.

### 3.1 Defining a ViewModel

```kotlin
@ContributesIntoMap(AppScope::class)
@ViewModelKey
@Inject
class ProjectListViewModel(
    private val repository: ProjectRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun dispatch(intent: Intent) { /* … */ }

    @Immutable
    data class State(/* … */)

    sealed interface Intent { /* … */ }
}
```

Rules:
- **Always `@ContributesIntoMap(AppScope::class) @ViewModelKey @Inject`.** Metro contributes the entry into the multibinding map that `MetroViewModelFactory` reads.
- **Use `viewModelScope`** for VM-owned coroutines. Never accept a `CoroutineScope` parameter — that's the old StateHolder API.
- **`@Immutable` on `State`** so Compose can skip recomposition when the reference is unchanged.
- **Sealed `Intent` + `dispatch(intent)`.** No `accept(intent)`, no public mutators. One entry point.
- **Effects via `SharedFlow<Effect>`** when the screen needs side-effects (open external app, navigate, toast). Collect once at screen root with `LaunchedEffect(vm) { vm.effects.collect { … } }`.
- **No `SavedStateHandle` until the SavedStateRegistry writer is wired on desktop.** The current factory does not persist state across process death; pretending it does is a footgun.

### 3.2 Acquiring a ViewModel in Compose

```kotlin
@Composable
fun ProjectsRoute() {
    val vm: ProjectListViewModel = metroViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    ProjectListScreen(state = state, onIntent = vm::dispatch)
}
```

Rules:
- **Acquire VMs at the smallest reasonable scope** — typically inside the NavEntry composable. Compose hoisting principles apply.
- **Never acquire all VMs at the root.** Hoisting them to `RootContent` ties every screen's lifetime to the window, defeating the point of `viewModelScope`.
- **Sidebar / chrome that reads VM-shaped data** should read directly from repository flows (`graph.proposalsRepository.observe()`). Don't spin up a VM just to populate a badge.
- **`LocalMetroViewModelFactory`** is provided once at the window root in `RootContent`. Composables below it call `metroViewModel<VM>()` without ceremony.

### 3.3 Assisted-injected VMs

When a VM needs a runtime parameter (e.g. a project id), use `@AssistedInject` per the metrox-viewmodel docs and acquire with `metroViewModel<VM> { factory -> factory.create(id) }`. As of 2026-05-06, no production VM uses this — `ProjectDetailViewModel` keeps a `load(id)` API instead. Promote to assisted when the side panel becomes a real route.

## 4. Coordinators (orchestration outside Composables)

Long-running orchestration that doesn't belong to a single screen lives in a `@SingleIn(AppScope::class)` coordinator. Example: `LibraryScanCoordinator` observes `SettingsRepository` for new library roots and kicks scans, exposing a `StateFlow<ScanUiState>` the UI renders. Started once from `Main.kt` after the graph is built.

Rules:
- **Don't put orchestration in Composables.** `RootContent` should be rendering-only; if it had `LaunchedEffect { observe + side-effect }` blocks, those move to a coordinator.
- **`AppScope` lifetime, app-scope `CoroutineScope`.** The graph provides a `CoroutineScope` keyed to the app lifecycle; coordinators inject it.
- **Coordinator state is read with `collectAsStateWithLifecycle`** in Compose, same as a VM's state.

## 5. Coroutines & dispatchers

- Inject `CoroutineDispatcher` from the graph. The default `Dispatchers.IO` is provided as `CoroutineDispatcher` (no qualifier today).
- **Hot views:** `Flow.stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)`.
- **Watcher-style sources:** `channelFlow { … awaitClose { } }`.
- **Long-running work belongs in a repository or coordinator scope, not a VM scope.** VM scopes die with the screen.

## 6. The desktop app graph (anatomy)

`DesktopAppGraph` is the reference assembly. It:

1. Extends `ViewModelGraph` (declares the three multibinding maps that `MetroViewModelFactory` reads).
2. Exposes accessors for top-level singletons that `Main.kt` needs to start up imperatively (`libraryScanCoordinator`, `appScope`, `settingsRepository`).
3. `@Provides` only the things that can't be `@ContributesBinding`'d:
   - Platform-specific factories (`JvmScanner`, `JvmSampleScanner` — they take a `CoroutineDispatcher` we don't want to qualify).
   - Adapter for `ProjectFtsSearcher` → `CatalogFts`.
   - `Dispatchers.IO` as `CoroutineDispatcher`.
4. Does **not** `@Provides` repos, the ViewModel factory, or coordinators — those self-contribute.

If you're adding a service and find yourself writing `@Provides` for it: stop and check whether `@ContributesBinding` would work. The default answer is yes.

## 7. Anti-patterns

| Don't | Do |
|---|---|
| `companion object { val instance = … }` | `@Inject class … @ContributesBinding` |
| `class Foo(scope: CoroutineScope) : ViewModel()` | use `viewModelScope` |
| Acquire all VMs in `RootContent` | `metroViewModel<VM>()` per NavEntry |
| `LaunchedEffect` orchestration in `RootContent` | extract a coordinator |
| Inject the impl class | inject the interface |
| Add a qualifier annotation for one rare collision | keep the rare one as `@Provides` |
| `commonMain` repo importing JVM types | `fun interface` adapter |
| `SavedStateHandle` on desktop today | `MutableStateFlow` until SavedStateRegistry is wired |

## 8. Related docs

- `docs/plans/2026-05-06-metro-kmp-viewmodel-design.md` — design behind this refactor.
- `docs/plans/2026-05-06-metro-kmp-viewmodel-plan.md` — implementation plan.
- `docs/plans/2026-05-05-sync-versioning-design.md` §2.1 — original tech-stack table (with the 2026-05-06 decision update).
- Metro: <https://github.com/ZacSweers/metro>
- Metro ViewModel sample: <https://github.com/ZacSweers/metro/blob/main/samples/android-app/src/main/kotlin/dev/zacsweers/metro/sample/android/CounterViewModel.kt>
