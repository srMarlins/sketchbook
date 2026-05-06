# Sketchbook — design language

> Visual + interaction language for the Compose Multiplatform desktop app. Source of truth for theme tokens, component vocabulary, motion, assets, accessibility, and AI presence. Originally authored 2026-05-04 alongside the (retired) React frontend; aesthetic principles carried over to Compose. The "tech wiring" section is intentionally kept short — see `shared/ui-shared/` for the Compose implementation and `gradle/libs.versions.toml` for the dependency list.

---

## 1. Vision & non-goals

### Vision

**Sketchbook** is the web UI for the audio catalog. Visual metaphor: a tactile, layered, hand-drawn lined-paper notebook on a wooden desk — colored construction-paper "song strips" pinned, taped, and stapled to the page; a corkboard tacked to the side of the desk for project detail; a torn-page pile holding pending AI proposals.

The metaphor exists for one reason: a chaotic library of ~1,628 Ableton sketches needs to feel inviting to revisit. A spreadsheet doesn't invite. A sketchbook does.

### Goals

- Make scanning ~1,628 projects feel like browsing a portfolio, not auditing a database.
- Hold one consistent visual language across catalog, proposals, project-detail, and (future) mix-analysis surfaces.
- Be theme-able for light + dark from day one via a proper token system.
- Render every project's Ableton color tag truthfully (full 14-color palette).
- Keep all writes legible — proposals pile + journal already locked in catalog design §7.

### Non-goals

- **Not a chat UI for Claude.** Conversational AI lives in CLI/MCP. The web UI is the *user's* approval & browsing surface.
- **Not a DAW.** No transport, no waveform timeline editing, no mixer in v0.1.
- **Not a public-facing or multi-user surface.** Localhost only, single user, no responsive/mobile layout.
- **Not a general-purpose component library.** Components serve this app; we don't build a reusable design system.
- **Not skeuomorphic for skeuomorphism's sake.** Every textured element must earn its weight by carrying meaning (color tag = strip color, proposals = torn pile, etc.).

---

## 2. Layout & navigation model

### Persistent chrome (always visible)

