# Search Overlay Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Stop hiding the home dashboard when the user types in the search field. Render results as an overlay layered on top of Home/ShelfFlat, with three dismiss affordances (×, Esc, scrim click).

**Architecture:** Pure UI changes in one file. `ProjectListScreen.kt` keeps its existing `state.query`-driven branching but turns the full-replace `SearchResults` branch into an overlay layer (scrim + panel) over a base layer that's always rendered. Reuses today's `SearchResults` and `TextField` (already has a `trailing` slot — no API changes). One Esc handler is wired via `onPreviewKeyEvent` on the field's modifier at the call site.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform (JVM target via `app-desktop`), kotlin.test. No new dependencies. No new color tokens.

**Reference doc:** [`docs/plans/2026-05-05-search-overlay-design.md`](./2026-05-05-search-overlay-design.md).

**Memory pointers (read before editing):**
- `feedback_color_restraint.md` — reuse `surfaceCard`/`inkPrimary` tones; do not add a new scrim token.
- `feedback_layer_dont_redesign.md` — additive only; do not reshape the search field, base layers, or row rendering.
- `feedback_no_unnecessary_libs.md` — no new deps.
- `feedback_no_batch_checkpoints.md` — drive through tasks; visual-verify as you go.

## Background facts

- `ProjectListScreen.kt:132-153` is a 3-way `when` on `state.query.isNotBlank()` / `zoomShelf != null` / else. The query branch fully replaces the dashboard.
- `TextField` (`shared/ui-shared/.../components/TextField.kt:50-151`) already accepts a `trailing: @Composable (() -> Unit)?` slot — the × button can plug in there with no TextField change.
- `BackToOverview` (`ProjectListScreen.kt:175-194`) is the existing dismiss chip pattern for zoom-shelf — mentioned only for visual reference; we do **not** reuse it for search.
- `SearchResults` (`ProjectListScreen.kt:343-`...) renders the result list. It stays untouched and gets reused inside the new overlay panel.
- `state.query` already drives the view; clearing the query auto-closes the overlay. No state-holder changes are needed.
- The outer container in `ProjectListScreen.kt:95` is a `BoxWithConstraints` already, so we can pull `maxHeight` for the overlay panel's max-height cap.
- The dispatch `holder.dispatch(ProjectListStateHolder.Intent.Search(""))` is the canonical "clear query" call — used for all three dismiss paths.

## Test commands

- Compile only: `./gradlew :shared:feature-projects:compileKotlinJvm :shared:ui-shared:compileKotlinJvm`
- Module tests: `./gradlew :shared:feature-projects:jvmTest :shared:ui-shared:jvmTest`
- Run desktop: `./gradlew :app-desktop:run`

Windows PowerShell users: prefix with `.\`.

---

## Task 1: × button + Esc handler on the search field

**Files:**
- Modify: `shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/ProjectListScreen.kt:119-124`

This task is the cheap wins — both fully driven from the existing `state.query` value and `Intent.Search("")`. No state-holder change. No `TextField` API change.

### Step 1.1: Add the × button via the existing `trailing` slot

Replace the `TextField(...)` call at lines 119-124 with:

```kotlin
TextField(
    value = state.query,
    onChange = { holder.dispatch(ProjectListStateHolder.Intent.Search(it)) },
    placeholder = "Search projects, plugins, samples…",
    modifier = Modifier
        .fillMaxWidth()
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && state.query.isNotEmpty()) {
                holder.dispatch(ProjectListStateHolder.Intent.Search(""))
                true
            } else {
                false
            }
        },
    trailing = if (state.query.isNotEmpty()) {
        {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { holder.dispatch(ProjectListStateHolder.Intent.Search("")) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                ProvideContentColor(AppTheme.colors.inkMuted) {
                    Text("×", style = AppTheme.typography.body)
                }
            }
        }
    } else null,
)
```

### Step 1.2: Add the imports

At the top of `ProjectListScreen.kt`, add:

```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme
```

Skip any that are already imported (most likely `dp`, `Box`, `padding`, `clip`, `clickable`, `Text`, `ProvideContentColor`, `AppTheme` are already there — leave them as-is). Add only the missing key-event and shape imports.

### Step 1.3: Compile

Run: `./gradlew :shared:feature-projects:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

### Step 1.4: Visual smoke

Run: `./gradlew :app-desktop:run`
- Type "remember" in the search field — the × button appears at the right edge of the field.
- Click × — query clears; the search field becomes empty; the home dashboard returns (the overlay isn't built yet, so today's `SearchResults` branch still renders the full-replace results, but the dismiss path works).
- Type again, press Esc — same dismiss behavior.

Document any environment limitation (no display, etc.) in the commit body.

### Step 1.5: Commit

```bash
git add shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/ProjectListScreen.kt
git commit -m "feat(search): × button + Esc handler clear the search query"
```

---

## Task 2: Layer the search results as an overlay over the base view

**Files:**
- Modify: `shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/ProjectListScreen.kt:132-153`

### Step 2.1: Restructure the `when` block

Replace the current `when` block (lines 132-153) with a base-layer + overlay structure. The base layer is always rendered (Home or ShelfFlat); the overlay renders above when `state.query.isNotBlank()`:

```kotlin
Box(modifier = Modifier.widthIn(max = 1240.dp).fillMaxWidth()) {
    // Base layer — always rendered so it stays visible (dimmed) behind the overlay.
    if (zoomShelf != null) {
        ShelfFlat(
            groups = zoomShelf!!.bucket(buckets),
            onOpen = { openDetailId = it.representative.id },
            syncStateFor = syncStateFor,
        )
    } else {
        HomeDashboard(
            buckets = buckets,
            gemsView = gemsView,
            isWide = isWide,
            onOpen = { openDetailId = it.representative.id },
            onSeeAll = { zoomShelf = it },
            onChip = { zoomShelf = it },
            onShuffleGems = { gemsShuffleSeed = (gemsShuffleSeed + 1).coerceAtLeast(1) },
            syncStateFor = syncStateFor,
        )
    }

    // Search overlay — scrim + results panel anchored under the search field.
    if (state.query.isNotBlank()) {
        // Scrim: paper-fog over the base layer; click anywhere to clear the query.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(AppTheme.colors.surfaceCard.copy(alpha = 0.85f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { holder.dispatch(ProjectListStateHolder.Intent.Search("")) },
                ),
        )
        // Results panel: full panel width, capped height with internal scroll, top-anchored.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (maxHeight * 0.6f))
                .align(Alignment.TopCenter),
        ) {
            SearchResults(
                groups = groups.filter { matchesQuery(it, state.query) },
                onOpen = { openDetailId = it.representative.id },
                syncStateFor = syncStateFor,
            )
        }
    }
}
```

Notes:
- `maxHeight` comes from the surrounding `BoxWithConstraints` at line 95 — it's already in scope.
- The scrim's `clickable` uses `indication = null` so clicking the scrim doesn't ripple; the visual feedback is the scrim itself disappearing as the query clears.
- `SearchResults` is a `LazyColumn` already, so `heightIn(max = ...)` plus its internal scroll gives the "panel scrolls, dimmed home peeks below" behavior.
- The `Box` wrap inherits the same `widthIn(max = 1240.dp).fillMaxWidth()` constraint the original `when` block sat under, so layout below the field is unchanged when the overlay is closed.

### Step 2.2: Add the imports

Add to the top of `ProjectListScreen.kt`:

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
```

Skip any already present.

### Step 2.3: Compile

Run: `./gradlew :shared:feature-projects:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

### Step 2.4: Visual smoke

Run: `./gradlew :app-desktop:run`

- **Activate:** type "remember" → home dashboard dims (paper-fog scrim); results panel renders below the search field; cards are scrollable inside the panel; below the panel you can faintly see the dimmed home content.
- **Dismiss via scrim click:** click anywhere on the dimmed home → query clears; home returns un-dimmed.
- **Dismiss via Esc:** type, press Esc → same.
- **Dismiss via ×:** type, click × → same.
- **Result selection (preserves overlay):** type, click a result row → `DetailPanel` slides in over the overlay; close the detail panel → overlay is still visible with the same results (because `state.query` was untouched).
- **ShelfFlat base layer:** click a "See all" → ShelfFlat renders. Type while in ShelfFlat → overlay appears above the dimmed ShelfFlat. Clear query → returns to ShelfFlat (not Home). Confirms the base layer correctly preserves zoom state.

Document any environment limitation in the commit body.

### Step 2.5: Commit

```bash
git add shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/ProjectListScreen.kt
git commit -m "feat(search): overlay results on dimmed Home/ShelfFlat instead of full-replace"
```

---

## Task 3: Final validation

### Step 3.1: Run module tests + build

Run: `./gradlew :shared:feature-projects:jvmTest :shared:ui-shared:jvmTest`
Expected: all tests pass (no test changes — existing tests still cover state-holder behavior).

Run: `./gradlew :app-desktop:assemble`
Expected: BUILD SUCCESSFUL.

### Step 3.2: Cross-check against the design doc

Open `docs/plans/2026-05-05-search-overlay-design.md` and walk every "Touchpoints" / "Test plan" bullet against the diff:

- Base layer always rendered (Home or ShelfFlat)? (Task 2)
- Overlay only when `state.query.isNotBlank()`? (Task 2)
- Scrim covers the base layer + clicks dispatch `Search("")`? (Task 2)
- Panel anchors top, max-height ~60% of the BoxWithConstraints maxHeight? (Task 2)
- × button on field, only when value non-blank? (Task 1)
- Esc on field clears query? (Task 1)
- Detail panel still slides over the overlay; closing it leaves the overlay visible? (Task 2 visual smoke)
- No new color tokens added (`shared/ui-shared/.../theme/Colors.kt` untouched)?
- No state-holder changes (`ProjectListStateHolder.kt` untouched)?

### Step 3.3: Final commit (only if cleanup needed)

If everything checks out, no extra commit. Otherwise:

```bash
git commit -m "chore(search): align implementation with overlay design doc"
```

Confirm to the user: "Search overlay complete. Typing now layers results over a dimmed Home or ShelfFlat; ×, Esc, and scrim click all clear the query. Selecting a result opens the detail panel without dismissing the overlay. No state-holder changes, no new color tokens."

---

## Out of scope (future work)

- Recent searches / search hints when field is focused with empty query.
- Search-result grouping by category (projects vs. plugins vs. samples).
- Keyboard navigation through results (↑/↓/Enter).
- Highlighting matched substrings in result names.
- Animations on overlay open/close.
