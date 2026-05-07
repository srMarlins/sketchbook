# Onboarding Screen Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a 5-step (welcome + 3 setup + done) full-screen onboarding flow for first-time Sketchbook launch — Projects roots (required), sample roots, plugin folders (OS defaults), and a final scanning hand-off. Skippable per-step and via a "use defaults" escape hatch. Notebook-themed, Compose-native animations.

**Architecture:** New `shared/feature-onboarding` module with a Metro/KMP `OnboardingViewModel`. A `LaunchGate` in `app-desktop` reads `Settings.firstRunCompletedAt` and routes to onboarding or the main app. Plugin folders move from hardcoded JVM paths in `JvmPluginPresenceProbe` to a settings-backed list. Soft re-prompt banner on Home for skipped optional steps. No new libraries — `JFileChooser` callback for desktop, Compose Canvas + `animateFloatAsState` for animations.

**Tech Stack:** Kotlin 2.3, Compose Multiplatform 1.11, Metro DI 0.7, SQLDelight 2.3, kotlin.test + Turbine + hand-written fakes. Reference design doc: `docs/plans/2026-05-06-onboarding-screen-design.md`.

**Worktree:** `.claude/worktrees/onboarding-screen/` on branch `feat/onboarding-screen`. Run all `./gradlew` commands from this worktree directory.

**Skills to consult during implementation:**
- @superpowers:test-driven-development for every task with a `Test:` line
- @superpowers:systematic-debugging if anything blocks
- @superpowers:verification-before-completion before claiming task done
- @superpowers:requesting-code-review at the end

**Memory constraints (load-bearing):**
- No new libraries, no Lottie, no MVI framework, no screenshot tests.
- Reuse existing color tokens — do not introduce new colors.
- No batch checkpoints during execution; drive through all tasks, browser-verify visually as you go.
- Layer onto existing UI; the only "new surface" allowed here is the onboarding takeover itself, justified by the design.
- One PR at the end, not a sequence.

---

## Task 0: Verify the worktree and baseline build

**Files:** None (sanity check)

**Step 1:** Confirm cwd is the worktree.

```bash
git rev-parse --show-toplevel
git branch --show-current
```

Expected: path ends in `.claude/worktrees/onboarding-screen` and branch is `feat/onboarding-screen`.

**Step 2:** Baseline build to make sure nothing's broken before we start.

```bash
./gradlew assemble -q
```

Expected: BUILD SUCCESSFUL.

**Step 3:** Run the existing test suite to capture the green baseline.

```bash
./gradlew allTests -q
```

Expected: BUILD SUCCESSFUL. Note any flakes for triage if needed.

---

## Task 1: Extend `Settings` with onboarding fields

**Files:**
- Modify: `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/SettingsRepository.kt`

**Step 1: Add new fields to `Settings` and a new data class.**

In `SettingsRepository.kt`, add to the `Settings` data class:

```kotlin
data class Settings(
    val libraryRoots: List<LibraryRoot>,
    val cloudConfigured: Boolean,
    val selfContainedProjects: Set<ProjectUuid>,
    val cacheSettings: BlobCacheSettings = BlobCacheSettings.Default,
    val cloudCredentialJson: String? = null,
    val cloudBucket: String? = null,
    /** Wall-clock instant the user finished onboarding (or skipped to defaults). Null until then. */
    val firstRunCompletedAt: kotlinx.datetime.Instant? = null,
    /** Sticky flags for soft re-prompt banners on Home after onboarding completes. */
    val onboardingSkipped: OnboardingSkipFlags = OnboardingSkipFlags(),
    /**
     * User-configurable plugin install directories. Empty = use platform defaults
     * (the JVM probe falls back to `defaultInstalledDirs()` when this list is empty).
     */
    val pluginFolders: List<String> = emptyList(),
) { /* …existing cloudReady val… */ }

data class OnboardingSkipFlags(
    val samplesSkipped: Boolean = false,
    val samplesPromptDismissed: Boolean = false,
)
```

**Step 2: Add new methods to the `SettingsRepository` interface.**

```kotlin
suspend fun markFirstRunComplete(skipFlags: OnboardingSkipFlags): Result<Unit>
suspend fun dismissOnboardingPrompt(kind: OnboardingPromptKind): Result<Unit>
suspend fun setPluginFolders(folders: List<String>): Result<Unit>

enum class OnboardingPromptKind { Samples }
```

**Step 3:** Compile-check.

```bash
./gradlew :shared:repository:compileKotlinMetadata -q
```

Expected: BUILD SUCCESSFUL — every existing `: SettingsRepository` impl will fail on the new abstract methods. That's the next task. (If the metadata target doesn't exist, use `:shared:repository:compileCommonMainKotlinMetadata`.)

**Step 4: Commit.**

