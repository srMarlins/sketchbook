# Compose Desktop UI Verification Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Wire Roborazzi (Compose Desktop) into Sketchbook so Claude can capture transient PNGs of Composables it builds, read them, and verify visual correctness without a human in the loop. Ship the integration PR with one captured screenshot per top-level screen embedded in the PR description.

**Architecture:** A new `screenshot-tests` convention plugin in `build-logic/` adds `io.github.takahirom.roborazzi` + `roborazzi-compose-desktop` to a module's `jvmTest` source set. Per-feature opt-in is one line. Tests use `runDesktopComposeUiTest { setContent { … } }` + `onRoot().captureRoboImage("…png")`. PNGs land in `build/roborazzi/` (transient, gitignored). No goldens, no `verifyRoborazziJvm`, no CI gate.

**Tech Stack:** Kotlin 2.3.21, Compose Multiplatform 1.11.0-rc01, Gradle 9.0.0, Roborazzi (latest stable as of 2026-05-07), JUnit 4 API via `kotlin.test`, precompiled-script convention plugins (`build-logic/src/main/kotlin/*.gradle.kts`).

**Worktree:** Already in `.claude/worktrees/screenshot-verification/` on branch `worktree-screenshot-verification`. Design doc at `docs/plans/2026-05-07-compose-desktop-ui-verification-design.md` is committed.

---

## Pre-flight context

**Existing convention plugins** (`build-logic/src/main/kotlin/`):
- `kmp-library.gradle.kts` — applies KMP plugin, sets `jvm()` target + JDK 21 toolchain
- `kmp-compose.gradle.kts` — extends `kmp-library` (reserved for Compose-specific config)
- `kmp-test.gradle.kts` — extends `kmp-library`, adds power-assert + `kotlin.test` to `commonTest`
- `detekt-config.gradle.kts` — detekt rules

**Feature module pattern** — uses `id("kmp-compose")` + Compose plugin aliases + Metro. Tests live in `commonTest` currently. There is no `jvmTest` in feature modules yet — the convention plugin will add one.

**Five screen-level Composables to capture for the PR:**
1. `ProjectListScreen` in `:shared:feature-projects`
2. `ProjectDetailScreen` in `:shared:feature-project-detail`
3. `TimelineScreen` in `:shared:feature-timeline`
4. `InboxScreen` in `:shared:feature-journal` (the "Inbox" navigation entry routes here per the audit)
5. `SettingsScreen` in `:shared:feature-settings`

**ViewModel-coupling caveat.** Each screen takes a Metro-injected `ViewModel` and reads its `StateFlow<State>` via `collectAsStateWithLifecycle()`. For screenshot capture we will **extract a stateless inner Composable** (`*Content`) that takes `state` + a no-op dispatch lambda, and have the screen-level Composable delegate to it. This is a small, idiomatic refactor and the standard pattern for testable Compose. Where a stateless variant already exists, use that instead of refactoring.

**Versioning.** The latest published Roborazzi version with Compose Desktop support must be confirmed on Maven Central before pinning. Task 1 includes that lookup.

---

## Task 1 — Pin Roborazzi version

**Files:**
- Modify: `gradle/libs.versions.toml`

**Step 1: Look up the latest Roborazzi version.**

Run: `curl -s https://repo1.maven.org/maven2/io/github/takahirom/roborazzi/roborazzi-compose-desktop/maven-metadata.xml | grep -E "<latest>|<release>"`
Expected: shows the latest released version (e.g. `<release>1.49.0</release>` — record the actual value).

If no `roborazzi-compose-desktop` artifact is published yet, fall back to the highest version on Maven Central by browsing `https://repo1.maven.org/maven2/io/github/takahirom/roborazzi/roborazzi-compose-desktop/`. Document what was chosen and why.

**Step 2: Add the version, plugin alias, and library alias.**

Edit `gradle/libs.versions.toml`:

In `[versions]`, add after the `# Testing` block (around line 49):
```toml
roborazzi = "<version-from-step-1>"
```

