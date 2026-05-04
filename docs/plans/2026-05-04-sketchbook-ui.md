# Sketchbook UI Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Scaffold the Sketchbook React frontend, build every component defined in `docs/design-language.md` against mock data, and wire up all four top-level pages, fronted by a `/_dev` component viewer that shows every component in every state.

**Architecture:** A single Vite + React + TypeScript app under `web/`. All theming flows through CSS custom properties on `:root` and `[data-theme="dark"]`; Tailwind classes resolve via `var(--…)`. State is local Zustand for UI. Server state is mocked via TanStack Query against fixture JSON files in `web/src/mocks/` — backend wiring is deferred to the parallel catalog plan. Components live in `web/src/components/{surface,data,inputs,feedback,primitives}/`. The `/_dev` route renders every component in light + dark with `prefers-reduced-motion` toggle, used for both manual review and visual regression. Pages (Home, Notebook, Proposals, Claude) consume the same mock fixtures.

**Tech Stack:**
- Build: Vite 5, React 18, TypeScript (strict), Tailwind CSS 3, PostCSS
- Routing: TanStack Router
- Server state (mocked for now): TanStack Query
- Client state: Zustand
- Headless primitives: Radix UI
- Virtualized table: TanStack Table v8 + TanStack Virtual
- Motion (carve-outs only): Framer Motion
- Testing: Vitest, React Testing Library, Playwright (light), `@axe-core/react`
- Asset pipeline: already done — assets in `web/public/{textures,brand,raw}` per `docs/design-language.md` §7

---

## Pre-flight

**Spec source of truth:** `docs/design-language.md`. Cite section numbers when implementing tokens, motion, components, etc.

**Out of scope for this plan (handled by parallel catalog plan):**
- FastAPI backend, scanner, parser, SQLite catalog
- `Action` pipeline, journal, proposals execution
- MCP server
- The CLI

**Mock data fixtures live at:** `web/src/mocks/` — committed JSON the UI reads through TanStack Query so the frontend is buildable end-to-end without the backend running.

**Conventions:**
- Every component has a `*.test.tsx` next to it (Vitest + RTL) for behavior.
- Every component has an entry in `web/src/dev/registry.ts` so it appears in `/_dev`.
- Tailwind classes only — no inline styles unless dynamic (e.g., per-strip Ableton color).
- All copy strings live next to the component; no i18n in v0.1.
- One commit per task. Pre-commit hooks (lint, typecheck, test) must pass.

---

## Phase 0 — Project scaffold

### Task 0.1: Initialize Vite + React + TS in `web/`

**Files:**
- Create: `web/package.json`
- Create: `web/tsconfig.json`
- Create: `web/tsconfig.node.json`
- Create: `web/vite.config.ts`
- Create: `web/index.html`
- Create: `web/src/main.tsx`
- Create: `web/src/app/App.tsx`
- Create: `web/.gitignore`

**Step 1: From `web/` run scaffold**

```pwsh
npm create vite@latest . -- --template react-ts
```

**Step 2: Replace `package.json` scripts** with `dev`, `build`, `preview`, `test`, `test:watch`, `typecheck`, `lint`.

**Step 3: Pin TS strict mode** in `tsconfig.json`: `"strict": true`, `"noUncheckedIndexedAccess": true`, `"exactOptionalPropertyTypes": true`.

**Step 4: Verify dev server**

Run: `npm run dev`
Expected: Vite serves `http://localhost:5173/` rendering "Vite + React".

**Step 5: Commit**

```pwsh
git add web/
git commit -m "feat(web): scaffold vite+react+ts"
```

### Task 0.2: Add Tailwind, PostCSS, theme tokens stub

**Files:**
- Create: `web/postcss.config.js`
- Create: `web/tailwind.config.ts`
- Create: `web/src/theme/tokens.css`
- Create: `web/src/theme/index.ts`
- Modify: `web/src/main.tsx` (import `tokens.css`)

**Step 1: Install**

```pwsh
cd web; npm i -D tailwindcss postcss autoprefixer; npx tailwindcss init -p
```