```bash
git add shared/repository/src/commonMain/kotlin/com/sketchbook/repo/SettingsRepository.kt
git commit -m "feat(repository): add onboarding fields + plugin folders to Settings"
```

---

## Task 2: Implement the new `SettingsRepository` methods in `PreferencesSettingsRepository`

**Files:**
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/repo/PreferencesSettingsRepository.kt`
- Modify: `app-desktop/src/jvmTest/kotlin/com/sketchbook/desktop/repo/PreferencesSettingsRepositoryTest.kt`
- Modify: `shared/feature-settings/src/commonTest/kotlin/com/sketchbook/featuresettings/SettingsViewModelTest.kt` (the in-test `FakeRepo` will need stubs for the new methods)

**Step 1: Write failing tests for the new methods.**

In `PreferencesSettingsRepositoryTest.kt`, add three tests:

```kotlin
@Test
fun `markFirstRunComplete persists timestamp and skip flags`() = runTest {
    val repo = newRepo()
    val flags = OnboardingSkipFlags(samplesSkipped = true)

    repo.markFirstRunComplete(flags).getOrThrow()

    val snapshot = repo.observe().first()
    assertNotNull(snapshot.firstRunCompletedAt)
    assertEquals(flags, snapshot.onboardingSkipped)
}

@Test
fun `dismissOnboardingPrompt flips the matching flag`() = runTest {
    val repo = newRepo()
    repo.markFirstRunComplete(OnboardingSkipFlags(samplesSkipped = true)).getOrThrow()

    repo.dismissOnboardingPrompt(OnboardingPromptKind.Samples).getOrThrow()

    val snapshot = repo.observe().first()
    assertTrue(snapshot.onboardingSkipped.samplesPromptDismissed)
}

@Test
fun `setPluginFolders persists list and observe re-emits`() = runTest {
    val repo = newRepo()
    val folders = listOf("/Users/me/Plugins", "/Library/Audio/Plug-Ins/VST3")

    repo.setPluginFolders(folders).getOrThrow()

    assertEquals(folders, repo.observe().first().pluginFolders)
}
```

(`newRepo()` is the existing helper in the file. Reuse the same Preferences node pattern.)

**Step 2:** Run them to confirm they fail.

```bash
./gradlew :app-desktop:jvmTest --tests "*PreferencesSettingsRepositoryTest*" -q
```

Expected: 3 failures (compile or assertion).

**Step 3: Implement the new methods.**

In `PreferencesSettingsRepository.kt`, choose preference keys (follow whatever convention is already in that file — likely `FIRST_RUN_COMPLETED_AT_KEY = "first_run_completed_at"`, `SAMPLES_SKIPPED_KEY = "onboarding_samples_skipped"`, etc.). Persist `firstRunCompletedAt` as the ISO string from `Instant.toString()`; null absence as not-present. Persist `pluginFolders` as a newline-joined string in a single key (mirror existing list-handling) or as N indexed keys, whichever matches the file's existing pattern. After each write, emit through whatever `MutableStateFlow<Settings>` `observe()` is backed by.

**Step 4:** Update the `FakeRepo` in `SettingsViewModelTest.kt` with the new method stubs (return `Result.success(Unit)` and update internal state).

**Step 5:** Run tests.

```bash
./gradlew :app-desktop:jvmTest --tests "*PreferencesSettingsRepositoryTest*" -q
./gradlew :shared:feature-settings:jvmTest -q
```

Expected: all green.

**Step 6: Commit.**

```bash
git add app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/repo/PreferencesSettingsRepository.kt \
        app-desktop/src/jvmTest/kotlin/com/sketchbook/desktop/repo/PreferencesSettingsRepositoryTest.kt \
        shared/feature-settings/src/commonTest/kotlin/com/sketchbook/featuresettings/SettingsViewModelTest.kt
