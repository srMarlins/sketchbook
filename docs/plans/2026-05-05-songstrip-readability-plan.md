# SongStrip Readability Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make the desktop project shelf rows (`SongStrip`) readable at any window width, replace the "+N" string concatenation with a proper version pill on a stacked-paper card, and add restrained color signals for empty effort and broken state.

**Architecture:** Pure UI changes in two modules. `shared/ui-shared/.../components/SongStrip.kt` becomes width-aware via `BoxWithConstraints` and gains a `variantCount` field plus a `VersionPill` and a stacked-paper background variant. `shared/feature-projects/.../ProjectListScreen.kt` stops gluing `"+N"` onto the project name and passes the variant count through to `SongStripData` instead.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform (JVM target via `app-desktop`), kotlin.test + Turbine for unit tests, Gradle. No new dependencies.

**Reference docs:** [`docs/plans/2026-05-05-songstrip-readability-design.md`](./2026-05-05-songstrip-readability-design.md).

**Memory pointers (read before editing):**
- `feedback_color_restraint.md` — palette must stay uniform; reuse existing tokens.
- `feedback_layer_dont_redesign.md` — additive only; don't reshape existing components.
- `feedback_no_unnecessary_libs.md` — no new deps.

---

## Background facts

- The project name in `SongStrip.kt:121-126` has `Modifier.weight(1f, fill = false)` but no `maxLines` or `overflow`. Long names wrap or push the row.
- `ProjectListScreen.kt:524-542` (`fun ProjectGroup.toSongStripData`) sets `name = r.name + (if (variantCount > 1) "  +${variantCount - 1}" else "")`. This concatenation is the bug — variant count must move out of the name string.
- `SongStripData` already exists in `SongStrip.kt:41-57`. Adding fields requires updating its single call site (`toSongStripData`) and any tests.
- The detail panel on `ProjectListScreen.kt:367-405` exists; this plan does **not** modify it. Variants list inside the panel is a follow-up.
- The codebase has zero existing tests on `SongStrip` or `ProjectListScreen` rendering. The new behavior is testable purely via the data mapper (`toSongStripData`) — visual changes are verified by running the app.
- `AppColors` (Light/Dark) defined at `shared/ui-shared/.../theme/Colors.kt`. Tokens we use: `accentDanger`, `inkFaint`, `tintCream`, `inkSecondary`, `ruleLine`, `surfaceCard`, `surfaceSunken`. No new tokens are added.

## Test commands you will use

- Single feature-projects test class: `./gradlew :shared:feature-projects:jvmTest --tests "com.sketchbook.featureprojects.ProjectListStateHolderTest"`
- Whole feature-projects module: `./gradlew :shared:feature-projects:jvmTest`
- Whole ui-shared module: `./gradlew :shared:ui-shared:jvmTest`
- Full Kotlin build (compile only, fast sanity): `./gradlew :shared:feature-projects:compileKotlinJvm :shared:ui-shared:compileKotlinJvm`
- Run desktop app for visual smoke: `./gradlew :app-desktop:run`

If you're on Windows PowerShell, prefix with `.\` instead of `./`.

---

## Task 1: Move variant count out of the project name string (TDD)

**Files:**
- Modify: `shared/ui-shared/src/commonMain/kotlin/com/sketchbook/uishared/components/SongStrip.kt:41-57`
- Modify: `shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/ProjectListScreen.kt:524-542`
- Create: `shared/feature-projects/src/commonTest/kotlin/com/sketchbook/featureprojects/ToSongStripDataTest.kt`

### Step 1.1: Write the failing test

Create `shared/feature-projects/src/commonTest/kotlin/com/sketchbook/featureprojects/ToSongStripDataTest.kt`:

