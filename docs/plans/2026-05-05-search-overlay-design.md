# Search Overlay — Design

**Status:** Approved 2026-05-05.
**Goal:** Stop hiding the home dashboard when the user types in the search field. Search results render as an overlay on top of whatever the user was looking at (Home or a zoomed shelf), with obvious dismiss affordances so the user can return to context with one click / Esc / × keystroke.

## Problem

`shared/feature-projects/.../ProjectListScreen.kt:132-153` has a 3-way `when` on render mode:

1. `state.query.isNotBlank()` → `SearchResults` replaces the whole panel.
2. `zoomShelf != null` → `ShelfFlat` replaces the whole panel.
3. else → `HomeDashboard`.

Typing in the search field therefore *hides* the home content entirely. The only way out is clearing the text field manually — there is no back chip (the zoom-shelf branch has one at line 116), no Esc handler, no × button. Users get stuck in a search detour with no obvious return path.

## Approach

Layer the existing `SearchResults` content as an **overlay** on top of the existing base layer (Home or ShelfFlat), driven by the same `state.query` value the search field already updates. No state-holder changes; no new color tokens; reuses today's row rendering.

### Visual / layout

- **Base layer** (always present): `HomeDashboard` if no zoom, `ShelfFlat` if `zoomShelf != null`. Identical to today minus the search branch of the `when`.
- **Overlay** (rendered above the base layer iff `state.query.isNotBlank()`):
  - **Scrim** — full-size `Box` with `colors.surfaceSunken.copy(alpha = 0.55f)` over the base layer, intercepts clicks, dispatches `Intent.Search("")` on tap.
  - **Panel** — anchored directly under the existing search field, full panel width (matching the field), `max-height = 60% viewport`, internal scroll. Inside: today's `SearchResults` composable verbatim.
- **Search field stays above the scrim** — still focusable / typable while overlay is active. `ScanIndicator` and the dashboard go *behind* the scrim and dim with the rest of home.
- **DetailPanel z-order** — slides in over the overlay, exactly as it does over the dashboard today. Closing the detail panel returns to the still-open overlay (because `state.query` is untouched).

### Dismissal

All three are aliases for "clear query":

- **Esc** with focus on the search field → `Intent.Search("")`.
- **Click on the scrim** → `Intent.Search("")`.
- **`×` suffix on the search field**, shown when `value.isNotBlank()` → `Intent.Search("")`.

Because the overlay's visibility is `state.query.isNotBlank()`, clearing the query auto-closes the overlay. No separate "open/closed" flag.

### Result selection

`SongStrip.onOpen` already triggers `openDetailId = it.representative.id`. That stays. `state.query` is **not** modified. Closing the detail panel reveals the still-open overlay — preserving the user's place in the result list, per the user's preference for scanning multiple matches in a row.

## Touchpoints

| File | Change |
| --- | --- |
| `shared/ui-shared/src/commonMain/kotlin/com/sketchbook/uishared/components/TextField.kt` | Add optional `onClear: (() -> Unit)?` param. When non-null AND value non-blank, render a trailing `×` slot that invokes `onClear` on click. |
| `shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/ProjectListScreen.kt` | Restructure the `when`: render base layer always (Home or ShelfFlat); conditionally render overlay (scrim + panel + reused `SearchResults`) on top. Wire Esc on the field via `onPreviewKeyEvent`. Pass `onClear = { holder.dispatch(Intent.Search("")) }` to the search `TextField`. |

No new files. No new color tokens. No state-holder changes. No row-rendering changes (`SongStrip` and `SearchResults` reused as-is).

## Out of scope (future work)

- Recent searches / search hints when field is focused with empty query.
- Search-result grouping by category (projects vs. plugins vs. samples).
- Keyboard navigation through results (↑/↓/Enter).
- Highlighting matched substrings in result names.

## Test plan

- **Unit:** existing `ProjectListStateHolderTest` covers `Intent.Search` updating/clearing query. Add one regression test asserting `Search("")` resets the state to the same `query` value as the initial state.
- **Visual smoke (desktop):**
  1. Type into field → scrim dims dashboard, overlay panel renders results below the field.
  2. Click scrim → query clears, overlay closes, dashboard returns un-dimmed.
  3. Press Esc → same.
  4. Click `×` → same.
  5. Click a result row → detail panel slides in over overlay; dismiss panel → overlay still visible with same results.
  6. Type while in zoomed-shelf view (e.g. "all forgotten gems") → overlay opens above ShelfFlat; clearing query returns to ShelfFlat (not Home).