git commit -m "feat(repository): persist onboarding flags + plugin folders in PreferencesSettingsRepository"
```

---

## Task 3: Wire `JvmPluginPresenceProbe` to read plugin folders from settings

**Files:**
- Modify: `shared/sync-io/src/jvmMain/kotlin/com/sketchbook/syncio/JvmPluginPresenceProbe.kt`
- Modify: corresponding test in `shared/sync-io/src/jvmTest/`

The probe currently has `private var installedDirs: List<Path> = defaultInstalledDirs()`. Make it observe `SettingsRepository.observe().pluginFolders`, fall back to defaults when empty.

**Step 1: Write a failing test** that constructs the probe with a fake `SettingsRepository` emitting a non-empty `pluginFolders` list and asserts those paths are walked instead of the OS defaults. Reuse any existing fake plugin directory under `build/tmp/` that the existing test uses.

**Step 2:** Run, expect fail.

**Step 3: Inject `SettingsRepository` into `JvmPluginPresenceProbe`.** Add it to the constructor, change `installedDirs` to be resolved per-`probe()` call:

```kotlin
override suspend fun probe(): PluginPresenceProbe.ProbeResult = withContext(ioDispatcher) {
    val configured = settings.observe().first().pluginFolders.map { Paths.get(it) }
    val dirs = configured.ifEmpty { defaultInstalledDirs() }
    val installedTokens = collectInstalledTokens(dirs)
    // …rest unchanged
}
```

Update the `forTest` factory to accept a fake repo and any existing constructor uses in `DesktopAppGraph.kt` (the Metro graph will resolve `SettingsRepository` automatically — verify by searching for `JvmPluginPresenceProbe(` and adding the dep if there's a manual construction).

**Step 4:** Run tests.

```bash
./gradlew :shared:sync-io:jvmTest -q
```

Expected: green.

**Step 5: Commit.**

```bash
git add shared/sync-io/src/jvmMain/kotlin/com/sketchbook/syncio/JvmPluginPresenceProbe.kt shared/sync-io/src/jvmTest/
git commit -m "feat(plugins): JvmPluginPresenceProbe reads pluginFolders from Settings, falls back to OS defaults"
```

---

## Task 4: Create the `feature-onboarding` module skeleton

**Files:**
- Create: `shared/feature-onboarding/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Create: `shared/feature-onboarding/src/commonMain/kotlin/com/sketchbook/featureonboarding/.gitkeep`
- Create: `shared/feature-onboarding/src/commonTest/kotlin/com/sketchbook/featureonboarding/.gitkeep`

**Step 1: Copy `shared/feature-settings/build.gradle.kts` verbatim** to the new module path. This gives us the same Compose + Metro + lifecycle setup.

**Step 2: Add to `settings.gradle.kts`** alongside the other features:

```kotlin
include(
    // …existing entries…
    ":shared:feature-onboarding",
    // …existing entries…
)
```

**Step 3:** Verify the module is registered.

```bash
./gradlew :shared:feature-onboarding:tasks -q
```

Expected: BUILD SUCCESSFUL with task list printed.

**Step 4: Commit.**

```bash
git add settings.gradle.kts shared/feature-onboarding/
git commit -m "build: scaffold feature-onboarding module"
```

---

## Task 5: Platform-aware OS defaults (`expect`/`actual`)

**Files:**
- Create: `shared/feature-onboarding/src/commonMain/kotlin/com/sketchbook/featureonboarding/OsDefaults.kt`
- Create: `shared/feature-onboarding/src/jvmMain/kotlin/com/sketchbook/featureonboarding/OsDefaults.jvm.kt`
- Create: `shared/feature-onboarding/src/jvmTest/kotlin/com/sketchbook/featureonboarding/OsDefaultsTest.kt`

**Step 1: Define `expect` declarations.**

```kotlin
package com.sketchbook.featureonboarding

internal expect fun defaultPluginFolders(): List<String>
internal expect fun defaultProjectsRootSuggestion(): String?
internal expect fun defaultSamplesRootSuggestion(): String?
```

**Step 2: Write failing tests for the JVM actuals.**

```kotlin
@Test
fun `default plugin folders are non-empty on Windows`() {
    val previous = System.getProperty("os.name")
    System.setProperty("os.name", "Windows 11")
    try {
        val result = defaultPluginFolders()
        assertTrue(result.any { it.contains("VST3", ignoreCase = true) })
    } finally {
        System.setProperty("os.name", previous)
    }
}

@Test
fun `default projects root suggests Documents Live Projects on Windows`() {
    System.setProperty("os.name", "Windows 11")
    val r = defaultProjectsRootSuggestion()
    assertNotNull(r)
    assertTrue(r.endsWith("Live Projects") || r.endsWith("Live Projects"))
}

// …mirror for Mac (`Mac OS X`) and unknown (returns empty/null).
```

**Step 3:** Run, expect fail (no actual yet).

**Step 4: Implement JVM actual** that branches on `System.getProperty("os.name")`:

```kotlin
internal actual fun defaultPluginFolders(): List<String> {
    val os = System.getProperty("os.name").orEmpty()
    val home = System.getProperty("user.home").orEmpty()
    return when {
        os.startsWith("Windows", true) -> listOf(
            "C:/Program Files/Common Files/VST3",
            "C:/Program Files/VstPlugins",
            "C:/Program Files/Common Files/VST2",
        )
        os.startsWith("Mac", true) -> listOf(
            "/Library/Audio/Plug-Ins/VST3",
            "/Library/Audio/Plug-Ins/VST",
            "/Library/Audio/Plug-Ins/Components",
            "$home/Library/Audio/Plug-Ins/VST3",
        )
        else -> emptyList()
    }
}

internal actual fun defaultProjectsRootSuggestion(): String? { /* analogous */ }
internal actual fun defaultSamplesRootSuggestion(): String? { /* nullable; empty for now */ }
```

**Step 5:** Run tests, expect green.

**Step 6: Commit.**

```bash
git add shared/feature-onboarding/src/
git commit -m "feat(onboarding): platform-aware OS defaults for plugin/projects/samples"
```

---

## Task 6: `OnboardingViewModel` — state, intents, basic transitions

**Files:**
- Create: `shared/feature-onboarding/src/commonMain/kotlin/com/sketchbook/featureonboarding/OnboardingViewModel.kt`
- Create: `shared/feature-onboarding/src/commonTest/kotlin/com/sketchbook/featureonboarding/OnboardingViewModelTest.kt`

**Step 1: Write failing tests** for the state model. Cover:

- Initial state: `currentIndex == 0`, all root lists empty, `pluginFolders == defaultPluginFolders()`, `canContinue == true` on Welcome.
- After `AddProjectsRoot("/foo")`, the path appears in `projectsRoots` exactly once.
- On the `ProjectsRoots` step (index 1), `canContinue == false` when empty, `true` after one entry.
- `Continue` advances `currentIndex` by 1.
- `Skip` advances by 1 and does NOT clear the corresponding step's data (per design: skip == leave as-is).
- On the `Welcome` step (index 0), `Skip` is a no-op (Welcome is not skippable).

```kotlin
class OnboardingViewModelTest {
    private fun newVm(repo: SettingsRepository = FakeSettingsRepository()) = OnboardingViewModel(
        repository = repo,
        scanCoordinator = FakeScanCoordinator(),
    )

    @Test
    fun `initial state has Welcome at index 0 with default plugin folders`() = runTest {
        val vm = newVm()
        val s = vm.state.value
        assertEquals(0, s.currentIndex)
        assertEquals(OnboardingStep.Welcome, s.steps[0])
        assertTrue(s.pluginFolders.isNotEmpty())
        assertTrue(s.canContinue)
    }

    @Test
    fun `ProjectsRoots step requires at least one root for canContinue`() = runTest {
        val vm = newVm()
        vm.dispatch(OnboardingIntent.Continue)               // → ProjectsRoots
        assertFalse(vm.state.value.canContinue)
        vm.dispatch(OnboardingIntent.AddProjectsRoot("/foo"))
        assertTrue(vm.state.value.canContinue)
    }

    // …additional cases per the bullet list above
}
```

**Step 2:** Run, expect fail (no class yet).

**Step 3: Implement `OnboardingViewModel`.**

```kotlin
package com.sketchbook.featureonboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.repo.OnboardingPromptKind
import com.sketchbook.repo.OnboardingSkipFlags
import com.sketchbook.repo.SettingsRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Inject
class OnboardingViewModel(
    private val repository: SettingsRepository,
    private val scanCoordinator: ScanTrigger,
) : ViewModel() {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun dispatch(intent: OnboardingIntent) {
        when (intent) {
            is OnboardingIntent.AddProjectsRoot -> _state.update { s ->
                s.copy(projectsRoots = (s.projectsRoots + intent.path).distinct())
                    .recomputeCanContinue()
            }
            is OnboardingIntent.RemoveProjectsRoot -> _state.update { s ->
                s.copy(projectsRoots = s.projectsRoots - intent.path).recomputeCanContinue()
            }
            is OnboardingIntent.AddSampleRoot -> _state.update { s ->
                s.copy(sampleRoots = (s.sampleRoots + intent.path).distinct())
            }
            is OnboardingIntent.RemoveSampleRoot -> _state.update { s ->
                s.copy(sampleRoots = s.sampleRoots - intent.path)
            }
            is OnboardingIntent.AddPluginFolder -> _state.update { s ->
                s.copy(pluginFolders = (s.pluginFolders + intent.path).distinct())
            }
            is OnboardingIntent.RemovePluginFolder -> _state.update { s ->
                s.copy(pluginFolders = s.pluginFolders - intent.path)
            }
            OnboardingIntent.UsePluginDefaults -> _state.update { it.copy(pluginFolders = defaultPluginFolders()) }
            OnboardingIntent.Continue, OnboardingIntent.Skip -> advance()
            OnboardingIntent.SkipAllUseDefaults -> _state.update { s ->
                s.copy(
                    pluginFolders = defaultPluginFolders().takeIf { s.pluginFolders.isEmpty() } ?: s.pluginFolders,
                    currentIndex = s.steps.indexOf(OnboardingStep.Done),
                ).recomputeCanContinue()
            }
            OnboardingIntent.Finish -> finish()
        }
    }

    private fun advance() = _state.update { s ->
        if (s.currentIndex >= s.steps.lastIndex) s
        else s.copy(currentIndex = s.currentIndex + 1).recomputeCanContinue()
    }

    private fun finish() {
        viewModelScope.launch {
            val s = _state.value
            s.projectsRoots.forEach { runCatching { repository.upsertRoot(LibraryRoot.Projects(it)) } }
            s.sampleRoots.forEach { runCatching { repository.upsertRoot(LibraryRoot.UserSamples(it)) } }
            runCatching { repository.setPluginFolders(s.pluginFolders) }
            runCatching {
                repository.markFirstRunComplete(
                    OnboardingSkipFlags(samplesSkipped = s.sampleRoots.isEmpty()),
                )
            }
            scanCoordinator.triggerScan()
        }
    }

    private fun initialState(): OnboardingState {
        val steps = listOf(
            OnboardingStep.Welcome,
            OnboardingStep.ProjectsRoots,
            OnboardingStep.SampleRoots,
            OnboardingStep.PluginFolders,
            OnboardingStep.Done,
        )
        return OnboardingState(
            steps = steps,
            currentIndex = 0,
            projectsRoots = emptyList(),
            sampleRoots = emptyList(),
            pluginFolders = defaultPluginFolders(),
            canContinue = true,
        )
    }

    private fun OnboardingState.recomputeCanContinue(): OnboardingState =
        copy(canContinue = when (steps[currentIndex]) {
            OnboardingStep.ProjectsRoots -> projectsRoots.isNotEmpty()
            else -> true
        })
}

interface ScanTrigger { fun triggerScan() }
```

Plus the `OnboardingState`, `OnboardingStep`, `OnboardingIntent` declarations in the same package (per the design doc).

**Step 4:** Run tests.

```bash
./gradlew :shared:feature-onboarding:jvmTest -q
```

Expected: green.

**Step 5: Commit.**

```bash
git add shared/feature-onboarding/src/
git commit -m "feat(onboarding): OnboardingViewModel state, intents, transitions"
```

---

## Task 7: `OnboardingViewModel` — skip-all and Finish flow

**Files:**
- Modify: `shared/feature-onboarding/src/commonTest/kotlin/com/sketchbook/featureonboarding/OnboardingViewModelTest.kt`

**Step 1: Add tests for `SkipAllUseDefaults` and `Finish`:**

- `SkipAllUseDefaults` from index 2 with an entered Projects root preserves it, sets `currentIndex` to Done, leaves samples empty, plugin folders defaulted.
- `Finish` calls `repository.upsertRoot` once per Projects path and once per Samples path, with the correct `LibraryRoot` subtype.
- `Finish` calls `setPluginFolders` with the current list.
- `Finish` calls `markFirstRunComplete` with `samplesSkipped = true` when sampleRoots is empty.
- `Finish` triggers the scan exactly once.
- A failing `upsertRoot` for one path doesn't prevent later calls from running (use a fake that fails for a specific path).

Use a `RecordingFakeSettingsRepository` (test-only; record every method call as a `sealed class Call`).

**Step 2:** Run, expect fail.

**Step 3:** Whatever's missing — likely the `RecordingFake` class — add it. Implementation in Task 6 should already cover the production logic.

**Step 4:** Run, expect green.

**Step 5: Commit.**

```bash
git add shared/feature-onboarding/src/commonTest/
git commit -m "test(onboarding): cover skip-all and Finish flow"
```

---

## Task 8: `LaunchGate` in `app-desktop`

**Files:**
- Create: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/LaunchGate.kt`
- Create: `app-desktop/src/jvmTest/kotlin/com/sketchbook/desktop/LaunchGateTest.kt`

**Step 1: Write failing tests:**

```kotlin
class LaunchGateTest {
    @Test
    fun `null firstRunCompletedAt routes to Onboarding`() = runTest {
        val gate = LaunchGate(FakeSettings(firstRunCompletedAt = null))
        assertEquals(LaunchDecision.Onboarding, gate.resolve())
    }

    @Test
    fun `set firstRunCompletedAt routes to MainApp`() = runTest {
        val gate = LaunchGate(FakeSettings(firstRunCompletedAt = Clock.System.now()))
        assertEquals(LaunchDecision.MainApp, gate.resolve())
    }
}
```

**Step 2:** Run, expect fail.

**Step 3: Implement.**

```kotlin
package com.sketchbook.desktop

import com.sketchbook.repo.SettingsRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first

sealed interface LaunchDecision {
    data object Onboarding : LaunchDecision
    data object MainApp : LaunchDecision
    // Future: data class Migration(...) when v0.1 → v1 ships
}

@Inject
class LaunchGate(private val settings: SettingsRepository) {
    suspend fun resolve(): LaunchDecision =
        if (settings.observe().first().firstRunCompletedAt == null) LaunchDecision.Onboarding
        else LaunchDecision.MainApp
}
```

**Step 4:** Run, expect green.

**Step 5: Commit.**

```bash
git add app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/LaunchGate.kt app-desktop/src/jvmTest/kotlin/com/sketchbook/desktop/LaunchGateTest.kt
git commit -m "feat(app): LaunchGate selects Onboarding vs MainApp from firstRunCompletedAt"
```

---

## Task 9: `OnboardingScreen` scaffolding (no animations yet)

**Files:**
- Create: `shared/feature-onboarding/src/commonMain/kotlin/com/sketchbook/featureonboarding/OnboardingScreen.kt`

Build the outer scaffold only — `PaperPage` background, centered ~520dp column, dot indicator at top, footer with `Skip all and use defaults` link, an `AnimatedContent` switching between step composables (each step a placeholder `Text("Step N")` for now). No animations beyond `AnimatedContent`'s default fade — those land in Task 14.

Public API:

```kotlin
@Composable
fun OnboardingScreen(
    vm: OnboardingViewModel,
    onPickFolder: () -> String?,
    onPickFile: () -> String?,                    // unused for now; reserved for future cloud step
    modifier: Modifier = Modifier,
)
```

Reuse existing components from `:shared:ui-shared` (`PaperPage`, `Surface`, `Button`, `Text`, `AppTheme`). Match the `SettingsScreen` outer-column pattern (centered, max-width 520dp).

The dot indicator is an inline `Row` of N small `Box(Modifier.size(8.dp).clip(CircleShape).background(...))` — filled vs hollow keyed by `state.currentIndex`. No animation yet.

The footer: `Row` with `Text("Skip all and use defaults")` (clickable, dispatches `SkipAllUseDefaults`) on the bottom-left. Right side blank for now.

No tests at this layer (Compose UI). Visual verification later.

**Commit:**

```bash
git add shared/feature-onboarding/src/commonMain/
git commit -m "feat(onboarding): OnboardingScreen scaffold with dot indicator + footer"
```

---

## Task 10: Step composables — Welcome and Done

**Files:**
- Create: `shared/feature-onboarding/src/commonMain/kotlin/com/sketchbook/featureonboarding/steps/WelcomeStep.kt`
- Create: `shared/feature-onboarding/src/commonMain/kotlin/com/sketchbook/featureonboarding/steps/DoneStep.kt`

`WelcomeStep`: heading "Welcome to Sketchbook.", subhead "Point it at your library and it'll do the rest. Takes a minute.", primary button "Get started" → `Continue`. Use `AppTheme.typography.title` for heading.

`DoneStep`: heading "Done.", subhead "Scanning starts now — you can use Sketchbook while it runs.", `InkLoading` indicator from `:shared:ui-shared` with copy "Reading your library…", primary button "Open Sketchbook" → `Finish`.

Wire both into `OnboardingScreen`'s `AnimatedContent` `when (step)` block.

**Commit:**

```bash
git add shared/feature-onboarding/
git commit -m "feat(onboarding): Welcome and Done step composables"
```

---

## Task 11: `ProjectsRootsStep` composable

**Files:**
- Create: `shared/feature-onboarding/src/commonMain/kotlin/com/sketchbook/featureonboarding/steps/ProjectsRootsStep.kt`

Heading "Where are your Ableton projects?", subhead "Add one folder or several." OS-default chip below the heading: a small `Surface`-styled clickable pill that, when clicked, dispatches `AddProjectsRoot(defaultProjectsRootSuggestion()!!)` and then hides itself (or just becomes redundant — the user sees it appear in the row list).

Below: column of folder rows rendered as `LibraryRootCard` from `feature-settings` (extract it to `:shared:ui-shared` if it's currently private to feature-settings — check first; if extraction is required, do it as part of this task since it's the natural moment, and add a brief test in `ui-shared`).

`+ Add folder` button below the rows → calls `onPickFolder()` and dispatches `AddProjectsRoot(picked)` if non-null.

Primary action: `Continue` button, disabled when `state.canContinue` is false. No `Skip` link on this step (required).

**Commit:**

```bash
git add shared/feature-onboarding/ shared/ui-shared/   # if LibraryRootCard moved
git commit -m "feat(onboarding): ProjectsRootsStep with multi-folder picker"
```

---

## Task 12: `SampleRootsStep` and `PluginFoldersStep`

**Files:**
- Create: `shared/feature-onboarding/src/commonMain/kotlin/com/sketchbook/featureonboarding/steps/SampleRootsStep.kt`
- Create: `shared/feature-onboarding/src/commonMain/kotlin/com/sketchbook/featureonboarding/steps/PluginFoldersStep.kt`

`SampleRootsStep`: identical pattern to `ProjectsRootsStep`, heading "Sample folders?", primary `Continue`, secondary `Skip` text link (dispatches `Skip`).

`PluginFoldersStep`: heading "Plugin folders.", subhead "Used to flag projects with missing plugins." Pre-filled rows (from `state.pluginFolders`). `Use defaults` button (dispatches `UsePluginDefaults`). `+ Add folder` button. Primary `Continue`, secondary `Skip` link.

**Commit:**

```bash
git add shared/feature-onboarding/
git commit -m "feat(onboarding): SampleRootsStep + PluginFoldersStep"
```

---

## Task 13: Wire `OnboardingScreen` into `app-desktop`

**Files:**
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/Main.kt` (or whichever file owns the top-level composable — locate by searching for `setContent {`)
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt` (Metro graph) — provide `OnboardingViewModel` and `ScanTrigger`. `ScanTrigger` impl wraps `LibraryScanCoordinator.start()` (or whatever the coordinator's "start a scan now" method is — check `LibraryScanCoordinator.kt`).

**Step 1: Implement `ScanTrigger` adapter in `app-desktop`** (a tiny class binding `ScanTrigger` to `LibraryScanCoordinator`).

**Step 2: Update Main** — replace the unconditional `RootContent(backStack)` with:

```kotlin
val launchGate: LaunchGate = graph.launchGate
val decision = produceState<LaunchDecision?>(null) { value = launchGate.resolve() }
when (val d = decision.value) {
    null -> { /* boot splash; brief — keep PaperPage with InkLoading */ }
    LaunchDecision.Onboarding -> {
        val onboardingVm: OnboardingViewModel = metroViewModel()
        OnboardingScreen(
            vm = onboardingVm,
            onPickFolder = { pickFolderJvm() },           // wraps JFileChooser DIRECTORIES_ONLY
            onPickFile = { pickFileJvm() },
        )
        // Re-resolve after Finish — observe firstRunCompletedAt and flip when set.
        LaunchedEffect(Unit) {
            graph.settingsRepository.observe()
                .map { it.firstRunCompletedAt != null }
                .filter { it }
                .first()
            // trigger recomposition by updating decision
        }
    }
    LaunchDecision.MainApp -> RootContent(backStack)
}
```

Better than `produceState` is to expose `decision: StateFlow<LaunchDecision>` from a tiny `LaunchDecisionHolder` that observes `firstRunCompletedAt`. Pick whichever lines up with the existing `RootChromeViewModel` style.

**Step 3:** Pull existing `JFileChooser` use out of `RootContent.kt:89` into a small `pickFolderJvm()` helper if it makes sense; otherwise inline.

**Step 4:** Build.

```bash
./gradlew :app-desktop:assemble -q
```

Expected: BUILD SUCCESSFUL.

**Step 5: Commit.**

```bash
git add app-desktop/
git commit -m "feat(app): wire OnboardingScreen into Main via LaunchGate"
```

---

## Task 14: Animations

**Files:**
- Modify: each step composable + `OnboardingScreen.kt`
- Possibly create: `shared/feature-onboarding/src/commonMain/kotlin/com/sketchbook/featureonboarding/anim/` with small helpers (`TypingHeading.kt`, `InkUnderline.kt`, `StampCard.kt`, `InkDots.kt`).

Implement the seven moments listed in the design doc § "Specific moments":

1. Heading types itself in word-by-word, 60ms stagger, 250ms fade per word.
2. Ink underline draws under focused folder picker / text field, 220ms `EaseOut`.
3. Folder card stamps in (scale 0.92→1.0 spring + 180ms fade).
4. Progress dots ink-fill on step change (radial fill from center).
5. Page-turn between steps — 16dp horizontal slide accent on top of the existing fade-through. Use the `togetherWith` pattern from `RootContent.kt:99-102`.
6. Done step `InkLoading` already exists.
7. Text field cursor blinks soft (700ms cycle, 0.4 → 1.0) on the `+ Add folder` text inputs.

All animations are `Compose-native` — no new dependencies. Easing curves per design doc:
- Entries: `CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)` (emphasized decelerate).
- Exits: `tween(300, FastOutSlowInEasing)`.
- Stagger: 80ms gaps, total entrance under 600ms.
- No springs except the folder-card stamp.

After each animation lands, build the app and visually confirm before moving to the next:

```bash
./gradlew :app-desktop:run -q
```

Walk through onboarding (delete `firstRunCompletedAt` from preferences first if needed) and verify the animation reads correctly.

**Commit per animation OR one combined commit** — your call based on how cleanly each lands. Tag them:

```bash
git commit -m "feat(onboarding): typing-in heading animation"
git commit -m "feat(onboarding): ink underline on focused inputs"
# …etc
```

---

## Task 15: Soft re-prompt banner on Home

**Files:**
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootChromeViewModel.kt` — expose `pendingOnboardingPrompt: StateFlow<OnboardingPromptKind?>` derived from `Settings.onboardingSkipped`.
- Create: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/OnboardingPromptBanner.kt` — small composable, `Surface` with `tintCream`, one line of copy + `×`. Reuses existing tokens.
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt` — render the banner above the projects list when `pendingOnboardingPrompt != null`. Banner click navigates to Settings; `×` dispatches `dismissOnboardingPrompt(Samples)`.