```kotlin
package com.sketchbook.featureprojects

import com.sketchbook.core.ParseStatus
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectPath
import com.sketchbook.core.ProjectRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ToSongStripDataTest {

    private val now = Instant.parse("2026-05-05T12:00:00Z")
    private fun row(id: Long, name: String) = ProjectRow(
        id = ProjectId(id),
        name = name,
        path = ProjectPath("Projects/2026/Song/$name"),
        tempo = 120.0,
        trackCount = 4,
        lastSavedLiveVersion = "11.3.20",
        updatedAt = now,
        tags = emptyList(),
        colorTag = null,
    )

    private fun group(rep: ProjectRow, variants: List<ProjectRow>) = ProjectGroup(
        id = "Projects/2026/Song",
        representative = rep,
        variants = variants,
        effortScore = null,
        updatedAtMs = now.toEpochMilliseconds(),
        parseStatusBest = ParseStatus.Ok,
        missingSampleCount = 0,
    )

    @Test
    fun singletonHasBareNameAndVariantCountOne() {
        val r = row(1, "kick.als")
        val data = group(r, listOf(r)).toSongStripDataForTest(sync = null)
        assertEquals("kick.als", data.name)
        assertEquals(1, data.variantCount)
    }

    @Test
    fun multiVariantGroupKeepsBareRepresentativeNameAndExposesCount() {
        val rep = row(1, "Track v3.als")
        val v2 = row(2, "Track v2.als")
        val v1 = row(3, "Track v1.als")
        val data = group(rep, listOf(rep, v2, v1)).toSongStripDataForTest(sync = null)
        assertEquals("Track v3.als", data.name) // no "  +2" suffix
        assertEquals(3, data.variantCount)
    }
}
```

The helper `toSongStripDataForTest` will be added in step 1.2 to expose the private mapper for testing without changing the screen surface.

### Step 1.2: Run the test — expect compile failure

Run: `./gradlew :shared:feature-projects:jvmTest --tests "com.sketchbook.featureprojects.ToSongStripDataTest"`
Expected: compile error — `toSongStripDataForTest` is unresolved, and `SongStripData` does not have a `variantCount` field.

### Step 1.3: Add `variantCount` to `SongStripData`

In `SongStrip.kt:41-57` (the `data class SongStripData`), insert a new field with a default so existing call sites stay valid:

```kotlin
data class SongStripData(
    val id: Long,
    val name: String,
    val parentDir: String,
    val tempo: Double?,
    val timeSigNum: Int?,
    val timeSigDen: Int?,
    val trackCount: Int?,
    val lengthSeconds: Double?,
    val effortScore: Int?,
    val lastModifiedRelative: String?,
    val colorTag: Int?,
    val tags: List<String>,
    val warning: String? = null,
    val sync: SongSyncBadge? = null,
    /** Number of `.als` variants in this project group. 1 = singleton (no version card treatment). */
    val variantCount: Int = 1,
)
```

### Step 1.4: Update `toSongStripData` to stop concatenating "+N" and pass `variantCount` through

In `ProjectListScreen.kt:524-542`, replace:

```kotlin
private fun ProjectGroup.toSongStripData(sync: ProjectSyncState?): SongStripData {
    val r = representative
    return SongStripData(
        id = r.id.value,
        name = r.name + (if (variantCount > 1) "  +${variantCount - 1}" else ""),
        parentDir = id,
        tempo = r.tempo,
        ...
    )
}
```

with:

```kotlin
private fun ProjectGroup.toSongStripData(sync: ProjectSyncState?): SongStripData {
    val r = representative
    return SongStripData(
        id = r.id.value,
        name = r.name,
        parentDir = id,
        tempo = r.tempo,
        timeSigNum = null,
        timeSigDen = null,
        trackCount = r.trackCount.takeIf { it > 0 },
        lengthSeconds = null,
        effortScore = effortScore,
        lastModifiedRelative = relativeFromMs(updatedAtMs),
        colorTag = r.colorTag,
        tags = r.tags,
        warning = if (missingSampleCount > 0) "$missingSampleCount missing sample${if (missingSampleCount == 1) "" else "s"}" else null,
        sync = sync?.toBadge(),
        variantCount = variantCount,
    )
}

internal fun ProjectGroup.toSongStripDataForTest(sync: ProjectSyncState?): SongStripData =
    toSongStripData(sync)
```

### Step 1.5: Run the test — expect pass

Run: `./gradlew :shared:feature-projects:jvmTest --tests "com.sketchbook.featureprojects.ToSongStripDataTest"`
Expected: 2 tests pass.

### Step 1.6: Run the existing project-list test to make sure nothing regressed

Run: `./gradlew :shared:feature-projects:jvmTest`
Expected: all tests pass (3 from `ProjectListStateHolderTest`, 2 from new file).

### Step 1.7: Commit

```bash
git add shared/ui-shared/src/commonMain/kotlin/com/sketchbook/uishared/components/SongStrip.kt \
        shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/ProjectListScreen.kt \
        shared/feature-projects/src/commonTest/kotlin/com/sketchbook/featureprojects/ToSongStripDataTest.kt
git commit -m "feat(shelf): move variant count out of project name into SongStripData.variantCount"
```

---

