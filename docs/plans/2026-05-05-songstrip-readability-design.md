# SongStrip readability — truncation, resize, version card, restrained color

Date: 2026-05-05
Scope: desktop app only (`shared/ui-shared`, `shared/feature-projects`)

## Problem

The project shelf rows (`SongStrip`) over-pack information and degrade poorly at
smaller window widths. Three concrete issues:

1. **String concatenation as data.** `ProjectListScreen.kt:528` glues `"  +N"`
   onto the project name when a folder has multiple `.als` variants. The "+2"
   becomes part of the displayed name string instead of being its own UI element.
2. **No truncation.** The project name in `SongStrip.kt:121` has no `maxLines`
   or `overflow` — long names wrap or push the row's stat columns and timestamp
   off-balance.
3. **No resize handling.** The row keeps all 5 stat columns (bpm / meter /
   tracks / length / effort) + sync pip + relative timestamp at every width.
   On narrow windows this squeezes the name to nothing.

The user also wants light coloring help, but with strict uniformity — reuse
existing palette tokens, don't multiply colors per state.

## Design

### 1. Truncation & resize

Project name (`SongStrip.kt:121`) gets `maxLines = 1, overflow = Ellipsis` while
keeping `Modifier.weight(1f, fill = false)`.

`SongStrip` wraps its top row in `BoxWithConstraints` and hides stats by
priority as width shrinks:

| width      | bpm | meter | tracks | length | effort |
|------------|-----|-------|--------|--------|--------|
| ≥ 880 dp   | ✓   | ✓     | ✓      | ✓      | ✓      |
| ≥ 760 dp   | ✓   | ✓     | ✓      | —      | ✓      |
| ≥ 640 dp   | ✓   | —     | ✓      | —      | ✓      |
| ≥ 520 dp   | ✓   | —     | —      | —      | ✓      |
| < 520 dp   | —   | —     | —      | —      | ✓      |

Color bar, name, sync pip, and relative timestamp always show. Effort and bpm
survive longest because they are the most-glanced fields for music projects.
Tag row caps at 2 chips at narrow widths (was 3 across all widths) so the
parent-dir + tags line doesn't wrap.

### 2. Version card

When `ProjectGroup.variantCount > 1`, `SongStrip` renders a stacked-paper
variant: the existing card with a 2 dp offset shadow card behind it suggesting
layered paper. Same height and padding as a singleton, so layout is uniform
across the shelf.

The "+N" string concatenation is removed. A small `VersionPill` composable
(in `ui-shared/components/`) sits between the name and the stat columns,
showing `vN` (variant count) in mono on `tintCream` background with
`inkSecondary` text. Single rounded chip, ~4 dp vertical padding.

`ProjectListScreen.kt:524-542` is updated:

- `name` becomes `r.name` (no concatenation).
- `SongStripData` gains `variantCount: Int` (default 1).
- Singleton rows render exactly as today (no shadow, no pill).

The detail panel route is unchanged — clicking the row or the pill calls
`onOpen`. Showing the variants list inside the detail panel is **out of scope
for this pass**; left as a follow-up note in the implementation plan.

### 3. Coloring (restrained)

Two changes only. No new color tokens.

**Effort accent extension (one new tier).** The existing `accent = score >= 60`
behavior in `Stat()` (`SongStrip.kt:144` / `:198-216`) stays. Additionally,
when score is null or 0, the value renders as an em-dash in `inkFaint` so
empty effort is visibly absent, not just dimmer. No mid-tier color — uniform
"earned attention or it didn't" signal.

**Status border accent (danger only).** When
`parseStatusBest == Failed || missingSampleCount > 0`, the card border switches
from `ruleLine` to `accentDanger` at ~40% opacity. Same 1 dp width. The
existing ⚠ glyph stays. Conflict / sync warnings stay on the sync pip alone —
not promoted to the border.

Shelf background tints (originally proposed) are dropped: the
`HighlightsStrip` chips already color-code shelves and the headers title
them. A second background layer is redundant.

## What stays the same

- Tag chips and their existing "+N" overflow (`SongStrip.kt:181-185`) — this
  is a small caption, not a primary identifier.
- `Stat()` widths and labels.
- `Shelf` / `ShelfFlat` / `SearchResults` composables — they hand the same
  `SongStripData` to `SongStrip`.
- Bucketing rules, grouping rules, detail panel mechanics.
- All existing palette tokens.

## Files touched

- `shared/ui-shared/src/commonMain/kotlin/com/sketchbook/uishared/components/SongStrip.kt`
  — `BoxWithConstraints` width-driven stat hiding; `maxLines`/`overflow` on
  name; new `VersionPill` rendering; stacked-paper background when
  `variantCount > 1`; danger border when warning present; em-dash empty
  effort.
- `shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/ProjectListScreen.kt`
  — `toSongStripData` no longer concatenates "+N" into name; passes
  `variantCount` and warning fields through.
- `SongStripData` (in `SongStrip.kt`) — gains `variantCount: Int = 1`.

## Out of scope

- Variants list inside the detail panel (follow-up).
- Tag overflow rework (keep "+N" for now).
- Marketing site / `site/index.html` (user clarified: desktop only).
- New color tokens or palette additions.
- Other screens (settings, scan UI, repair UI) — flag separately if issues
  surface.

## Test plan

- Resize the window from full-width down to ~480 dp; stats drop in the
  documented order; name ellipsizes; row never wraps.
- A shelf containing a project group with 3 variants shows a stacked-paper
  card and a `v3` pill; singletons still look unchanged.
- A project with `missingSampleCount > 0` shows the danger-tinted border and
  the existing ⚠ glyph.
- Effort 0 / null shows an em-dash in `inkFaint`; effort ≥ 60 stays terracotta
  semibold.
- Existing `ProjectListStateHolderTest` still passes; new tests cover
  `toSongStripData` no longer concatenating "+N".