The flag logic for `pendingOnboardingPrompt`: emit `Samples` iff `samplesSkipped && !samplesPromptDismissed`, else null.

**Step 1: Test in `RootChromeViewModelTest`** (or whichever exists) that the flow transitions correctly across (`samplesSkipped` true/false) × (`promptDismissed` true/false).

**Step 2: Commit.**

```bash
git commit -m "feat(onboarding): soft re-prompt banner for skipped sample folders"
```

---

## Task 16: End-to-end manual smoke test + screenshots

**Files:** None (verification step).

**Step 1: Wipe onboarding state.** In a clean test environment:

```bash
# Find the prefs node — Java prefs on Windows live in HKCU\Software\JavaSoft\Prefs\com\sketchbook\...
# Mac: ~/Library/Preferences/com.sketchbook.*.plist
# Easiest: add a dev-only main arg `--reset-first-run` and use it. If that's overkill, manually delete the relevant pref keys.
```

For this task, add a temporary command-line arg `--reset-first-run` to `Main.kt` that calls a test-only `SettingsRepository.resetFirstRun()` method (add it; behind a `@Suppress("UnusedPrivateMember")` if you don't want to expose it). Or simpler: surface a "Run setup again" menu item in the existing `Help` menu (which the design says should exist anyway for transparency).

**Step 2: Launch the app.**

```bash
./gradlew :app-desktop:run
```

Walk through every step. Take a screenshot of:

1. Welcome
2. Projects roots — empty
3. Projects roots — with one folder added
4. Sample roots — empty
5. Plugin folders — defaults pre-filled
6. Done
7. Home with the "Add a samples folder later?" banner

Save screenshots to `docs/plans/screenshots/onboarding/` and reference them from the PR.

**Step 3: Skip-all path.** Restart with state cleared, hit "Skip all and use defaults" from the Projects step (since Step 0 has no skip-all link visible per design — actually Welcome's primary action is "Get started", footer skip-all is on every step ≥ 1). Verify it lands on Done with the user-entered Projects path preserved and plugin folders defaulted.

**Step 4: Returning-user path.** Restart without clearing — confirm the app boots straight to Home with no onboarding.

**Step 5: Verify scan kicks off.** After finishing onboarding with a real Projects folder, confirm the scan ribbon appears and indexes the projects.

**Step 6:** Run all tests + lint as a final check.

```bash
./gradlew allTests detekt -q
```

Expected: BUILD SUCCESSFUL.

**Step 7: Commit any fixes from the smoke test.** No commit if everything passes.

---

## Task 17: PR

**Files:** None (delivery).

**Step 1: One-shot review.** Use @superpowers:requesting-code-review on the diff before opening the PR.

```bash
git diff main...HEAD --stat
```

Sanity-check the file count matches the plan's "File touch list" in the design doc.

**Step 2: Push and open PR.**

```bash
git push -u origin feat/onboarding-screen
gh pr create --title "feat: first-launch onboarding screen" --body "$(cat <<'EOF'
## Summary
- New full-screen onboarding flow (welcome + 3 setup steps + done) for first-launch users
- LaunchGate routes brand-new installs to onboarding, returning users straight to Home
- Plugin folders move from hardcoded JVM paths to user-configurable list in Settings
- Soft re-prompt banner on Home for users who skipped sample folders

Design doc: `docs/plans/2026-05-06-onboarding-screen-design.md`

## Test plan
- [x] Unit: OnboardingViewModel state transitions, skip-all, Finish flow
- [x] Unit: LaunchGate decision logic
- [x] Unit: PreferencesSettingsRepository persistence of new fields
- [x] Unit: JvmPluginPresenceProbe reads from Settings, falls back to defaults
- [x] Manual: walked full flow, screenshots in `docs/plans/screenshots/onboarding/`
- [x] Manual: skip-all path lands on Done with entered data preserved
- [x] Manual: returning-user path skips onboarding entirely
- [x] Manual: scan kicks off after Finish
EOF
)"
```

Per memory: branch protection is off, no required reviews. Once green, merge.

---

## Decision log

- **No Lottie / Compottie:** native Compose hits the bar, avoids palette drift, respects "no unnecessary libraries" memory.
- **`LibraryRootCard` extraction to `ui-shared` is conditional** — only do it if the card is currently private to `feature-settings`. Otherwise just import.
- **Plugin folders model:** simple `List<String>`. Empty list = use OS defaults. Non-empty = use those exact paths.
- **`firstRunCompletedAt` is the single onboarding gate.** The migration arm is *designed-for* via `LaunchDecision`'s sealed shape but not built — pre-release, no v0.1 users to migrate.
- **No confirmation modal on "Skip all and use defaults"** — every choice is reversible in Settings.
- **Cloud step is deliberately absent.** Will be one new entry in `OnboardingStep` + one composable when v1.2 cloud sign-in lands. The list-driven step model makes it a one-line insert.