**Step 2: Configure Tailwind** — `tailwind.config.ts` extends colors from CSS vars per `docs/design-language.md` §3:

```ts
import type { Config } from 'tailwindcss';
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // Surfaces
        'surface-desk': 'var(--surface-desk)',
        'surface-page': 'var(--surface-page)',
        'surface-strip': 'var(--surface-strip-base)',
        'surface-corkboard': 'var(--surface-corkboard)',
        'surface-panel': 'var(--surface-panel)',
        'surface-overlay': 'var(--surface-overlay)',
        'surface-kraft': 'var(--surface-kraft)',
        // Ink
        'ink-primary': 'var(--ink-primary)',
        'ink-secondary': 'var(--ink-secondary)',
        'ink-muted': 'var(--ink-muted)',
        // Accents
        'accent-action': 'var(--accent-action)',
        'accent-secondary': 'var(--accent-secondary)',
        // Ableton 14 (theme-invariant)
        ...Object.fromEntries(Array.from({length: 14}, (_, i) => [`als-${i + 1}`, `var(--als-${i + 1})`])),
      },
      fontFamily: {
        display: 'var(--font-display)',
        mono: 'var(--font-mono)',
        sans: 'var(--font-sans)',
      },
      zIndex: {
        desk: '0', page: '10', strip: '20', attach: '30', panel: '40', overlay: '50', toast: '60',
      },
    },
  },
  plugins: [],
} satisfies Config;
```

**Step 3: Write `tokens.css`** — full token set per design doc §3 + §4 + §6:

```css
:root {
  /* Primitives — Ableton 14 (truthful, theme-invariant) */
  --als-1: #ff6f6f; --als-2: #ff9933; --als-3: #ffcc33; --als-4: #ffff66;
  --als-5: #c2ff66; --als-6: #66cc66; --als-7: #66ccff; --als-8: #6699ff;
  --als-9: #9966ff; --als-10: #cc66cc; --als-11: #ff66cc; --als-12: #cc9966;
  --als-13: #999999; --als-14: #d8d8d8;
  /* (placeholder values — replace with the real Ableton 14 palette in Task 0.4) */

  /* Light theme — semantic */
  --surface-desk: #6b4a2b;
  --surface-page: #f5efdf;
  --surface-strip-base: #ffffff;
  --surface-corkboard: #c4a373;
  --surface-panel: #f5efdf;
  --surface-overlay: rgba(40, 28, 18, 0.45);
  --surface-kraft: #c8a472;
  --ink-primary: #2a2422;
  --ink-secondary: #3a3430;
  --ink-muted: #6b605a;
  --ink-on-strip-light: #1a1614;
  --ink-on-strip-dark: #f8f4ec;
  --rule-line: rgba(60, 50, 40, 0.16);
  --shadow-page: 0 12px 40px rgba(40, 28, 18, 0.28);
  --shadow-lift: 0 6px 14px rgba(40, 28, 18, 0.18);
  --shadow-pin: inset 0 1px 0 rgba(0,0,0,0.18);
  --tint-overlay: transparent;
  --accent-action: #d63a3a;
  --accent-secondary: #9a9692;
  --pin-green: #4caf50; --pin-blue: #3a7bd5; --pin-orange: #f29128;
  --pin-purple: #8a4fbf; --pin-red: #d63a3a; --pin-yellow: #f0c419;

  --font-display: 'Permanent Marker', 'Gloria Hallelujah', cursive;
  --font-mono: 'Space Mono', ui-monospace, monospace;
  --font-sans: 'Inter', system-ui, sans-serif;

  --ease-paper: cubic-bezier(0.2, 0.8, 0.2, 1);
  --ease-settle: cubic-bezier(0.4, 0, 0.6, 1);
  --dur-fast: 120ms; --dur-base: 200ms; --dur-slow: 400ms;
}

[data-theme="dark"] {
  --surface-page: #f5efdf;          /* same paper raster */
  --surface-desk: #3d2814;          /* deeper walnut */
  --tint-overlay: rgba(40, 28, 18, 0.55);
  --ink-primary: #2a2422;
  --rule-line: rgba(60, 50, 40, 0.22);
  /* Pins desaturate ~15% (replace with the actual desaturated values) */
}

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { transition-duration: 0ms !important; animation-duration: 0ms !important; }
}
```

