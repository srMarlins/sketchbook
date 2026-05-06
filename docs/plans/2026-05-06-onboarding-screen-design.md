# Onboarding screen — design

Date: 2026-05-06

## Goal

A first-time user opens Sketchbook on a clean install and ends up with: at least one Projects root scanned, optional Sample roots, plugin folders pre-filled with OS defaults — without ever feeling like they're filling in a form. Finishable in 60 seconds with one folder pick, or extensible if the user wants to configure everything.

## Non-goals

- Not a tutorial. Doesn't explain features.
- Doesn't replace Settings — everything onboarding sets is editable there afterward.
- Doesn't import existing libraries from anywhere — only points at folders the user already has.
- Doesn't onboard cloud (GCS) sync. Cloud is a v1.2 problem and will become a one-click sign-in once the Cloudflare Worker coordinator lands; until then it stays in Settings → Advanced where it lives today. **The onboarding step model is built so a future cloud step is one new entry in a list, not a refactor.**
- Doesn't onboard external aliases (Splice, factory packs). Those will be surfaced in-context by `NeedsAttention` the first time a scan hits an unresolved sample reference — that's the natural moment to ask, not preemptively during welcome.

## Trigger logic

A single flag on `Settings`:

```kotlin
val firstRunCompletedAt: Instant? = null
```

A `LaunchGate` resolves at startup:

```kotlin
sealed interface LaunchDecision {
    data object Onboarding : LaunchDecision
    data object MainApp : LaunchDecision
    // Future: data class Migration(...) — designed-for, not built
}
```

Null `firstRunCompletedAt` → `Onboarding`. Otherwise → `MainApp`. The migration arm is left as a future `when` branch in `LaunchGate.resolve` and a future `LaunchDecision` variant — adding it later is two edits, not a redesign.

## The conversation

5 steps including welcome and wrap. Step 1 is the only one that gates progress.

| # | Prompt | Input | Default if skipped |
|---|---|---|---|
| 0 | **Welcome to Sketchbook.** Point it at your library and it'll do the rest. Takes a minute. | `Get started` | — |
| 1 | **Where are your Ableton projects?** Add one folder or several. | Multi-folder picker. OS-default suggestion as a clickable chip (Windows: `Documents/Live Projects`; Mac: `~/Music/Ableton/User Library`). | No skip — required |
| 2 | **Sample folders?** | Multi-folder picker, can stay empty. | Empty. Soft re-prompt later. |
| 3 | **Plugin folders.** Used to flag projects with missing plugins. | Pre-filled with OS defaults, editable. `Use defaults` button. | OS defaults (already filled) |
| 4 | **Done.** Scanning starts now — you can use Sketchbook while it runs. | `Open Sketchbook` | — |

**Skip-all-and-use-defaults.** A single text link in the footer of every step ≥ 1. Keeps anything entered up to that point, applies OS defaults for plugin folders, leaves samples empty, jumps to Step 4 (Done) — *not* a silent dismiss, because the user should still see scanning kick off and have one last `Open Sketchbook` button. No confirmation modal — every choice is reversible in Settings.

**Soft re-prompt banner on Home.** When onboarding finishes with samples skipped, render a one-line dismissable banner above the projects list:

> *"Add a samples folder later? Powers the missing-files finder."* → links to Settings.

Same `Surface` component as the existing dashboard cards, `tintCream` background, small `×` to dismiss. Dismiss is sticky — Settings remains the way back in. No banner for plugin folders since OS defaults are always written.

## Visual treatment

**Layout.** Full-screen `PaperPage` (existing notebook texture) with a single centered column, ~520dp max-width, vertically centered. Each step is its own composable; transitions reuse the fade-through pattern in `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt:99-102`. Onboarding inherits the visual language; nothing new gets invented.

**Per-step structure (top to bottom):**

1. Five small inked dots, current one filled. No "Step N of 5" label.
2. Heading (`AppTheme.typography.title`).
3. Input — folder picker rows / file picker.
4. Primary action button (`Continue`, `Open Sketchbook` on Done).
5. `Skip` text link to the right of primary, where applicable. Disabled style on Step 1.
6. Footer: `Skip all and use defaults` text link bottom-left, version bottom-right.

**Component reuse.** Folder picker rows render as the existing `LibraryRootCard` from `shared/feature-settings/src/commonMain/kotlin/com/sketchbook/featuresettings/SettingsScreen.kt:96`. `JFileChooser` is passed in as a callback (already used in `RootContent.kt:89`). Existing `PaperPage`, `Surface`, `Button(ButtonVariant)`, `Text`, `InkLoading` cover everything.

**Color discipline.** No new tokens. `tintCream` background, existing `ButtonVariant` styles, existing typography scale. (Per `feedback_color_restraint.md`.)

## Animation and detail