## Task 2: Single-line ellipsis on the project name

**Files:**
- Modify: `shared/ui-shared/src/commonMain/kotlin/com/sketchbook/uishared/components/SongStrip.kt:115-132`

This is a visual fix with no unit-test surface — verified by running the app.

### Step 2.1: Apply the change

In `SongStrip.kt`, the inner `Row` containing the project name and warning glyph (around lines 115-132) currently renders the name without `maxLines`/`overflow`. Update:

```kotlin
ProvideContentColor(colors.inkPrimary) {
    Text(
        text = data.name,
        style = AppTheme.typography.bodyEmphasis,
        modifier = Modifier.weight(1f, fill = false),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
```

`TextOverflow` is already imported on line 27.

### Step 2.2: Compile

Run: `./gradlew :shared:ui-shared:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

### Step 2.3: Visual smoke

Run: `./gradlew :app-desktop:run`
Verify: pick a project with a long name (or rename one in the catalog briefly); the name truncates with `…` instead of pushing the stat columns or wrapping. Resize the window narrower; the name keeps shrinking but still ellipses.

If you cannot run the desktop app in this environment, say so explicitly in the commit message body — do not assert visual correctness without verification.

### Step 2.4: Commit

```bash
git add shared/ui-shared/src/commonMain/kotlin/com/sketchbook/uishared/components/SongStrip.kt
git commit -m "fix(shelf): single-line ellipsis on project name in SongStrip"
```

---

## Task 3: Width-driven stat dropping in `SongStrip`

**Files:**
- Modify: `shared/ui-shared/src/commonMain/kotlin/com/sketchbook/uishared/components/SongStrip.kt`

### Step 3.1: Wrap the row in `BoxWithConstraints` and gate stats by width

The current top `Row` lives inside the outer `Column` (around lines 100-161). Wrap the top `Row` (color bar / name / stats / sync / timestamp / launch) in a `BoxWithConstraints` so the stat block can read available width. Add the import:

```kotlin
import androidx.compose.foundation.layout.BoxWithConstraints
```

Replace the existing top `Row(...)` (lines 101-161) with a `BoxWithConstraints` whose content is the same `Row`, but the inner stat `Row` (currently lines 134-145) is filtered by width:

```kotlin
BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val widthDp = maxWidth.value
    val showLength = widthDp >= 880f
    val showMeter  = widthDp >= 760f
    val showTracks = widthDp >= 640f
    val showBpm    = widthDp >= 520f
    // effort always visible

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // ... color bar (unchanged) ...
        // ... name + warning (unchanged after Task 2) ...

        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBpm) Stat("bpm", data.tempo?.let { it.toInt().toString() } ?: "—", 36.dp)
            if (showMeter) Stat("meter", fmtTimeSig(data.timeSigNum, data.timeSigDen), 36.dp)
            if (showTracks) Stat("tracks", data.trackCount?.toString() ?: "—", 38.dp)
            if (showLength) Stat("length", fmtSeconds(data.lengthSeconds), 42.dp)
            Stat("effort", data.effortScore?.toString() ?: "—", 36.dp, accent = (data.effortScore ?: 0) >= 60)
        }
        Spacer(Modifier.width(4.dp))
        if (data.sync != null) SyncPip(data.sync)
        Box(modifier = Modifier.requiredWidthIn(min = 64.dp)) {
            ProvideContentColor(colors.inkMuted) {
                Text(
                    data.lastModifiedRelative ?: "—",
                    style = AppTheme.typography.mono.copy(fontSize = 11.sp()),
                )
            }
        }
        if (onLaunch != null) {
            LaunchIcon(onLaunch)
        }
    }
}
```

The bottom row (parent dir + tags) stays unchanged in this step. Tag count cap is updated in Task 4.

### Step 3.2: Cap tag chips at 2 (down from 3) when width < 760dp

Within the same `BoxWithConstraints` scope (or by hoisting `widthDp` up via `remember` before the outer `Column`), thread a `val tagsLimit = if (widthDp >= 760f) 3 else 2` into the bottom row. Easiest: put both rows inside one `BoxWithConstraints` wrapping the outer `Column` content, OR keep the second `BoxWithConstraints` with the same breakpoints. Pick one approach — don't duplicate breakpoint constants.

Recommended structure: put a single `BoxWithConstraints` at the top of the `Column`'s content (just inside the existing `Column { ... }`), capture `widthDp` in a `val`, then render both rows under it:

```kotlin
BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val widthDp = maxWidth.value
    val showLength = widthDp >= 880f
    val showMeter  = widthDp >= 760f
    val showTracks = widthDp >= 640f
    val showBpm    = widthDp >= 520f
    val tagsLimit  = if (widthDp >= 760f) 3 else 2

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(/* top row, see step 3.1 */) { ... }
        Row(/* bottom row */) {
            // parent dir (unchanged)
            // tags use tagsLimit instead of hard-coded 3:
            if (data.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (t in data.tags.take(tagsLimit)) {
                        TagChip(t)
                    }
                    if (data.tags.size > tagsLimit) {
                        ProvideContentColor(colors.inkMuted) {
                            Text("+${data.tags.size - tagsLimit}", style = AppTheme.typography.caption)
                        }
                    }
                }
            }
        }
    }
}
```

### Step 3.3: Compile

Run: `./gradlew :shared:ui-shared:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