In `[libraries]`, add at end of the test section (after `turbine`, around line 113):
```toml
roborazzi-compose-desktop = { module = "io.github.takahirom.roborazzi:roborazzi-compose-desktop", version.ref = "roborazzi" }
```

In `[plugins]`, add at end of the dev tooling section:
```toml
roborazzi = { id = "io.github.takahirom.roborazzi", version.ref = "roborazzi" }
```

**Step 3: Add the Roborazzi Gradle plugin to the build-logic classpath.**

Edit `build-logic/build.gradle.kts`. After the `dependency-analysis-gradle-plugin` line (around line 20), add:
```kotlin
implementation("io.github.takahirom.roborazzi:roborazzi-gradle-plugin:${version("roborazzi")}")
```

**Step 4: Verify the catalog parses.**

Run: `./gradlew help -q`
Expected: completes without errors. Any catalog typo surfaces here.

**Step 5: Commit.**

```bash
git add gradle/libs.versions.toml build-logic/build.gradle.kts
git commit -m "build: add Roborazzi version + plugin alias for screenshot capture"
```

---

## Task 2 — Create the `screenshot-tests` convention plugin

**Files:**
- Create: `build-logic/src/main/kotlin/screenshot-tests.gradle.kts`

**Step 1: Write the convention plugin.**

Create `build-logic/src/main/kotlin/screenshot-tests.gradle.kts` with:

```kotlin
plugins {
    id("kmp-compose")
    id("io.github.takahirom.roborazzi")
}

// Pull catalog so this script can resolve `libs.*` aliases.
val libs = the<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        // Roborazzi compose-desktop tests run on the JVM target only — Skia surface
        // initialisation and the desktop UI test harness are JVM-bound. Hosting them
        // in `jvmTest` (not `commonTest`) keeps the dep + plugin off other targets if
        // the project ever adds them.
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.findLibrary("roborazzi-compose-desktop").get())
                // Compose's official UI test harness — provides runDesktopComposeUiTest.
                implementation(compose.uiTest)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

// Roborazzi's `verify` task is intentionally NOT wired into `check`. Captures are
// for Claude's eyes, not regression gating. Tests that capture run on demand via
// `recordRoborazziJvm` only.
tasks.matching { it.name == "check" }.configureEach {
    setDependsOn(dependsOn.filterNot {
        (it as? String)?.startsWith("verifyRoborazzi") == true ||
        (it as? org.gradle.api.Task)?.name?.startsWith("verifyRoborazzi") == true
    })
}
```

Note: `compose.uiTest`, `compose.runtime`, etc. are accessor extensions from the Compose Multiplatform plugin. They resolve correctly because `id("kmp-compose")` brings in `id("org.jetbrains.kotlin.multiplatform")` and the consuming module applies `compose-multiplatform` itself.

**Step 2: Verify the convention plugin compiles.**

Run: `./gradlew :build-logic:assemble`
Expected: BUILD SUCCESSFUL. Any compile error in the precompiled script surfaces here.

**Step 3: Commit.**

```bash
git add build-logic/src/main/kotlin/screenshot-tests.gradle.kts
git commit -m "build: add screenshot-tests convention plugin (Roborazzi + Compose UI test)"
```

---

## Task 3 — Smoke test the framework on one trivial Composable

Goal: prove Roborazzi + Compose 1.11.0-rc01 + the convention plugin actually capture a PNG before refactoring any production code. If this fails, debug here, not after touching five screens.

**Files:**
- Modify: `shared/feature-projects/build.gradle.kts`
- Create: `shared/feature-projects/src/jvmTest/kotlin/com/sketchbook/featureprojects/screenshot/SmokeTest.kt`

**Step 1: Apply the convention plugin to `:shared:feature-projects`.**

Edit `shared/feature-projects/build.gradle.kts`. Add `id("screenshot-tests")` to the `plugins` block:

```kotlin
plugins {
    id("kmp-compose")
    id("screenshot-tests")  // ← new
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.metro)
}
```

**Step 2: Write a trivial smoke test.**

Create `shared/feature-projects/src/jvmTest/kotlin/com/sketchbook/featureprojects/screenshot/SmokeTest.kt`:

```kotlin
package com.sketchbook.featureprojects.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class SmokeTest {
    @Test
    fun captures_a_hello_box() = runDesktopComposeUiTest(width = 400, height = 200) {
        setContent {
            Box(Modifier.fillMaxSize().size(200.dp)) {
                Text("hello roborazzi")
            }
        }
        onRoot().captureRoboImage("build/roborazzi/smoke_hello.png")
    }
}
```

Notes:
- `runDesktopComposeUiTest(width, height)` lets us pin a deterministic surface size.
- The PNG path is relative to the module root (Gradle's default working dir for tests).
- `compose.material3` is needed for `Text`. If not on classpath in feature-projects' `jvmTest`, swap to `androidx.compose.foundation.text.BasicText` to avoid adding a dep just for the smoke test.

**Step 3: Run the smoke test.**

Run: `./gradlew :shared:feature-projects:recordRoborazziJvm --tests "*SmokeTest*"`
Expected: BUILD SUCCESSFUL; `shared/feature-projects/build/roborazzi/smoke_hello.png` exists.

If this fails, common causes:
- Missing Compose dep on `jvmTest` — extend the convention plugin to add `compose.material3` if `Text` is the cause.
- Roborazzi version mismatch with Compose 1.11.0-rc01 — try the previous Roborazzi minor version.
- `runDesktopComposeUiTest` not resolving — confirm `compose.uiTest` accessor is available; may need explicit `org.jetbrains.compose.ui:ui-test:<version>` library entry.

Debug here until green before proceeding.

**Step 4: Read the PNG to confirm Claude can actually see it.**

Use the Read tool on `Z:\User\audio\.claude\worktrees\screenshot-verification\shared\feature-projects\build\roborazzi\smoke_hello.png`.
Expected: image renders showing "hello roborazzi" text in a box.

**Step 5: Delete the PNG (transient artifact rule).**

Run: `rm shared/feature-projects/build/roborazzi/smoke_hello.png`

**Step 6: Commit the smoke test.**

```bash
git add shared/feature-projects/build.gradle.kts shared/feature-projects/src/jvmTest/kotlin/com/sketchbook/featureprojects/screenshot/SmokeTest.kt
git commit -m "test: smoke-test Roborazzi capture pipeline on feature-projects"
```

---

## Task 4 — Capture `ProjectListScreen`

**Files:**
- Modify: `shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/ProjectListScreen.kt` (extract `ProjectListContent`)
- Create: `shared/feature-projects/src/jvmTest/kotlin/com/sketchbook/featureprojects/screenshot/ProjectListScreenshots.kt`

**Step 1: Extract a stateless `ProjectListContent` Composable.**

Open `ProjectListScreen.kt`. The current entry point reads `vm.state.collectAsStateWithLifecycle()` then renders.

Refactor into two Composables:

```kotlin
@Composable
fun ProjectListScreen(
    vm: ProjectListViewModel,
    modifier: Modifier = Modifier,
    scanLabel: String? = null,
    scanActive: Boolean = false,
    syncStateFor: ((ProjectId) -> ProjectSyncState)? = null,
    detailPanel: (@Composable (ProjectId, () -> Unit) -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    ProjectListContent(
        state = state,
        dispatch = vm::dispatch,
        modifier = modifier,
        scanLabel = scanLabel,
        scanActive = scanActive,
        syncStateFor = syncStateFor,
        detailPanel = detailPanel,
    )
}

@Composable
internal fun ProjectListContent(
    state: ProjectListViewModel.State,
    dispatch: (ProjectListViewModel.Intent) -> Unit,
    modifier: Modifier = Modifier,
    scanLabel: String? = null,
    scanActive: Boolean = false,
    syncStateFor: ((ProjectId) -> ProjectSyncState)? = null,
    detailPanel: (@Composable (ProjectId, () -> Unit) -> Unit)? = null,
) {
    // ... existing body, with `state` and `dispatch` used directly
    // Replace `dispatch(...)` calls with the parameter; remove `val dispatch = vm::dispatch`.
}
```

The body is mechanical: every `dispatch(X)` already exists, just replace the lambda derivations to use the parameter. The `remember(vm)` keys for stable callbacks become `remember(dispatch)`.

**Step 2: Run the existing `:shared:feature-projects` test suite to confirm no regression.**

Run: `./gradlew :shared:feature-projects:check`
Expected: BUILD SUCCESSFUL. (Existing tests at `commonTest/.../ProjectListViewModelTest.kt` and `ToSongStripDataTest.kt` continue passing.)

**Step 3: Write the screenshot test.**

Create `shared/feature-projects/src/jvmTest/kotlin/com/sketchbook/featureprojects/screenshot/ProjectListScreenshots.kt`:

```kotlin
package com.sketchbook.featureprojects.screenshot

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.github.takahirom.roborazzi.captureRoboImage
import com.sketchbook.featureprojects.ProjectListContent
import com.sketchbook.featureprojects.ProjectListViewModel
import com.sketchbook.uishared.theme.AppTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class ProjectListScreenshots {
    @Test
    fun loaded_state() = runDesktopComposeUiTest(width = 1280, height = 800) {
        setContent {
            AppTheme {
                ProjectListContent(
                    state = sampleLoadedState(),
                    dispatch = {},
                )
            }
        }
        onRoot().captureRoboImage("build/roborazzi/project_list_loaded.png")
    }
}

// Sample state factory. Construct a `ProjectListViewModel.State` with realistic-looking
// data: 3-5 project rows, mix of stages, no active search. Field-by-field — peek at the
// State data class and at ProjectListViewModelTest for sample-construction patterns.
private fun sampleLoadedState(): ProjectListViewModel.State {
    TODO("Build a populated State value — see ProjectListViewModelTest for field shapes")
}
```

The `TODO` is intentional: the executor must read the `State` data class and `ProjectListViewModelTest` to build a faithful sample. Don't fake it abstractly — use the same data shapes existing tests construct.

**Step 4: Implement `sampleLoadedState()`.**

Read `ProjectListViewModel.kt` for the `State` data class and `ProjectListViewModelTest.kt` for sample-construction patterns. Build a `State` with:
- 3–5 entries in whatever maps/lists the State holds (groups, buckets, search results, etc.)
- A non-empty ProjectListing or equivalent — pick stages that vary so the screenshot shows visual variety
- `query = ""`, `isLoading = false` — happy-path defaults
- Whatever derived fields the State exposes set to non-null where reasonable

If `State` is private/internal, expose a test factory in `commonMain` (`@VisibleForTesting`-style) OR move the screenshot test to the same package via `commonTest` if the convention plugin permits it. Prefer the test-factory route — keep production API clean.

**Step 5: Run the capture.**

Run: `./gradlew :shared:feature-projects:recordRoborazziJvm --tests "*ProjectListScreenshots*"`
Expected: BUILD SUCCESSFUL; `shared/feature-projects/build/roborazzi/project_list_loaded.png` exists.

**Step 6: Read the PNG.**

Use the Read tool on the PNG path. Confirm:
- It looks like the project list (shelves, song rows, stage chips visible)
- Theme is applied (colors aren't default Material baseline)
- Layout isn't broken (no overflow, no zero-height rows)

If broken, iterate on `sampleLoadedState()` until it looks right.

**Step 7: Save a copy of the PNG outside the build dir for the PR.**

Run: `mkdir -p .pr-artifacts && cp shared/feature-projects/build/roborazzi/project_list_loaded.png .pr-artifacts/`

`.pr-artifacts/` is *not* committed (gitignored in Task 9). It's a staging area for PR upload at the end.

**Step 8: Commit.**

```bash
git add shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/ProjectListScreen.kt \
        shared/feature-projects/src/jvmTest/kotlin/com/sketchbook/featureprojects/screenshot/ProjectListScreenshots.kt
git commit -m "test: capture ProjectListScreen via Roborazzi (Content extraction + loaded state)"
```

---

## Task 5 — Capture `ProjectDetailScreen`

Same shape as Task 4. Module: `:shared:feature-project-detail`.

**Step 1:** Apply `id("screenshot-tests")` in `shared/feature-project-detail/build.gradle.kts`.

**Step 2:** Read `ProjectDetailScreen.kt`. If it takes a ViewModel, extract a `ProjectDetailContent` stateless Composable mirroring Task 4's pattern. If it already accepts state directly, skip the refactor.

**Step 3:** Run existing tests: `./gradlew :shared:feature-project-detail:check`. Expect green.

**Step 4:** Create `shared/feature-project-detail/src/jvmTest/kotlin/com/sketchbook/featureprojectdetail/screenshot/ProjectDetailScreenshots.kt` with one capture: `loaded_state` — a project with versions, tags, and a stage set, rendered at 1280x800. Sample state built from looking at the ViewModel + any existing detail tests.

**Step 5:** Run `./gradlew :shared:feature-project-detail:recordRoborazziJvm --tests "*ProjectDetailScreenshots*"`. Read the PNG. Iterate until the screen looks correct.

**Step 6:** `cp shared/feature-project-detail/build/roborazzi/project_detail_loaded.png .pr-artifacts/`

**Step 7:** Commit:
```bash
git add shared/feature-project-detail/build.gradle.kts \
        shared/feature-project-detail/src/commonMain/kotlin/.../ProjectDetailScreen.kt \
        shared/feature-project-detail/src/jvmTest/kotlin/com/sketchbook/featureprojectdetail/screenshot/ProjectDetailScreenshots.kt
git commit -m "test: capture ProjectDetailScreen via Roborazzi"
```

---

## Task 6 — Capture `TimelineScreen`

Same pattern. Module: `:shared:feature-timeline`. Output: `.pr-artifacts/timeline_loaded.png`.

Sample state should show a timeline with at least 5–10 events spread across visible time buckets so the screenshot is visually informative (not empty / single-event).

Commit message: `test: capture TimelineScreen via Roborazzi`

---

## Task 7 — Capture `InboxScreen`

Same pattern. Module: `:shared:feature-journal` (Inbox routes here per the audit). Output: `.pr-artifacts/inbox_loaded.png`.

Sample state should show a populated inbox — multiple entries with mixed types if the data model supports it.

Commit message: `test: capture InboxScreen via Roborazzi`

---

## Task 8 — Capture `SettingsScreen`

Same pattern. Module: `:shared:feature-settings`. Output: `.pr-artifacts/settings_default.png`.

Settings is likely simpler — render with default-but-configured values (e.g. a populated library path, theme toggle visible) so the screenshot conveys the screen, not a blank form.

Commit message: `test: capture SettingsScreen via Roborazzi`

---

## Task 9 — Gitignore the PR-artifacts staging dir and tidy

**Files:**
- Modify: `.gitignore`

**Step 1: Add `.pr-artifacts/` to `.gitignore`.**

Append to `.gitignore`:
```
.pr-artifacts/
```

**Step 2: Verify nothing in `build/roborazzi/` snuck in.**

Run: `git status`
Expected: clean working tree apart from `.gitignore`. No `build/roborazzi/*.png` tracked anywhere.

If any captured PNG is accidentally staged (for example because `build/` is incorrectly excluded somewhere), `git rm --cached` it.

**Step 3: Commit.**

```bash
git add .gitignore
git commit -m "build: gitignore .pr-artifacts/ (PR-only staging for screenshot uploads)"
```

---

## Task 10 — Final verification

**Step 1: Clean and re-run all five capture tests in one shot.**

Run:
```bash
./gradlew clean
./gradlew :shared:feature-projects:recordRoborazziJvm \
          :shared:feature-project-detail:recordRoborazziJvm \
          :shared:feature-timeline:recordRoborazziJvm \
          :shared:feature-journal:recordRoborazziJvm \
          :shared:feature-settings:recordRoborazziJvm \
          --tests "*Screenshots*"
```
Expected: all five `recordRoborazziJvm` tasks green. PNGs exist under each module's `build/roborazzi/`.

**Step 2: Re-stage the five PNGs in `.pr-artifacts/`.**

```bash
mkdir -p .pr-artifacts
cp shared/feature-projects/build/roborazzi/project_list_loaded.png .pr-artifacts/
cp shared/feature-project-detail/build/roborazzi/project_detail_loaded.png .pr-artifacts/
cp shared/feature-timeline/build/roborazzi/timeline_loaded.png .pr-artifacts/
cp shared/feature-journal/build/roborazzi/inbox_loaded.png .pr-artifacts/
cp shared/feature-settings/build/roborazzi/settings_default.png .pr-artifacts/
```

**Step 3: Read each `.pr-artifacts/*.png` to confirm visual correctness one last time before opening the PR.**

Use the Read tool on each. Note any oddities (clipping, unstyled text, broken layout) and either fix or document.

**Step 4: Run the wider check task to ensure nothing else regressed.**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. (Recall: `verifyRoborazziJvm` was excluded from `check` in the convention plugin, so this should not run any screenshot verification.)

**Step 5: No commit — verification step only.**

---

## Task 11 — Open the PR

**Step 1: Push the branch.**

Run: `git push -u origin worktree-screenshot-verification`

**Step 2: Open the PR with the five screenshots embedded inline.**

Use `gh pr create`. Title: `feat(test): Roborazzi-as-eyes for autonomous Compose Desktop UI verification`.

Body template (the executor uploads each PNG to GitHub via the editor's drag-and-drop or via `gh` attachments — `gh pr create --body-file` with an absolute file ref will not auto-embed; the simplest approach is to open the draft PR first, then edit the body in the GitHub UI to drag-drop the PNGs):

```markdown
## Summary

Adds Roborazzi-based screenshot capture so Claude can see Composable changes it makes without a human in the loop. Per-feature opt-in via the new `screenshot-tests` convention plugin. Captures land in `build/roborazzi/`, are transient (gitignored, cleaned by `./gradlew clean`), and are explicitly **not** wired into `check` — no goldens, no CI gate, no visual regression suite. Roborazzi is being used as Claude's eyes, not as a regression tool.

Design doc: `docs/plans/2026-05-07-compose-desktop-ui-verification-design.md`

## Screens captured

**ProjectList**
<drag project_list_loaded.png here>

**ProjectDetail**
<drag project_detail_loaded.png here>

**Timeline**
<drag timeline_loaded.png here>

**Inbox**
<drag inbox_loaded.png here>

**Settings**
<drag settings_default.png here>

## Test plan
- [x] `./gradlew clean && ./gradlew :shared:feature-*:recordRoborazziJvm --tests "*Screenshots*"` green across all 5 feature modules
- [x] Each PNG visually inspected
- [x] `./gradlew check` green (confirms `verifyRoborazziJvm` is *not* gating the build)
- [x] Each screen-level Composable refactored to expose a stateless `*Content` variant where necessary; existing ViewModel tests still pass
```

**Step 3: After the PR is open, paste the GitHub URL back so the PR body can be confirmed in the UI.**

---

## Notes for the executor

- **Frequent commits.** Each task ends in a commit. Don't batch.
- **YAGNI.** Don't add states beyond `loaded_state` per screen for this PR. Empty / error / loading variants are deferred — capture them when actually working on those code paths.
- **No `verifyRoborazziJvm` ever.** Don't run it. Don't wire it. The convention plugin actively un-wires it from `check`.
- **PNG dimensions.** Default to `width = 1280, height = 800` for screen-level captures (matches a reasonable Sketchbook window). Smaller for individual components (Task 3 used 400x200).
- **If a screen has no extractable stateless variant** (e.g. it lives inside a Box that depends on parent constraints unavailable in test), pause and surface the obstacle — do not invent fragile workarounds.
- **Compose 1.11.0-rc01 risk.** If Roborazzi misbehaves due to RC-vs-stable Skiko mismatch, document the failure and recommend either downgrading the chosen Roborazzi version or pausing the integration until Compose 1.11.0 stable. Don't ship a flaky pipeline.