**Step 4: Add `@tailwind` directives** to `src/index.css` (or `tokens.css` head).

**Step 5: Verify** — add a `<div className="bg-surface-page text-ink-primary">test</div>` in `App.tsx`, run dev, confirm cream background dark text.

**Step 6: Commit**

```bash
git add web/postcss.config.js web/tailwind.config.ts web/src/theme web/src/main.tsx web/src/app/App.tsx
git commit -m "feat(web): add tailwind + theme tokens"
```

### Task 0.3: Self-host fonts (display, mono, sans)

**Files:**
- Create: `web/public/fonts/permanent-marker.woff2`
- Create: `web/public/fonts/space-mono-{regular,bold}.woff2`
- Create: `web/public/fonts/inter-variable.woff2`
- Modify: `web/src/theme/tokens.css` (add `@font-face` blocks; preload metas)
- Modify: `web/index.html` (`<link rel="preload" as="font">` for display + mono)

**Step 1:** Download woff2s from google-webfonts-helper or fontsource. Place under `web/public/fonts/`. License files alongside.

**Step 2:** Add `@font-face` blocks; use `font-display: swap` and `size-adjust` metrics override so `font-display` swap from Inter is invisible.

**Step 3: Verify total payload ≤180 KB.** Run:

```pwsh
Get-ChildItem web/public/fonts | Measure-Object -Sum Length
```

Expected: total < 180 * 1024 bytes.

**Step 4: Commit.**

### Task 0.4: Lock in real Ableton 14 palette

**Files:**
- Modify: `web/src/theme/tokens.css` (replace placeholder `--als-*` values)
- Create: `web/src/theme/contrast-table.ts`