### Step 3.4: Visual smoke

Run: `./gradlew :app-desktop:run`
Resize the window through the breakpoints (≈ 480 → 540 → 660 → 780 → 900 → full):
- ≥ 880dp: all 5 stats visible.
- 760–880: length disappears.
- 640–760: length + meter disappear; tag chips cap at 2.
- 520–640: length + meter + tracks disappear; tag chips cap at 2.
- < 520: only effort stays.

If running the app is not feasible in your environment, document that in the commit body.

### Step 3.5: Commit

```bash
git add shared/ui-shared/src/commonMain/kotlin/com/sketchbook/uishared/components/SongStrip.kt
git commit -m "feat(shelf): width-driven stat dropping + tag-cap in SongStrip"
```

---

## Task 4: Version pill (group card with stacked-paper background)

**Files:**
- Modify: `shared/ui-shared/src/commonMain/kotlin/com/sketchbook/uishared/components/SongStrip.kt`

The `VersionPill` is small enough to live as a `private @Composable` inside `SongStrip.kt` next to `TagChip` and `SyncPip`. No new file.

### Step 4.1: Add the `VersionPill` composable

Add inside `SongStrip.kt`, near `TagChip` (around line 242):

```kotlin
@Composable
private fun VersionPill(count: Int) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.tintCream)
            .border(1.dp, colors.ruleLine, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        ProvideContentColor(colors.inkSecondary) {
            Text(
                "v$count",
                style = AppTheme.typography.mono.copy(fontSize = 11.sp()),
            )
        }
    }
}
```

### Step 4.2: Render the pill between name and stats when `variantCount > 1`

In the top `Row`, after the name+warning inner `Row` and before the stat block:

```kotlin
if (data.variantCount > 1) {
    VersionPill(count = data.variantCount)
}
```

The pill should NOT be inside the `Modifier.weight(1f)` name row — it sits as its own siblings of the name row in the outer top `Row`, so the name still ellipsizes against it.

### Step 4.3: Add the stacked-paper background variant

The simplest implementation: when `variantCount > 1`, render an offset shadow card behind the main card. Wrap the existing `Column(modifier = Modifier...clip...background...border...clickable...padding)` in a `Box`, with a sibling `Box` drawn first at +3dp offset, +0dp horizontal, same shape and a slightly darker background (`surfaceSunken`).

```kotlin
val isGroup = data.variantCount > 1

Box(modifier = modifier.fillMaxWidth()) {
    if (isGroup) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = 3.dp, y = 3.dp)
                .clip(shape)
                .background(colors.surfaceSunken)
                .border(1.dp, colors.ruleLine, shape),
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(1.dp, colors.ruleLine, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onOpen,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // ...top row + bottom row from prior tasks...
    }
}
```

Add the import: `import androidx.compose.foundation.layout.offset`.

