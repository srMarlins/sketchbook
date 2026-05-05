# Audio Catalog UI

The web frontend for the audio catalog. A warm-pastel stationery surface,
dense project rows with Ableton color tags as small color pills, and a
right-side detail panel that fetches plugins/samples/journal on demand.

Source-of-truth design notes: [`../docs/design-language.md`](../docs/design-language.md).
Latest implementation plan: [`../docs/plans/2026-05-04-web-stationery.md`](../docs/plans/2026-05-04-web-stationery.md).

## Quick start

```pwsh
cd web
npm install

# real backend (assumes uvicorn audio_web.app:create_app --factory on :7878)
npm run dev

# offline UI work, mocked fixtures
npm run dev:mocks
```

The dev server proxies `/api/*` to `http://127.0.0.1:7878`. Flip with
`.env.development`.

## Routes

| Path | Description |
| --- | --- |
| `/` | Home — derived notebook grid (per-year + per-tag + Archive + Claude). |
| `/n/$notebookId` | A notebook page — TanStack-virtualized list of `<SongStrip>`. |
| `/proposals` | Translated proposal cards; bulk approve/reject. |
| `/n/claude` | Journal feed — translated batches with per-batch undo. |
| `/_dev` | Component viewer — every component in light + dark + reduced-motion. Lazy-loaded so it doesn't bloat the user bundle. |

## Scripts

| Script | What it runs |
| --- | --- |
| `npm run dev` | Vite dev server with FastAPI proxy. `VITE_USE_MOCKS=false` (default). |
| `npm run dev:mocks` | Vite dev server in mock mode (`--mode development.mocks`). |
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
    data/       SongStrip, NavStrip, FilterChip, ProposalCard, MarginStickyNote (dev-only)
    feedback/   Toast, EmptyState, LoadingState, ErrorState
    surface/    Desk, Sidebar, BrandingHeader, ThemeToggle, NotebookPage,
                NotebookSpine, Shelf, CorkboardPanel, TornPagePile
    corkboard/  Tab content for the project detail panel (Overview/Tracks/Samples/Plugins/History)
  dev/          /_dev component viewer (DevShell, DevControls, registry)
  hooks/        useTheme, useKeyboard
  lib/          api, types, proposal-translate, seed (FNV-1a + mulberry32)
  mocks/        projects.json + in-memory handlers (proposals/journal generated from projects)
  routes/       index, _dev, proposals, notebook, claude-notebook
  theme/        tokens.css (CSS custom properties), contrast-table.ts (WCAG AA helpers)
```

### Theming

- All colors live as CSS custom properties on `:root`/`[data-theme="dark"]`. Tailwind utility classes like `bg-surface-page` resolve to `var(--surface-page)`.
- Theme is a `data-theme` attribute on `<html>` driven by [`useTheme`](./src/hooks/useTheme.ts). Hydrates from `localStorage`, falls back to `prefers-color-scheme`.
- The header chrome includes a `<ThemeToggle />` icon button; the dev viewer also has light/dark/compare/reduced-motion toggles.
- The Ableton-14 strip palette is exposed as `--als-1`..`--als-14`. SongStrip maps backend `color_tag` (0..13) to `als-1..als-14`.

### Data flow (mock vs. live)

[`lib/api.ts`](./src/lib/api.ts) is the single API client. When `VITE_USE_MOCKS=true` (set in `.env.development.mocks` for `npm run dev:mocks`), every call returns from [`src/mocks/handlers.ts`](./src/mocks/handlers.ts), which serves projects from `projects.json` and generates Proposal/JournalBatch fixtures inline.

The default dev mode (`.env.development`) sets `VITE_USE_MOCKS=false` and the Vite proxy forwards `/api/*` to `http://127.0.0.1:7878` — start the FastAPI app there with:

```pwsh
uv run uvicorn audio_web.app:create_app --factory --host 127.0.0.1 --port 7878
```

Server state caching is TanStack Query; queries + mutations are declared in [`app/queries.ts`](./src/app/queries.ts) (`useProjects`, `useProject`, `useProposals`, `useJournal`, plus `useApproveProposal`/`useRejectProposal`/`useUndoBatch`/`useSubmitProposal`).

### Live indexer events

The backend publishes scanner/backfill/watcher events on `/api/events` (SSE). [`hooks/useIndexerEvents.ts`](./src/hooks/useIndexerEvents.ts) is the raw subscription; [`hooks/useIndexerCachePatcher.ts`](./src/hooks/useIndexerCachePatcher.ts) invalidates `['project', id]` and `['projects']` on every `scan_row` and writes findings counts directly into `['findings']`. The `<IndexerStatus />` chip in the page header surfaces live state (idle / scanning / backfilling / watcher_warning / disconnected). The first-launch splash (`<FirstLaunch />`) takes over when the catalog is empty and the scanner is active, auto-dismissing once 30+ rows are in the catalog.

### Proposal/journal translation

Backend ProposedAction is a discriminated union by `type` (`RenameProject`, `MoveProject`, `ArchiveProject`, `SetColorTag`, `SetTags`). The UI never reads the per-type args directly — it goes through [`lib/proposal-translate.ts`](./src/lib/proposal-translate.ts), which produces a uniform `{verb, label, before, after}` shape. Adding a new action type = adding one branch in `proposal-translate.ts` and TS narrowing surfaces every UI site that needs an update.

## Component viewer (`/_dev`)

Every component is registered in [`src/dev/registry.tsx`](./src/dev/registry.tsx). The viewer offers light/dark/compare and a reduced-motion toggle. The route is lazy-loaded (a separate ~9 KB gzip chunk) so it doesn't bloat the main bundle.

## Assets

Images and fonts are committed (no remote pipeline at runtime).

| Path | What it holds |
| --- | --- |
| `public/fonts/` | Self-hosted Inter Variable + Space Mono. License: `LICENSE.md` in that folder. |
| `public/raw/icons/doodles/` | Hand-drawn doodle PNGs referenced by `<Sprite name="..." />`. |
| `public/raw/icons/field/` | Field-icon sprites (BPM, length, time-sig, tracks). |

The page background is a CSS radial gradient on `body` — no texture rasters.

## Performance

| Metric | Current |
| --- | --- |
| Main bundle (gz) | ~165 KB |
| Lazy `_dev` chunk (gz) | ~9 KB |
| CSS (gz) | ~5 KB |
| Self-hosted fonts | ~111 KB |

## Accessibility

- Every interactive component has a Vitest behavior test asserting click handlers, keyboard, and aria attributes.
- The reduced-motion media query is honored globally via [`tokens.css`](./src/theme/tokens.css). Verified by `e2e/reduced-motion.spec.ts`.
- The component viewer is axe-clean in light + dark (`e2e/axe.spec.ts`).

## Smoke against the real backend

`tools/smoke_real_backend.py` (in the repo root) drives every endpoint
the UI consumes against the actual SQLite catalog via FastAPI's
`TestClient`:

```pwsh
uv run python ../tools/smoke_real_backend.py
```

It hits projects/proposals/journal, exercises a propose -> approve ->
undo loop with a `SetTags` action, and asserts the row reverts.
