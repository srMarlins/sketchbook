# Sketchbook UI

The web frontend for the audio catalog. A tactile lined-paper notebook on a wooden desk; colored construction-paper "song strips" pinned, taped, and stapled to the page; a corkboard for project detail; a torn-page pile for AI proposals.

Source-of-truth design spec: [`../docs/design-language.md`](../docs/design-language.md).
Implementation plan: [`../docs/plans/2026-05-04-sketchbook-ui.md`](../docs/plans/2026-05-04-sketchbook-ui.md).

## Quick start

```pwsh
cd web
npm install
npm run dev    # http://localhost:5173/
```

## Routes

| Path | Description |
| --- | --- |
| `/` | Home — wooden shelf with notebook spines (Inbox, per-year, per-tag, Archive, Claude). |
| `/n/$notebookId` | A notebook page — virtualized list of `<SongStrip>` with margin sticky notes. |
| `/proposals` | Torn-page pile of pending AI proposals; bulk approve/reject. |
| `/n/claude` | Kraft-paper notebook of journal entries (Claude's activity log). |
| `/_dev` | Component viewer — every component in light + dark + reduced-motion. |

## Scripts

| Script | What it runs |
| --- | --- |
| `npm run dev` | Vite dev server on `http://localhost:5173/`. |
| `npm run build` | Type-check + production build to `dist/`. |
| `npm run preview` | Serve the production build. |
| `npm test` | Vitest unit + RTL component tests. |
| `npm run test:e2e` | Playwright E2E specs (`browse-and-open`, `approve-proposal`, `axe`, `reduced-motion`). |
| `npm run typecheck` | Strict TS check (`noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`). |

## Architecture

### File layout

```
src/
  app/          App shell + router + TanStack Query bindings + notebook derivation
  components/
    primitives/ Sprite (typed PNG-keyed icon)
    inputs/     Button, TextInput, SearchBar
    data/       SongStrip, NavStrip, FilterChip, MarginStickyNote, ProposalCard
    feedback/   Toast, EmptyState, LoadingState, ErrorState
    surface/    Desk, Sidebar, BrandingHeader, NotebookPage, NotebookSpine,
                Shelf, CorkboardPanel, TornPagePile
    corkboard/  Tab content for the project corkboard (Overview/Tracks/Samples/Plugins/History)
  dev/          /_dev component viewer (DevShell, DevControls, registry)
  hooks/        useTheme, useKeyboard
  lib/          api, types, seed (FNV-1a + mulberry32)
  mocks/        Fixture JSON + in-memory mutation handlers
  routes/       index, _dev, proposals, notebook, claude-notebook
  theme/        tokens.css (CSS custom properties), contrast-table.ts (WCAG AA)
```

### Theming

- All colors live as CSS custom properties on `:root`/`[data-theme="dark"]`. Tailwind is configured (in `tailwind.config.ts`) so utility classes like `bg-surface-page` resolve to `var(--surface-page)`.
- Theme is a single `data-theme` attribute on `<html>` driven by [`useTheme`](./src/hooks/useTheme.ts). Hydrates from `localStorage`, falls back to `prefers-color-scheme`.
- The Ableton-14 strip palette and the per-strip ink contrast table live in [`theme/contrast-table.ts`](./src/theme/contrast-table.ts). All 14 pairings are pre-verified against WCAG-AA 4.5:1 by [`contrast-table.test.ts`](./src/theme/contrast-table.test.ts).

### Data flow (mock vs. live)

[`lib/api.ts`](./src/lib/api.ts) is the single API client. When `VITE_USE_MOCKS=true` (the default during dev — see `.env.development`), every call returns from [`src/mocks/handlers.ts`](./src/mocks/handlers.ts), which serves the fixture JSON in `src/mocks/`.

When the FastAPI backend lands, flip `VITE_USE_MOCKS=false` and provide a base URL — the same calls switch to `fetch()` against `/api/...`.

Server state caching is TanStack Query; queries are declared once in [`app/queries.ts`](./src/app/queries.ts).

### Determinism

Visual jitter (per-strip rotation, hold-method choice, sticky-note color, proposal-card tilt) is seeded by content id via FNV-1a + mulberry32 in [`lib/seed.ts`](./src/lib/seed.ts), so layouts are stable across re-renders and runs.

## Component viewer (`/_dev`)

Every component is registered in [`src/dev/registry.tsx`](./src/dev/registry.tsx). The viewer offers light/dark/compare and a reduced-motion toggle. To add a component:

```ts
import { MyThing } from '../components/data/MyThing';

registry.push({
  id: 'my-thing',
  group: 'data',
  label: 'MyThing',
  controls: { /* optional knobs */ },
  render: () => <MyThing ... />,
});
```

Axe-core runs automatically in dev mode and logs violations to the console.

## Assets

Images, fonts, and textures are committed (no remote pipeline at runtime). Sources documented in `tools/asset-pipeline/README.md`.

| Path | What it holds |
| --- | --- |
| `public/fonts/` | Self-hosted Permanent Marker, Space Mono, Inter Variable. License: `LICENSE.md` in that folder. |
| `public/textures/` | Paper-grain and wood-grain WebP rasters consumed by `Desk` / `NotebookPage` / `Shelf`. |
| `public/raw/icons/doodles/` | Hand-drawn doodle PNGs referenced by `<Sprite name="..." />`. |
| `public/raw/icons/field/` | Field-icon sprites (BPM, key, length, time-sig, tracks). |
| `public/brand/sketchbook.png` | The brand wordmark — single image, never theme-swapped. |

## Performance budgets (DoD)

| Budget | Cap | Current |
| --- | --- | --- |
| Cold-load JS (gz) | ≤ 300 KB | ~171 KB |
| Self-hosted fonts | ≤ 180 KB | 111 KB |
| Sprites + textures (cold) | ≤ 320 KB | wood-grain 110 KB + paper-grain 3 KB + brand 427 KB ⚠ |
| Total cold load | ≤ 900 KB | ~760 KB |

⚠ Brand image (`public/brand/sketchbook.png`, 427 KB) is the largest single cold-load asset. Consider compressing or replacing with the inline-SVG version envisaged in `docs/design-language.md` §4.

## Accessibility

- Every interactive component has a Vitest behavior test asserting click handlers, keyboard, and aria attributes.
- Strip-on-text contrast is verified at build time against WCAG AA.
- The reduced-motion media query is honored globally via [`tokens.css`](./src/theme/tokens.css). Verified by `e2e/reduced-motion.spec.ts`.
- The component viewer is axe-clean in light + dark (`e2e/axe.spec.ts`).