- **Wood-grain desk** — full-bleed background. Color-tinted per theme.
- **Spiral binding** — pinned to the left edge of the active notebook page.
- **Sidebar (paper-strip nav strips, pinned to the desk left of the binding):**
  - `HOME` (shelf view)
  - `PROJECTS` (the user's main lined-paper notebook)
  - `PROPOSALS` (torn-page pile — badge count when non-empty)
  - `CLAUDE` (kraft-paper composition book — activity log)
  - `TAGS` (one notebook per tag, when any exist)
  - `ARCHIVE` (manila notebook)
  - `NEW SKETCH` (yellow strip — primary creation CTA, deferred behavior in v0.1)
- **Branding header** — the colored cutout-letter wordmark "Sketchbook" pinned at the top-left of the active page.
- **Search bar** — kraft-paper torn strip pinned at the top-right of the active page; always visible; `Cmd-K` / `Ctrl-K` / `/` focuses it.
- **Filter chips row** — doodled chips below the search bar; renders only when filters or queries are active.

### Two top-level routes

1. **`/` — Home / Shelf.** A wooden shelf with notebook spines visible: Inbox (un-foldered root projects), one notebook per year (`2024`, `2025`, `2026`), one per active tag, plus Archive and Claude's notebook. Each spine shows count + last-updated. Click a spine → opens that notebook.
2. **`/n/<notebook-id>` — Notebook view.** The standard sketchbook page. Lined paper, infinite-scroll virtualized list of song strips. Sticky page header inside the notebook shows notebook title (handwritten) + filter chips + current sort + result count. Search results temporarily replace the strip list (Esc restores).

### Detail surface

The **corkboard panel** is a slide-in mounted to the right edge of the desk; opens when a strip is clicked, slides in over the wood (not over the notebook). Catalog stays visible on the left for comparison. Width ~480px, fixed. Tabs (overview / tracks / samples / plugins / history) are paper-strip tabs down the panel's left edge. Esc or outside-click closes it.

### Z-axis layering (token system, not ad-hoc `z-index`)

```
z-desk     →  wooden background
z-page     →  notebook lined paper
z-strip    →  song strips
z-attach   →  tape, pins, staples, paperclips
z-panel    →  corkboard slide-in
z-overlay  →  margin sticky notes, command palette
z-toast    →  toasts
```

Defined once in `tailwind.config.ts`.

---

## 3. Theme system

### Architecture

Themes are CSS custom properties on `:root` and `[data-theme="dark"]`, surfaced through Tailwind via `theme.extend.colors` referencing `var(--…)`. **No hardcoded hex in components.** Theme switch is a single `data-theme` attribute on `<html>`; persists in localStorage; respects `prefers-color-scheme` on first load.

### Token tiers

**1. Primitives — theme-agnostic raw values.**
- `--paper-cream-50…900`
- `--wood-walnut-50…900`
- `--ink-graphite-50…900`
- `--kraft-50…900`
- **Ableton-14 palette:** `--als-1` … `--als-14`. These are *truthful* Ableton colors and **never change between themes** (they are project data, not chrome).

**2. Semantic — theme-swapped, the layer components consume.**
- Surfaces: `--surface-desk`, `--surface-page`, `--surface-strip-base`, `--surface-corkboard`, `--surface-panel`, `--surface-overlay`, `--surface-kraft`
- Ink: `--ink-primary` (handwriting), `--ink-secondary` (sans/mono body), `--ink-muted`, `--ink-on-strip-light`, `--ink-on-strip-dark`
- Lines & shadows: `--rule-line`, `--shadow-page`, `--shadow-lift`, `--shadow-pin`
- Decorations: `--tape-washi-{1..6}`, `--pin-{green,blue,orange,purple,red,yellow}`
- Tinting: `--tint-overlay` — per-theme tint applied over raster paper/wood textures
- Accents: `--accent-action` (red CTA), `--accent-secondary` (grey)

**3. Component tokens — kept rare**, used only when a component has a need not covered by semantic.

### Light theme

Cream paper (`--paper-cream-100`), warm walnut desk, graphite ink, no tint overlay (or `transparent`).

### Dark theme — "night-shift sketchbook"

Same paper raster + amber-dim tint overlay (`rgba(40, 28, 18, 0.55)`); same wood raster + deeper walnut tint; ink stays dark (handwriting reads as ink on a dim-lit page, not chalk on slate); pin colors desaturate ~15%; **Ableton-14 palette renders unchanged**. Theme is a pure color-token swap — no second set of textures.

### Ableton-14 strip rendering rule

A strip's base color is `var(--als-N)`. Text-on-strip color is decided per-color via a precomputed contrast table (`contrast-table.ts`): light strips get `--ink-on-strip-light` (near-black); dark strips get `--ink-on-strip-dark` (near-white); whichever passes 4.5:1. Strip texture (rough construction-paper edge) is an SVG mask + subtle paper-grain overlay at low opacity — same in both themes.

---

## 4. Typography

### Three font roles, three tokens

| Token | Family (v0.1 pick) | Used for |
|---|---|---|
| `font-display` | Permanent Marker (primary) + Gloria Hallelujah (secondary) | Nav strip labels, notebook tab titles, page titles, button labels, search placeholder, empty-state copy, section dividers, doodled filter chip labels, AI sticky-note text |
| `font-mono` | Space Mono | All data values: project titles on strips, BPM, key, time-sig, track counts, lengths, paths, file hashes, timestamps, version strings, tag values |
| `font-sans` | Inter (variable, with system-stack fallback) | Utility text: form fields, table headers (corkboard), tooltips, error/toast messages, breadcrumbs, settings, validation hints, modal body |

The brand wordmark "Sketchbook" is a **single inline SVG** of colored construction-paper letter shapes — *not* this token system. Treated as an image, not type. Letter colors are fixed brand assets, never theme-swapped.

### Type scale

`xs 12 / sm 13 / base 14 / md 15 / lg 17 / xl 20 / 2xl 24 / 3xl 30 / 4xl 36`. Line-heights are font-specific: handwritten 1.4–1.6, mono 1.25, sans 1.45.

### Hand-font legibility rules (load-bearing)

- Handwritten **never** below 16px.
- Handwritten **never** for multi-sentence paragraphs (max ~6 words per element).
- Handwritten **never** as input field text (placeholder may be handwritten; typed value is `font-mono` or `font-sans`).
- Handwritten **never** for tabular data, even single cells.

### Font loading

- Self-hosted woff2 in `web/public/fonts/`.
- Preload `font-display` and `font-mono` (above-the-fold); `font-sans` loaded normally.
- `font-display: swap` with sane fallback metrics (Inter substitutes during swap; no FOIT).
- **Total font payload budget: ≤180 KB.**

### Doodled labels vs doodled icons

Filter chip labels and sticky-note text are *typed* in `font-display` (so they accept dynamic content like tag names). The hand-drawn icon next to each label is a sprite SVG.

---

## 5. Component vocabulary

### Surface (chrome)

- **`<Desk>`** — full-bleed wood-texture wrapper; mounts spiral binding, sidebar, branding header, search bar slots, and `<Outlet>`. Single instance, top of the React tree.
- **`<NotebookPage>`** — lined-paper surface with sticky header. Renders any virtualized list passed as children. Owns the spiral-binding left margin offset.
- **`<Shelf>`** — wood-shelf component for `/`. Renders an array of `<NotebookSpine>` items.
- **`<NotebookSpine>`** — clickable book-spine asset. Props: title, count, lastUpdated, accent color, kind (`lined` / `kraft` / `manila`).
- **`<CorkboardPanel>`** — right-side slide-in. Slots for tabs (overview / tracks / samples / plugins / history) rendered as paper-strip tabs down its left edge.
- **`<TornPagePile>`** — proposals queue surface. Renders `<ProposalCard>`s as torn-out pages stacked with deterministic rotation jitter (seeded by proposal id).

### Data

- **`<SongStrip>`** — *the* load-bearing component. Props: project (title, BPM, key, tracks, length, alsColor, tags, holdMethod). Renders: color base via `var(--als-N)`, mono data layout (title L / BPM+key C / tracks+length R), `holdMethod` overlay (washi tape variant or staple SVG, deterministic per project id), `font-mono` for all values, doodled icons next to BPM/key/tracks/length.
- **`<NavStrip>`** — paper-strip sidebar item. Props: label, doodle icon, pinColor, active. Active state press-in via `--shadow-pin`.
- **`<FilterChip>`** — doodled chip with handwritten label, dismiss-X, optional value badge.
- **`<MarginStickyNote>`** — small rotated paper square pinned (paperclip SVG) to the side of a strip or corkboard tab; carries AI inline suggestions.
- **`<ProposalCard>`** — torn-page card: verb + target + diff preview; approve/reject buttons (red/grey rough-edge).

### Inputs

- **`<SearchBar>`** — kraft-paper torn strip with masking-tape attach, magnifying-glass doodle, hand-written placeholder, `font-mono` typed input. Listens for `Cmd-K`, `Ctrl-K`, `/`.
- **`<TextInput>`** — used inside corkboard forms (notes, rename). Lined-paper inset, `font-mono` text.
- **`<Button>`** — three variants: `primary` (red rough-edge construction paper), `secondary` (grey rough-edge), `ghost` (handwritten label, no paper backing). Sizes sm/md/lg.

### Feedback

- **`<Toast>`** — paper-clipped sticky note that drops in at bottom-right; `font-sans` body. Auto-dismiss; `prefers-reduced-motion` removes drop animation.
- **`<EmptyState>`** — handwritten message + a single doodle illustration, centered on the lined page.
- **`<LoadingState>`** — pencil-sketching animation (a doodle being drawn line-by-line); cap 3 seconds before falling back to a static doodle.
- **`<ErrorState>`** — torn paper with handwritten "something tore" message + technical detail in `font-mono` (collapsed by default).

### Primitives layer

- **Radix UI** (headless, unstyled) for: Dialog, Popover, Tooltip, DropdownMenu, Tabs, ScrollArea, VisuallyHidden, Toast, Slider, Switch, Checkbox, Label, Separator, AccessibleIcon. Skinned in our sketchbook language.
- **TanStack Table v8 + TanStack Virtual** for the strip list.
- **TanStack Query** for server state.
- **TanStack Router** for routing.
- **No shadcn/ui, MUI, Chakra, or Mantine.**

---

## 6. Motion language

### Tokens

| Token | Value | Used for |
|---|---|---|
| `--ease-paper` | `cubic-bezier(0.2, 0.8, 0.2, 1)` | default ease-out for entries |
| `--ease-settle` | `cubic-bezier(0.4, 0, 0.6, 1)` | exits, dismissals |
| `--dur-fast` | 120ms | hover lift, focus rings, chip toggles |
| `--dur-base` | 200ms | corkboard panel slide, fade-in, search results swap |
| `--dur-slow` | 400ms | proposals "torn page lands", archive "tape peel" |

### Standard motions

- **Strip hover** — `translateY(-2px)`, `--shadow-lift` deepens, washi-tape/staple opacity 0.85 → 1.0. `--dur-fast` `--ease-paper`.
- **Strip click** — `scale(0.99)`, `--dur-fast`. Then triggers corkboard slide-in.
- **Corkboard panel** — `translateX` 100% → 0, `--dur-base` `--ease-paper`. Reverse on close.
- **Pin / nav active** — active strip presses 1px down, `--shadow-pin` replaces lift shadow. `--dur-fast`.
- **Search results swap** — old strips fade out (`--dur-fast`); new strips fade in staggered 12ms each, max 30 items animated then no stagger.
- **Filter chip add/remove** — width-grow + opacity, `--dur-base`.
- **Toast drop-in** — `translateY(8px → 0)` + opacity, `--dur-base`.
- **Notebook spine click → page open** — crossfade between shelf and notebook, `--dur-base` (cheaper than a flip, same intent).

### Carve-outs (the two opinionated motions)

- **Proposal lands.** When a new proposal arrives in the pile (live or after `propose_batch`), a torn-page card flutters in: `rotate(-6° → +2° → -1°)`, `translateY(-30px → 0)`, opacity `0 → 1`. `--dur-slow` `--ease-paper`. Stack jitter recalculated.
- **Archive tape-peel.** The washi tape rotates 8° away, the strip translates 40px and rotates -3° while fading to 0, `--dur-slow` `--ease-settle`. Then the row collapses (`height → 0`, `--dur-base`).

### Reduced-motion contract

When `prefers-reduced-motion: reduce`:
- All `translate`, `rotate`, `scale`, and shadow-depth changes are removed.
- Only opacity fades remain (≤120ms).
- Both carve-outs become instant state changes.
- Loading "pencil sketching" becomes a static placeholder doodle.

### Hard rules

- No bouncy / spring physics anywhere. Linear, ease-out, ease-in only.
- No sound effects in v0.1.

---

## 7. Asset pipeline

### Two raster textures (the only rasters in the system)

- `paper-grain.webp` — seamless tileable cream paper. Target ≤80 KB, ~512×512 tile.
- `wood-grain.webp` — seamless tileable walnut desk. Target ≤120 KB, ~1024×1024 tile.

Both are **desaturated and theme-neutral**. Color comes from a CSS overlay layer (`--tint-overlay`) so light/dark themes share the same files.

### SVG sprite sheet (`web/public/sprites.svg`, single file, `<symbol>` defs, referenced via `<use href="#name">`)

| Group | Members |
|---|---|
| Doodles | metronome, piano keyboard, microphone, cassette tape, drums, drumstick, dancer, stars, moon, cloud, rainstorm, magnifying glass, paper airplane, pencil-stub, paperclip, scissors, plus, X, check, chevron, house, bookmark |
| Field icons | bpm, key, tracks, length, time-sig |
| Sidebar nav icons | home, projects, proposals, claude, tags, archive, new-sketch |
| Tape variants | washi-1…washi-6, masking-tape, scotch-tape |
| Hold methods | staple-1, staple-2, paperclip, pin (× 6 colors) |
| Edges & masks | torn-edge-{top, bottom, left, right}, rough-cut-button (4 edge masks rotated), notebook-spiral-binding |
| Brand | `brand-sketchbook` (the cutout-letter wordmark, fixed colors) |

### Strip color rendering

A strip is one `<div>` with: base color `var(--als-N)`, paper-grain overlay (raster, low opacity ~0.18), torn-edge SVG mask via `mask-image`, optional tape/staple `<svg>` absolute-positioned on top with deterministic seed (project id → hold method).

### Determinism

Anything that "looks random" — washi pattern per strip, rotation jitter on torn pages, position jitter on doodled margin marks — is seeded by stable IDs (project id, proposal id) so renders are stable across reloads. **No `Math.random()` in render paths.**

### Performance budget

| Asset | Budget |
|---|---|
| Initial JS (gz) | ≤300 KB |
| Initial CSS (gz) | ≤40 KB |
| Sprite sheet | ≤120 KB |
| Two rasters | ≤200 KB total |
| Fonts | ≤180 KB total |
| **Total cold-load** | **≤900 KB** |
| Strip render | 60fps virtualized scroll over 1,628 rows |

TanStack virtualizer + `content-visibility: auto` on offscreen strips.

### Asset organization

```
web/public/
  textures/    # the two webp rasters
  fonts/       # 3 woff2 files + license
  sprites.svg  # all SVG sprites in one file
web/src/
  assets/
    icons/     # individual SVG components for doodles needing animation
    masks/     # SVG <clipPath> definitions
```

### Build pipeline

Vite handles bundling; sprite sheet generated at build time from individual SVG sources via `vite-plugin-svg-sprite` (or hand-authored). Textures committed as-is.

---

## 8. Accessibility

### Color & contrast

- All text-bearing surfaces hit WCAG AA (4.5:1 body, 3:1 for ≥18px or ≥14px-bold).
- Ableton-14 strip colors are tested against both `--ink-on-strip-light` and `--ink-on-strip-dark`; precomputed in `contrast-table.ts` — not computed at runtime.
- Filter chips, tape labels, and pin labels never rely on color alone; every status carries a doodled icon or text.
- Focus rings: 2px outline in `--ink-primary` with 2px offset. Visible in both themes. Never disabled.

### Keyboard

- All Radix primitives keep their default keyboard semantics (Tab/Shift-Tab, Enter/Space, Esc, arrow keys in menus/tabs).
- Strip list: ↑/↓ moves selection, Enter opens corkboard, Space toggles selection.
- `Cmd-K` / `Ctrl-K` / `/` focuses search.
- `Esc` priority order: closes corkboard → dismisses search → dismisses overlays.
- Sidebar tabs are arrow-navigable when focused.
- No interaction is mouse-only. Nothing depends on hover.

### Screen reader

- Doodle icons paired with text label: `aria-hidden="true"`. Standalone icon buttons: explicit `aria-label`.
- Tape, staples, paperclips, torn edges, paper grain, wood grain are all decorative — `aria-hidden`.
- Brand SVG has `<title>Sketchbook</title>` and `role="img"`.
- Strips announce: project title, BPM, key, tracks, length, color tag name (Ableton color name like "Magenta", "Lime"), tag list. Hold method (washi/staple) is decorative.
- Proposal cards announce verb + target + diff summary; approve/reject have explicit labels.
- Live region announces toast content and proposal-pile changes.

### Reduced motion

Already specified in §6. Honored in full, no exceptions.

### Reduced transparency

`prefers-reduced-transparency`: paper-grain raster overlay opacity → 0; tinted overlays in dark mode become flat colors instead of `rgba`.

### Forced colors / Windows high-contrast

`forced-colors: active`: drops paper/wood textures; drops Ableton color tagging on strip background (color tag becomes a small swatch + text label instead); uses system colors for borders and text. Metaphor degrades gracefully into a clean accessible list. Tested as a real fallback, not an afterthought.

### Zoom

- Layout works at 200% browser zoom without horizontal scroll.
- Strip layout reflows: at narrow widths the metadata cluster wraps under the title; corkboard panel becomes a full-width modal below ~900px (rule, not a v0.1 use case).

### Animation safety

No flashing >3 Hz anywhere.

---

## 9. AI presence

The AI works primarily through CLI/MCP. Web UI is the user's *approval & browsing* surface. AI presence is light-touch and limited to three places.

### 1. Proposals pile (`/proposals`)

First-class screen. Renders `data/proposals/*.json` as a torn-page pile (`<TornPagePile>` of `<ProposalCard>`s). Each card: action verb (`Rename`, `Move`, `SetColorTag`, `SetTags`, `Archive`), target project(s), human-readable diff, source proposal id. Approve/reject buttons. Bulk approve-all and reject-all at the top. Approved proposals execute through the existing `Action` pipeline and are journaled. **The pile is the only place the user *acts on* AI work.**

### 2. Claude's notebook (`/n/claude`)

A kraft-paper composition book on the shelf. Renders the journal — every action ever taken, who proposed it (CLI session id, MCP, or the user directly), when, and what the undo path is. Activity log, **not a chat**. Sortable by date, filterable by action type and source. The user can step backward through the journal and undo any single action or any contiguous range. Read-only view of journal data.

### 3. Margin sticky notes (inline, contextual)

When a project has unaddressed AI suggestions specific to it (e.g., "candidate rename: 2018-08-Kyoto-Trap-sketch"), a small rotated paper square is paperclipped to the strip's right edge in the catalog and to the corkboard overview tab. Clicking pulls just that suggestion into the proposals pile. **Sticky notes are a summary count, not a chat thread** — at most one per project, replaceable as new suggestions supersede old.

### Out of scope for v0.1

- No chat panel.
- No "ask Claude" input field.
- No streaming output displayed in the UI.
- No mascot, no avatar.
- No live "Claude is thinking" indicators.

### Identity rules

- Claude's notebook spine and sticky notes use `--surface-kraft` (kraft paper), distinguishing them from user notebooks (lined paper). Only visual signal.
- AI-authored text in the UI (proposal verbs, sticky-note copy) renders in `font-display` so it reads as a "note left for you," not a system message.
- Action source (`user` / `claude-cli` / `claude-mcp`) is shown in `font-mono` on each journal entry — small label, not a heavy badge.

---

## 10. Tech wiring

The desktop app is Compose Multiplatform (JVM target via `:app-desktop`). The aesthetic above is implemented in `shared/ui-shared/`:

- `theme/Colors.kt` — semantic + Ableton-14 tokens.
- `theme/Spacing.kt` — spacing scale.
- `components/PaperPage.kt`, `RuledPaper.kt`, `Surface.kt` — paper/desk surfaces.
- `components/SongStrip.kt`, `RowItem.kt` — load-bearing list rows.
- `components/NotebookSidebar.kt`, `PageHeader.kt`, `Button.kt`, `TextField.kt` — chrome.

Per-feature surfaces live in `shared/feature-*/`. Settings persistence uses `java.util.prefs.Preferences` (will move to OS keychain in v1.1). State-holders follow the canonical pattern in `docs/ai/CLAUDE.md` (sealed `Intent`, `data class State`, `state: StateFlow<State>`, `accept(intent)`).

The marketing landing page under `site/` mirrors a subset of these tokens for the download splash (see `site/index.html`).

---

## Appendix A — asset-generation prompts

The following prompts were authored alongside this design for use with Gemini Imagen / Nano Banana to generate the production rasters and reference art for the SVG sprite library.

### A1. `paper-grain.webp` — seamless tileable paper texture (production)

```
Seamless tileable texture of plain cream-colored notebook paper, top-down flat scan, soft warm off-white (#F5EFDF tone), subtle natural fiber grain, very faint horizontal lined-paper rules barely visible, no writing, no marks, no stains, no edges, no tears, perfectly uniform across the whole image so it tiles without visible seams. Photo-realistic paper scan quality, 512x512, no shadows, even neutral lighting, no logo, no text. Output a single seamlessly repeating square tile.
```

### A2. `wood-grain.webp` — seamless tileable walnut desk (production)

```
Seamless tileable texture of warm walnut wood desktop, top-down flat scan, rich medium-brown grain with natural variation (#6B4A2B to #4A301B tones), realistic wood pores and grain lines running horizontally, subtle but not exaggerated, no knots, no scratches, no objects, no shadows, evenly lit, perfectly uniform so it tiles seamlessly. Photographic wood-grain quality, 1024x1024, no logo, no text, no edges. Output a single seamlessly repeating square tile.
```

### A3. Brand wordmark — "Sketchbook" cutout letters (production)

```
The single word "Sketchbook" rendered as one horizontal line on a transparent background. Each letter is cut from rough-edged construction paper with visible torn/scissor-cut edges and faint paper texture, slightly tilted at varied angles to feel hand-placed. Letter colors EXACTLY in this order: S=red, k=blue, e=yellow, t=green, c=purple, h=red, b=orange, o=blue, o=yellow, k=green. Cheerful saturated primary colors. Mixed case as written (capital S, rest lowercase). Childlike-creativity feel, NOT childish. No background, no shadow, no extra elements. Flat top-down view, even lighting. PNG with transparent background. 2400x800.
```

After generation, vectorize letter edges (Inkscape/Illustrator) and save as the `brand-sketchbook` SVG symbol.

### A4. Doodle icon library — reference sheet to trace into SVG sprites

```
A single sheet of plain off-white paper, top-down view, showing a grid of 24 small hand-drawn pencil/graphite icons spaced evenly on a 6x4 grid, each ~80px square, all in the same loose hand-drawn style, all the same medium-grey graphite color (#3A3A3A), all lineweight matched (~2px), all line-art only (no fill, no shading). The icons in order: (1) metronome, (2) piano keyboard 5-key view, (3) microphone, (4) cassette tape, (5) drum kit, (6) drumstick, (7) stick-figure dancer, (8) star, (9) crescent moon, (10) cloud, (11) rainstorm cloud with drops, (12) magnifying glass, (13) paper airplane, (14) pencil stub, (15) paperclip, (16) scissors, (17) plus sign, (18) X mark, (19) checkmark, (20) chevron right, (21) house, (22) bookmark, (23) folder, (24) cassette/tape spool. Childlike-creativity hand-drawn feel, slightly imperfect lines, not vector-perfect. Pure white background, no other content, no labels. 2400x1600.
```

Trace each icon into an SVG `<symbol>` for the sprite sheet. Use `currentColor` on strokes so theme tokens drive doodle color.

### A5. Field-icon set — strip metadata icons

```
Five small hand-drawn pencil/graphite icons on plain off-white paper, top-down, arranged in a horizontal row, evenly spaced, all matched lineweight ~2px, medium-grey graphite (#3A3A3A), line-art only. Left to right: (1) tiny stopwatch (BPM), (2) tiny piano keyboard 3-keys (KEY), (3) tiny stack of three horizontal lines (TRACKS), (4) tiny hourglass (LENGTH), (5) tiny "4/4" handwritten fraction (TIME-SIG). Each icon ~64px square. Hand-drawn imperfect feel, pure white background, no labels, no background pattern. 1600x320.
```

### A6. Tape variants — washi tape strips

```
Six horizontal strips of decorative washi tape on a transparent background, arranged stacked vertically with even spacing, each strip 600x80px, slightly translucent so the background shows through softly, slightly torn deckle edges on the short ends, soft drop-shadow underneath. Each strip a different pattern: (1) solid pastel mint green, (2) red and white horizontal stripes, (3) pastel pink with small white polka dots, (4) yellow with thin black diagonal stripes, (5) light blue with tiny white stars, (6) plain kraft brown with subtle paper texture. Top-down view, photo-realistic tape feel, transparent PNG, no extra elements, no labels. 600x600 final canvas.
```

### A7. Hold-method assets — staples, paperclips, pushpins

```
A single sheet on transparent background showing, in three rows:
Row 1 — Two metallic staples in chrome silver, top-down view, slightly rotated at different angles, 80x20px each.
Row 2 — One brass-colored paperclip, classic loop shape, slight rotation, 100x40px.
Row 3 — Six round-head pushpins in different colors viewed top-down (not from the side): green, blue, orange, purple, red, yellow. Each pushpin 60x60px with small specular highlight on the dome.
Photo-realistic metal/plastic feel, transparent PNG, soft drop-shadows under each item, no labels, no background. 800x600.
```

### A8. Torn edge masks — for strip and proposal-card edges

```
Four horizontal strips of plain white paper on a transparent background, stacked vertically, each 800x60px. Each strip has a different ROUGH TORN edge style on the TOP edge only (bottom is flat): (1) gentle deckle tear, (2) jagged irregular tear with small fibers showing, (3) clean scissor-cut wavy line, (4) sharp irregular zigzag tear. White paper, transparent background outside the paper, no shadows, no labels, top-down flat view. 800x300.
```

Trace the tear edges into SVG `<clipPath>` elements for the `torn-edge-*` masks.

### Asset-pipeline workflow

- A1, A2, A3 are the only true production rasters. A3 should be vectorized.
- A4–A8 are reference art; trace and clean into vector SVG so they live in `sprites.svg`, stay crisp at any zoom, and are theme-token colorable via `currentColor`.
- A6 (tape) and A7 (pushpins) may stay as raster sprites at moderate quality if vectorizing isn't worth the time.