The outer `modifier` argument moves to the wrapping `Box` so callers can still pass layout modifiers. The `Column` keeps `Modifier.fillMaxWidth()` (no longer takes the caller's `modifier`).

### Step 4.4: Compile

Run: `./gradlew :shared:ui-shared:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

### Step 4.5: Visual smoke

Run: `./gradlew :app-desktop:run`
- Find or simulate a project group with multiple `.als` variants (the existing scan should produce them naturally for any project folder containing `Track v1.als`/`v2.als`).
- The group row shows a stacked-paper shadow at +3,+3dp behind it and a `v3` pill (or whatever the variant count is) between the name and stats.
- Singletons render exactly as before (no shadow, no pill).
- Hover still highlights only the front card.

### Step 4.6: Commit

```bash
git add shared/ui-shared/src/commonMain/kotlin/com/sketchbook/uishared/components/SongStrip.kt
git commit -m "feat(shelf): stacked-paper card + version pill for multi-variant project groups"
```

---

## Task 5: Restrained color signals (empty-effort em-dash + danger-tinted border)

**Files:**
- Modify: `shared/ui-shared/src/commonMain/kotlin/com/sketchbook/uishared/components/SongStrip.kt`

### Step 5.1: Empty effort em-dash in `inkFaint`

The existing `Stat()` (around line 192) accepts `accent: Boolean`. Extend it to accept an `empty: Boolean` flag, defaulting false. When `empty == true`, render the value in `colors.inkFaint` regardless of `accent`. Update the call site for effort:

```kotlin
Stat(
    label = "effort",
    value = data.effortScore?.toString() ?: "—",
    width = 36.dp,
    accent = (data.effortScore ?: 0) >= 60,
    empty = data.effortScore == null || data.effortScore == 0,
)
```

In `Stat`, replace the value text color block with:

```kotlin
val tone = when {
    empty -> colors.inkFaint
    accent -> colors.accentAction
    else -> colors.inkSecondary
}
ProvideContentColor(tone) {
    Text(
        value,
        style = AppTheme.typography.mono.copy(
            fontSize = 12.sp(),
            fontWeight = if (accent) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
        ),
    )
}
```

### Step 5.2: Danger-tinted border when warning present

In `SongStrip` (just before computing `border` color), pick the border color based on `warning`:

```kotlin
val borderColor = if (data.warning != null) {
    colors.accentDanger.copy(alpha = 0.4f)
} else {
    colors.ruleLine
}
```

Replace the `.border(1.dp, colors.ruleLine, shape)` on the front `Column` with `.border(1.dp, borderColor, shape)`. Leave the shadow card's border on `colors.ruleLine` (we don't want the shadow to glow red — it would look louder than the front card).

The existing ⚠ glyph stays.

### Step 5.3: Compile

Run: `./gradlew :shared:ui-shared:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

### Step 5.4: Visual smoke

Run: `./gradlew :app-desktop:run`
- A project with `missingSampleCount > 0` shows a faintly red-tinted border + the ⚠ glyph.
- A project with effort 0 or null renders `—` in `inkFaint`; effort ≥ 60 stays terracotta semibold; mid-range effort stays `inkSecondary`.

### Step 5.5: Commit

```bash
git add shared/ui-shared/src/commonMain/kotlin/com/sketchbook/uishared/components/SongStrip.kt
git commit -m "feat(shelf): restrained color signals — empty-effort em-dash, danger border on warnings"
```

---

## Task 6: Final validation

### Step 6.1: Full build + tests

Run: `./gradlew :shared:ui-shared:jvmTest :shared:feature-projects:jvmTest`
Expected: all tests pass.

Run: `./gradlew :app-desktop:assemble`
Expected: BUILD SUCCESSFUL.

### Step 6.2: Manual cross-check against the design doc

Open `docs/plans/2026-05-05-songstrip-readability-design.md` and walk every "Files touched" entry / "Test plan" bullet against the actual diff. If any of these is missing in the code, fix and recommit:

- `maxLines = 1, overflow = Ellipsis` on project name? (Task 2)
- "+N" string concatenation removed from `name`? (Task 1)
- `variantCount` in `SongStripData` and threaded through? (Task 1)
- Width-driven stat dropping at the documented breakpoints? (Task 3)
- Tag chip cap at 2 below 760dp? (Task 3)
- Stacked-paper variant + `vN` pill when `variantCount > 1`? (Task 4)
- `inkFaint` em-dash when effort empty? (Task 5)
- Danger-tinted border when `warning != null`? (Task 5)
- No new color tokens added to `Colors.kt`? (must not have changed)

### Step 6.3: Final commit (if any cleanup) + summary

If everything checks out, no extra commit is needed. Otherwise commit the cleanup with:

```bash
git commit -m "chore(shelf): align implementation with readability design doc"
```

Confirm to the user: "Implementation complete. SongStrip is now width-aware, the version count is a proper pill on a stacked-paper card, and broken state shows on the border. Effort heat stays binary as the user requested. No new color tokens added."

---

## Out of scope (future work)

- Variants list rendered inside the detail panel.
- Tag overflow rework beyond `+N` + width-driven cap.
- Marketing site / `site/index.html`.
- Other desktop screens (settings, scan UI, repair UI).
- New color tokens or palette changes.