**Step 1:** Capture the 14 hex values from Ableton Live (verify by opening the test catalog `Z:\User\audio\Projects` in Live and reading the color picker, OR reference the `core` package's color constants if the parallel agent has landed them; add a `// SOURCE:` comment).

**Step 2:** Compute text-on-strip color per palette entry. Add a contrast-table module:

```ts
// Pre-computed at design-time, not runtime. WCAG AA pass against 4.5:1.
export const TEXT_ON_ALS: Record<number, 'light' | 'dark'> = {
  1: 'dark', 2: 'dark', 3: 'dark', 4: 'dark', 5: 'dark',
  6: 'light', 7: 'dark', 8: 'light', 9: 'light', 10: 'light',
  11: 'dark', 12: 'light', 13: 'light', 14: 'dark',
};
```

**Step 3:** Write a unit test that asserts every `als-N` × `TEXT_ON_ALS[N]` combination passes 4.5:1 (use `wcag-contrast` lib or hand-rolled `relativeLuminance`).

**Step 4: Commit.**

### Task 0.5: Install Radix, TanStack, Zustand, Framer Motion, Vitest, RTL

**Files:**
- Modify: `web/package.json`

**Step 1:**

```pwsh
cd web
npm i @radix-ui/react-dialog @radix-ui/react-popover @radix-ui/react-tooltip @radix-ui/react-dropdown-menu @radix-ui/react-tabs @radix-ui/react-scroll-area @radix-ui/react-toast @radix-ui/react-switch @radix-ui/react-checkbox @radix-ui/react-label @radix-ui/react-separator @radix-ui/react-visually-hidden @radix-ui/react-accessible-icon
npm i @tanstack/react-router @tanstack/react-query @tanstack/react-table @tanstack/react-virtual zustand framer-motion clsx
npm i -D vitest @testing-library/react @testing-library/jest-dom @testing-library/user-event jsdom @vitest/ui @axe-core/react
```

**Step 2:** Wire `vite.config.ts` to use Vitest with `environment: 'jsdom'` and `setupFiles: ['./src/test-setup.ts']`.

**Step 3:** Create `src/test-setup.ts` importing `@testing-library/jest-dom`.

**Step 4:** Add a smoke test `src/app/App.test.tsx` that renders `<App />` and asserts a known element. Run `npm test` — expect PASS.

**Step 5: Commit.**

### Task 0.6: Add TanStack Router with two stub routes

**Files:**
- Create: `web/src/app/router.tsx`
- Create: `web/src/routes/index.tsx` (Home stub)
- Create: `web/src/routes/_dev.tsx` (Component Viewer stub)
- Modify: `web/src/app/App.tsx`
- Modify: `web/src/main.tsx`

**Step 1:** Configure router with file-based routes (or code-based — code-based is simpler for v0.1).

**Step 2:** `routes/index.tsx` returns `<div>home stub</div>`. `routes/_dev.tsx` returns `<div>dev stub</div>`.

**Step 3:** Run dev, navigate `/` and `/_dev`, both render.

**Step 4: Commit.**

---

## Phase 1 — Foundation utilities

### Task 1.1: Theme provider + `useTheme` hook

**Files:**
- Create: `web/src/hooks/useTheme.ts`
- Create: `web/src/hooks/useTheme.test.ts`
- Modify: `web/src/app/App.tsx` (wrap children in theme provider — actually a no-op in this design; just attach `data-theme` on `<html>`)

**Step 1: Write the failing test** for `useTheme`:

```ts
import { renderHook, act } from '@testing-library/react';
import { useTheme } from './useTheme';

test('toggles between light and dark, persists to localStorage', () => {
  const { result } = renderHook(() => useTheme());
  expect(result.current.theme).toBe('light');
  act(() => result.current.setTheme('dark'));
  expect(result.current.theme).toBe('dark');
  expect(document.documentElement.dataset.theme).toBe('dark');
  expect(localStorage.getItem('sketchbook.theme')).toBe('dark');
});
```

**Step 2: Run** — fails (hook not defined).

**Step 3: Implement** — Zustand store with `theme: 'light' | 'dark'`, hydrated from `localStorage.getItem('sketchbook.theme')` falling back to `matchMedia('(prefers-color-scheme: dark)').matches`. On set, write the attribute and localStorage.

**Step 4: Run** — PASS.

**Step 5: Commit.**

### Task 1.2: `useKeyboard` hook (Cmd-K, /, Esc)

**Files:**
- Create: `web/src/hooks/useKeyboard.ts`
- Create: `web/src/hooks/useKeyboard.test.ts`

**Step 1: Failing test** — fires keyboard events, asserts handlers called.

**Step 2:** Implement with `useEffect` + `keydown` listener; debounced `Esc` priority queue (a registry of handlers in priority order).

**Step 3: Test passes. Commit.**

### Task 1.3: Deterministic seed util

**Files:**
- Create: `web/src/lib/seed.ts`
- Create: `web/src/lib/seed.test.ts`

**Step 1: Failing test** — `seedFromString('proj-123')` returns the same int across calls; `seedPick(['a','b','c'], seed)` returns a deterministic element.

**Step 2:** Implement FNV-1a hash + small mulberry32 PRNG.

**Step 3: Commit.**

### Task 1.4: API client stub + mock fixtures

**Files:**
- Create: `web/src/lib/api.ts`
- Create: `web/src/mocks/projects.json` (50 fixture rows)
- Create: `web/src/mocks/proposals.json` (8 fixture proposals)
- Create: `web/src/mocks/journal.json` (20 fixture journal entries)
- Create: `web/src/mocks/handlers.ts` (in-memory mock that `api.ts` calls when `import.meta.env.VITE_USE_MOCKS === 'true'`)
- Create: `web/.env.development` with `VITE_USE_MOCKS=true`

**Step 1:** Write the fixture files. Each project has `{id, path, name, parent_dir, tempo, time_sig, key, track_count, audio_track_count, midi_track_count, length_seconds, live_version, last_modified, last_scanned, file_hash, is_archived, color_tag, notes}` per catalog design §4.

**Step 2:** `api.ts` exports `listProjects`, `getProject`, `listProposals`, `approveProposal`, `rejectProposal`, `listJournal` — each branches on `VITE_USE_MOCKS` to return fixtures or call FastAPI.

**Step 3: Smoke test** that `listProjects()` returns 50 rows in mock mode.

**Step 4: Commit.**

### Task 1.5: Sprite component (`<Sprite name="metronome" />`)

**Files:**
- Create: `web/src/components/primitives/Sprite.tsx`
- Create: `web/src/components/primitives/Sprite.test.tsx`
- Create: `web/src/components/primitives/sprite-names.ts` (typed union of all sprite ids)

**Step 1: Test** — `<Sprite name="metronome" />` renders an `<img>` referencing `web/public/raw/icons/doodles/metronome.png` (interim, until vectorization). Theme-color via CSS filter or `currentColor`-keyed SVG when available.

**Step 2:** Implement as a component that maps `name` → asset path. `aria-hidden="true"` by default; when `label` prop given, use `<AccessibleIcon>`.

**Step 3:** Add storybook entry to `dev/registry.ts` (Phase 2 will create the file).

**Step 4: Commit.**

---

## Phase 2 — Component viewer (`/_dev`)

### Task 2.1: Component registry

**Files:**
- Create: `web/src/dev/registry.ts`
- Create: `web/src/dev/types.ts`

**Step 1:** Define `DevEntry = { id, group, label, render: (controls) => ReactNode, controls?: { ... } }`.

**Step 2:** Export `registry: DevEntry[]` — start with one entry: `Sprite` example.

**Step 3:** Commit.

### Task 2.2: `/_dev` page renders all registry entries

**Files:**
- Modify: `web/src/routes/_dev.tsx`
- Create: `web/src/dev/DevShell.tsx`
- Create: `web/src/dev/DevControls.tsx`

**Step 1:** `DevShell` has: theme toggle (light/dark), reduced-motion toggle, viewport-width sim, registry sidebar, render area.

**Step 2:** Each entry renders against light + dark backgrounds side-by-side (when toggle = "compare").

**Step 3:** Reduced-motion toggle injects a CSS rule `* { animation: none !important; transition: none !important; }` when ON.

**Step 4:** Visit `/_dev`, confirm Sprite entry renders in both themes.

**Step 5: Commit.**

### Task 2.3: Axe integration in dev

**Files:**
- Modify: `web/src/main.tsx` (load `@axe-core/react` only in dev)

**Step 1:** Conditional import. Configure to log to console.

**Step 2:** Visit `/_dev` in dev, see no axe violations on the shell.

**Step 3: Commit.**

---

## Phase 3 — Components

> **Pattern for each task:** (a) write the test that exercises observable behavior or rendering, (b) implement the minimal component, (c) add to `dev/registry.ts`, (d) verify visually at `/_dev` in both themes, (e) commit. For components that are mostly visual (no logic), the "test" is a smoke render assertion + axe check.

### Task 3.1: `Button` (primary / secondary / ghost)

**Files:** `web/src/components/inputs/Button.tsx` + `.test.tsx`

**Behavior under test:** click handler fires; `disabled` prevents click; rough-edge SVG mask renders; text uses `font-display`.

### Task 3.2: `FilterChip` (doodled chip)

**Files:** `web/src/components/data/FilterChip.tsx` + `.test.tsx`

**Behavior:** dismiss-X click fires `onDismiss`; renders handwritten label + sprite icon; supports value badge.

### Task 3.3: `SongStrip` (the load-bearing component)

**Files:** `web/src/components/data/SongStrip.tsx` + `.test.tsx`

**Behavior:** renders `title` (font-mono), BPM/key/tracks/length with field-icon sprites, strip background = `var(--als-N)`, text-on-strip color from `contrast-table`. Hold method (washi/staple) is selected deterministically by `seedPick(...,project.id)`. Hover applies lift CSS class. Click fires `onOpen(projectId)`.

**Test cases:**
- All 14 als colors render with correct text-on color.
- Same project id → same hold method across re-renders.
- Reduced motion: lift not applied.
- Click handler fires.

### Task 3.4: `NavStrip` (sidebar item)

**Files:** `web/src/components/data/NavStrip.tsx` + `.test.tsx`

### Task 3.5: `SearchBar`

**Files:** `web/src/components/inputs/SearchBar.tsx` + `.test.tsx`

**Behavior:** typed value lifts via Zustand. `Cmd-K` / `Ctrl-K` / `/` focuses input. `Esc` clears query and blurs. Placeholder is handwritten.

### Task 3.6: `TextInput`

**Files:** `web/src/components/inputs/TextInput.tsx` + `.test.tsx`

### Task 3.7: `Toast`, `EmptyState`, `LoadingState`, `ErrorState`

**Files:** `web/src/components/feedback/{Toast,EmptyState,LoadingState,ErrorState}.tsx` + `.test.tsx`

**LoadingState specific test:** animation duration capped at 3 s; reduced-motion shows static doodle.

### Task 3.8: `MarginStickyNote`

**Files:** `web/src/components/data/MarginStickyNote.tsx` + `.test.tsx`

**Behavior:** rotated paper square + paperclip SVG; click fires `onOpenSuggestion`. `font-display` for the text. Rotation seeded from `note.id`.

### Task 3.9: `ProposalCard`

**Files:** `web/src/components/data/ProposalCard.tsx` + `.test.tsx`

**Behavior:** verb + target + diff preview; approve/reject buttons fire respective handlers; rotation jitter seeded from proposal id.

### Task 3.10: `Desk` (top-level chrome)

**Files:** `web/src/components/surface/Desk.tsx` + `.test.tsx`

**Behavior:** renders wood-grain background (raster + tint overlay); mounts `<Sidebar>`, `<BrandingHeader>`, `<SearchBar>` slots, and `<Outlet />`.

### Task 3.11: `Sidebar` (composes `<NavStrip>`)

**Files:** `web/src/components/surface/Sidebar.tsx` + `.test.tsx`

**Behavior:** renders 7 nav strips; active strip presses in; arrow keys navigate; Enter activates.

### Task 3.12: `BrandingHeader`

**Files:** `web/src/components/surface/BrandingHeader.tsx`

**Behavior:** renders `web/public/brand/sketchbook.png` with `role="img"` + alt "Sketchbook".

### Task 3.13: `NotebookPage`

**Files:** `web/src/components/surface/NotebookPage.tsx` + `.test.tsx`

**Behavior:** lined-paper background (paper-grain raster + horizontal rule lines via repeating-linear-gradient). Spiral binding pinned at `left: 0` via SVG sprite. Sticky header slot.

### Task 3.14: `Shelf` + `NotebookSpine`

**Files:** `web/src/components/surface/{Shelf,NotebookSpine}.tsx` + `.test.tsx`

**Behavior of `NotebookSpine`:** click fires `onOpen(notebookId)`; props `kind` ∈ `{lined, kraft, manila}` swap surface classes; `count` and `lastUpdated` rendered in `font-mono`.

### Task 3.15: `CorkboardPanel`

**Files:** `web/src/components/surface/CorkboardPanel.tsx` + `.test.tsx`

**Behavior:** slide-in via Framer Motion, `--dur-base` / `--ease-paper`. Esc closes. Tabs = paper-strip tabs down the left edge (Radix Tabs primitive). Content area renders the active tab's children.

**Test:**
- Esc closes (via Radix Dialog).
- Outside-click closes.
- Reduced-motion: instant slide.

### Task 3.16: `TornPagePile`

**Files:** `web/src/components/surface/TornPagePile.tsx` + `.test.tsx`

**Behavior:** stacks `<ProposalCard>` children with deterministic per-card rotation jitter (seeded from card id). New cards animate in via the §6 carve-out.

---

## Phase 4 — Pages

### Task 4.1: Home / Shelf (`/`)

**Files:** `web/src/routes/index.tsx`

Renders `<Shelf>` containing notebook spines for: Inbox (un-foldered projects), one per year, one per active tag, Archive, Claude. Click spine → navigate `/n/<notebook-id>`.

**Test:** mock fixtures contain projects across 3 years + 4 tags → shelf shows 3 year spines + 4 tag spines + Inbox + Archive + Claude.

### Task 4.2: Notebook view (`/n/$notebookId`)

**Files:** `web/src/routes/n/$notebookId.tsx`

Renders `<NotebookPage>` with virtualized `<SongStrip>` rows (TanStack Virtual). Sticky header shows notebook title, search bar, filter chips, sort, count. Search results temporarily replace strips. Esc restores. Click strip → opens `<CorkboardPanel>`.

**Tests:**
- Renders ≥1000 mock strips at 60fps (use `performance.now()` measurement).
- Strip click opens corkboard.
- Esc closes corkboard, then on a second Esc clears search.

### Task 4.3: Corkboard tabs (Overview / Tracks / Samples / Plugins / History)

**Files:** `web/src/routes/n/$notebookId/$projectId.tsx` (modal route mounted in panel)
- Create: `web/src/components/corkboard/{Overview,Tracks,Samples,Plugins,History}.tsx`

**Behavior of each tab:** renders the project's data from mock fixtures. Tracks/Samples/Plugins are simple tables. History reads journal entries for that project.

### Task 4.4: Proposals (`/proposals`)

**Files:** `web/src/routes/proposals.tsx`

Renders `<TornPagePile>` of all pending proposals. Bulk approve / reject at top. Approve → calls mock api, removes card with carve-out. Pile empty state = handwritten "Nothing pending — try `claude propose ...` in the CLI" with a doodle.

### Task 4.5: Claude's notebook (`/n/claude`)

**Files:** `web/src/routes/n/claude.tsx`

Same `<NotebookPage>` but kraft-paper surface. Renders journal entries in chronological order. Each entry: action verb + target + source (`user` / `claude-cli` / `claude-mcp`) + timestamp. Click entry → opens corkboard "History" tab for that project.

### Task 4.6: Margin sticky notes wired through

**Files:** modify `web/src/components/data/SongStrip.tsx`
- Create: `web/src/lib/suggestions.ts`

Per-project AI suggestion (mock fixture `web/src/mocks/suggestions.json`) renders as `<MarginStickyNote>` paperclipped to the strip. Click → adds the suggestion to the proposals pile.

---

## Phase 5 — End-to-end + accessibility verification

### Task 5.1: Playwright golden-path tests

**Files:**
- Create: `web/playwright.config.ts`
- Create: `web/e2e/browse-and-open.spec.ts`
- Create: `web/e2e/approve-proposal.spec.ts`

**Test 1:** From `/`, click year notebook → see strips → search "kyoto" → see filtered strips → click first strip → see corkboard → close → search cleared.

**Test 2:** From `/proposals`, see 8 cards → click approve on first → card animates out → fixture updated → count decremented.

### Task 5.2: Axe sweep on `/_dev`

**Files:** `web/e2e/axe.spec.ts`

Loads `/_dev`, runs axe across both themes, asserts zero violations.

### Task 5.3: Reduced-motion sweep

**Files:** `web/e2e/reduced-motion.spec.ts`

Loads `/_dev` with `prefers-reduced-motion`, asserts no `animation` or `transition` styles take effect (sample 5 components).

---

## Phase 6 — Documentation handoff

### Task 6.1: README

**Files:** `web/README.md`

Document: how to run dev, how `/_dev` works, where mocks live, how to switch to live API, where assets come from (link to `tools/asset-pipeline/README.md` and `docs/design-language.md`).

### Task 6.2: Update memory

Update auto-memory entry for the audio project to note that the UI scaffold is in place at `web/`, design lives in `docs/design-language.md`, and all components live in `web/src/components/{surface,data,inputs,feedback,primitives}`.

---

## Definition of done

- `npm run dev` serves the app at `localhost:5173`.
- `/_dev` shows every component in both themes.
- Home, Notebook, Proposals, Claude pages all render mock data.
- All Vitest unit tests pass.
- Two Playwright E2Es pass.
- Axe sweep is clean.
- Reduced-motion sweep confirms no animations leak through.
- Total cold-load JS ≤ 300 KB gz, fonts ≤ 180 KB, sprites/textures ≤ 320 KB, total ≤ 900 KB.