**Library decision: no Lottie.** Considered Compottie (CMP port of Lottie) and rejected: (1) new dependency violates "no unnecessary libraries" feedback memory, (2) Lottie files come with baked palettes that fight existing tokens, (3) `InkLoading` + Compose Canvas + `animateFloatAsState` cover every animation we need.

**Specific moments:**

| Moment | Technique |
|---|---|
| Step heading enters by typing itself in, word-by-word, 60ms stagger, 250ms fade per word | `AnimatedVisibility` per word + `LaunchedEffect` delay |
| Ink underline draws under the focused folder picker / text field, left-to-right, 220ms `EaseOut` | `Canvas` + `animateFloatAsState(0f → 1f)` driving a `drawLine` |
| Folder card "stamps" in when added — scale 0.92 → 1.0 spring (`StiffnessMediumLow`) + 180ms fade | Wrap `LibraryRootCard` in `animateFloatAsState` |
| Progress dots ink-fill — current dot has radial fill growing from center on step change | `Canvas` + `animateFloatAsState` |
| Page-turn between steps — fade-through plus 16dp horizontal slide accent | `AnimatedContent` (matches `RootContent` pattern) |
| Done step indicator — `InkLoading` with copy *"Reading your library…"* | Existing component |
| Text field cursor blinks soft (700ms cycle, opacity 0.4 → 1.0 not 0 → 1) | `BasicTextField` `cursorBrush` + `animateFloatAsState` |

**Easing.** Material "emphasized decelerate" (`CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)`) for entries, `tween(300, FastOutSlowInEasing)` for exits. Slower than the app's sidebar transitions on purpose — onboarding feels calmer than working.

**Stagger.** Sequential element entry with 80ms gaps; total entrance under 600ms.

**No springs except the folder-card stamp.** Springs read as physical drop-ins; elsewhere they read as childish. Adults using tools.

**No sound.** Audio in a music app's onboarding is a trap.

**Inspiration credit.** Things 3, Bear, iA Writer, Linear, and Apple's setup assistants for cadence. Deliberately ignored Slack/Duolingo/Figma/Arc — too playful or too color-keyed for this aesthetic.

## Architecture

### Module placement

New `shared/feature-onboarding/` slotted into the existing `feature-*` tier. Depends on `repository` and `ui-shared`. UI in `commonMain`. Folder picker is a callback parameter (`onPickFolder: () -> String?`) supplied by `app-desktop`. Same shape as the other feature modules.

### ViewModel

Follows the Metro / KMP convention from `docs/architecture/dependency-injection.md` — `@Inject` constructor, `@ContributesBinding`, acquired via `metroViewModel` per `NavEntry`.

```kotlin
data class OnboardingState(
    val steps: List<OnboardingStep>,
    val currentIndex: Int,
    val projectsRoots: List<String>,
    val sampleRoots: List<String>,
    val pluginFolders: List<String>,        // pre-filled with OS defaults
    val canContinue: Boolean,               // false on ProjectsRoots step if empty
)

sealed interface OnboardingStep {
    data object Welcome : OnboardingStep
    data object ProjectsRoots : OnboardingStep
    data object SampleRoots : OnboardingStep
    data object PluginFolders : OnboardingStep
    data object Done : OnboardingStep
    // Future: data object CloudSignIn — drop in when v1.2 cloud lands
}

sealed interface OnboardingIntent {
    data class AddProjectsRoot(val path: String) : OnboardingIntent
    data class RemoveProjectsRoot(val path: String) : OnboardingIntent
    data class AddSampleRoot(val path: String) : OnboardingIntent
    data class RemoveSampleRoot(val path: String) : OnboardingIntent
    data class AddPluginFolder(val path: String) : OnboardingIntent
    data class RemovePluginFolder(val path: String) : OnboardingIntent
    data object UsePluginDefaults : OnboardingIntent
    data object Continue : OnboardingIntent
    data object Skip : OnboardingIntent              // skip the current optional step
    data object SkipAllUseDefaults : OnboardingIntent
    data object Finish : OnboardingIntent            // pressed from Done step
}
```

`steps: List<OnboardingStep>` is built in the VM constructor. Adding cloud later is one new list entry plus one composable per step in the UI — no `when`-arm sprawl. Progress dots render `steps.size` dots, current = `currentIndex`, so the indicator follows automatically.

### Settings additions

Extend `Settings` and `SettingsRepository` in `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/SettingsRepository.kt`:

```kotlin
data class Settings(
    // …existing fields
    val firstRunCompletedAt: Instant? = null,
    val onboardingSkipped: OnboardingSkipFlags = OnboardingSkipFlags(),
)

data class OnboardingSkipFlags(
    val samplesSkipped: Boolean = false,
    val samplesPromptDismissed: Boolean = false,
    // Future: cloudSkipped, cloudPromptDismissed
)
```

Two new `SettingsRepository` methods:

- `markFirstRunComplete(skipFlags: OnboardingSkipFlags)` — sets `firstRunCompletedAt` and skip flags atomically.
- `dismissOnboardingPrompt(kind: OnboardingPromptKind)` — flips the relevant `*PromptDismissed` flag.

Both write through the existing `java.util.prefs.Preferences` JVM impl.

### Plugin-folder persistence

The missing-plugin chip already ships, so plugin folders are persisted somewhere. **Open question for implementation:** confirm whether they live in `SettingsRepository` already; if not, add a `pluginFolders: Set<String>` field and `setPluginFolders()` method following the existing pattern.

### Finish flow

When the user hits `Open Sketchbook` on Done:

1. Persist `LibraryRoot.Projects(path)` rows for every entered Projects path via `SettingsRepository.upsertRoot`.
2. Same for samples (`LibraryRoot.UserSamples`).
3. Persist plugin folders via the appropriate setter.
4. Call `markFirstRunComplete(skipFlags)`.
5. Trigger `LibraryScanCoordinator` (already wired in `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/LibraryScanCoordinator.kt`) — scan kicks off as the user lands on Home.
6. Navigate to Home (replace, not push — onboarding shouldn't be on the back stack).

Each suspend call is wrapped in `runCatching` — a single bad path doesn't strand the user on Done. Failures surface as a non-blocking toast; the rest of the persistence still runs.

### Soft re-prompt banner

`RootChromeViewModel` exposes `pendingOnboardingPrompt: StateFlow<OnboardingPrompt?>` derived from `Settings.onboardingSkipped`. Home renders the banner when non-null. Dismiss → `dismissOnboardingPrompt(...)` flips the flag, the flow re-emits null, banner disappears.

### Platform-aware OS defaults

```kotlin
expect fun defaultPluginFolders(): List<String>
expect fun defaultProjectsRootSuggestion(): String?
expect fun defaultSamplesRootSuggestion(): String?
```

JVM `actual` reads `System.getProperty("os.name")` and returns:

- Windows projects: `${user.home}/Documents/Live Projects`
- Mac projects: `${user.home}/Music/Ableton/User Library`
- Windows plugins: `C:/Program Files/Common Files/VST3`, `C:/Program Files/VstPlugins`
- Mac plugins: `/Library/Audio/Plug-Ins/VST3`, `/Library/Audio/Plug-Ins/Components`

Unrecognized OS returns empty/null lists; the user just sees no chip and adds folders manually.

## Testing

`OnboardingViewModelTest` in `shared/feature-onboarding/src/commonTest/`, using `FakeSettingsRepository` (already exists in test infra):

- `canContinue` is false on `ProjectsRoots` step when empty, true after one is added.
- `SkipAllUseDefaults` from any step preserves entered data, fills `pluginFolders` with OS defaults, jumps `currentIndex` to `Done`.
- `Skip` from optional steps advances `currentIndex` by one without persisting anything.
- `Finish` writes all roots in order, calls `markFirstRunComplete` with the right skip flags, triggers scan exactly once.
- A failing `upsertRoot` call doesn't prevent later calls from running.

`LaunchGateTest`:

- Null `firstRunCompletedAt` → `Onboarding`.
- Set `firstRunCompletedAt` → `MainApp`.

No screenshot tests (per `feedback_no_unnecessary_libs.md`). Visual verification is manual: implementation plan includes a task to launch the desktop app with `firstRunCompletedAt` cleared, walk the full flow, capture screenshots of each step, attach to the PR.

## File touch list

- **New:** `shared/feature-onboarding/` module — `OnboardingScreen.kt`, `OnboardingViewModel.kt`, step composables, `expect/actual` for OS defaults, tests.
- **New:** `app-desktop/.../LaunchGate.kt`, hookup in `Main.kt`.
- **Modified:** `shared/repository/.../SettingsRepository.kt` — add fields and methods.
- **Modified:** `shared/repository/.../impl/PrefsSettingsRepository.kt` (or equivalent) — persist new fields.
- **Modified:** `app-desktop/.../RootContent.kt` (or `RootChromeViewModel.kt`) — render the soft re-prompt banner.
- **Modified:** `app-desktop/.../Main.kt` — branch on `LaunchGate.resolve()`.
- **Modified:** `settings.gradle.kts` — register the new module.

## Future-proofing

- **Cloud step.** Drop a `CloudSignIn` entry into `OnboardingStep`, add a composable, append to the steps list. No structural change.
- **Migration arm.** Add a `Migration(progress: Flow<MigrationProgress>)` variant to `LaunchDecision`. Update `LaunchGate.resolve()` to detect "v0.1 catalog with rows but no `firstRunCompletedAt`" and emit it. Wire to a separate progress screen (not onboarding's UI). Onboarding's state assumes nothing about pre-existing catalog rows, so migration can run before onboarding for upgraders without onboarding caring.
- **Reduced-motion preference.** Surface a settings toggle later. For v1, animations stay under 400ms so even unflagged motion-sensitive users don't feel sick.
