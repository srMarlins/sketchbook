# Web Stationery Redesign + Real Backend Wiring

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Why this plan exists:** the sketchbook v1 redesign attempted on `feat/sketchbook-ui` did not land — too much asset dependency, busy chrome, weak dark mode, density problems. We're keeping the components, hooks, routing, tests, and overall architecture, but **replacing the visual layer** with a calmer "warm stationery with character" theme that is achievable using only CSS + the icon doodles we already have. We're also **wiring the UI to the real FastAPI backend** instead of mock JSON.

**Goal:** a clean, dense, warm-pastel stationery UI for light + a matching dim-paper dark mode, served against the real `audio_web` API at `127.0.0.1:7878` over a Vite proxy. No raster textures, no spiral binding, no torn edges, no wood grain. Strips and components remain colored by Ableton-14 truthful palette.

**Branch:** `feat/web-stationery`, branched from `feat/sketchbook-ui` so we inherit components/hooks/tests rather than rebuild. Worktree at `Z:\User\audio\.worktrees\web-stationery`.

**Source-of-truth design spec:** `docs/design-language.md` — but several elements are explicitly being deferred or dropped (wood, torn edges, spiral binding, strip hold-methods, decorations). Treat the doc as guidance for *tokens / typography / motion / a11y rules*; treat the *aesthetic chrome* sections as suggestions we're choosing not to execute.

**Out of scope:**
- Any work on raster assets we don't already have committed
- The `<MarginStickyNote>` pattern (deferred — no backend suggestions endpoint)
- Theme switcher UI button (still derived from `prefers-color-scheme` via `useTheme`; manual toggle deferred)
- A real CLI for "claude propose..." — backend already exists, just no example client

---

## Tech changes summary

- **Theme tokens** rewritten: pastel cream paper + warm slate dark. Ableton-14 strip palette slightly desaturated to sit with pastels.
- **Surface components** stripped of decoration:
  - `Desk` → flat warm surface (no wood)
  - `NotebookPage` → simple paper card (no spiral, no rule lines, no margin lines)
  - `Shelf` → grid of notebook cards (no wood band)
  - `CorkboardPanel` → clean drawer with horizontal tabs (not paper-strip kraft sidebar)
  - `TornPagePile` → stacked cards with subtle offset (no torn edges, no rotation jitter)
  - `SongStrip` → condensed card row, dropped washi/staple/tape, dropped jitter
- **Density**: 3-column SongStrip card grid at desktop (≥1280px), 2-col at md, 1-col at sm.
- **Brand**: drop the 427KB `sketchbook.png`. Title is text in display font.
- **Real API**: `lib/api.ts` calls `/api/...` against the FastAPI backend via Vite proxy.
- **Backend tweak**: add `tags` to `GET /api/projects` response (one-line SQL change in `audio_core/db/projects.py`).
- **Suggestions feature**: `<MarginStickyNote>` use sites removed; component file kept for `/_dev` reference.

---

## Pre-flight

**Conventions (unchanged from previous plan):**
- Every component has a `*.test.tsx` next to it for behavior.
- Every component has a `dev/registry.tsx` entry.
- Tailwind classes only, no inline styles unless dynamic (per-strip Ableton color stays inline).
- Pre-commit must pass `typecheck` + `test` (Vitest unit) at minimum.
- Mock fixtures stay only for Vitest tests, never for the running app.

**Run loop:** Two terminals.
1. `python -m audio_web.main` (FastAPI on 7878)
2. `cd web && npm run dev` (Vite on 5173, proxies `/api` → 7878)

---

## Phase 0 — Backend tags-on-list patch + Vite proxy

### Task 0.1: Backend `GET /api/projects` includes tags array

**Files:**
- Modify: `packages/core/audio_core/db/projects.py` (`search_projects`)
- Modify: `packages/web/audio_web/routes_projects.py` (response shape doc only)
- Modify: `packages/web/tests/test_projects_endpoints.py` (assert tags appear)

**Step 1 — Failing test:** assert `GET /api/projects` returns `tags: []` (or populated) on each row.

**Step 2 — Implement:** in `search_projects`, after the main SELECT, batch-fetch tags: one query joining `project_tags` + `tags` keyed by all returned project ids; attach to dicts as `tags: list[str]`.

**Step 3 — Verify:** run pytest in `packages/web`. Curl `/api/projects?limit=5 | jq '.[0].tags'` returns an array.

**Step 4 — Commit.**

### Task 0.2: Vite dev proxy + drop `VITE_USE_MOCKS` from runtime path

**Files:**
- Modify: `web/vite.config.ts` (add `server.proxy['/api']`)
- Modify: `web/.env.development` (remove `VITE_USE_MOCKS=true`)
- Create: `web/.env.test` (set `VITE_USE_MOCKS=true` for vitest)

**Step 1:** Add proxy:
```ts
server: {
  port: 5173,
  strictPort: true,
  proxy: {
    '/api': { target: 'http://127.0.0.1:7878', changeOrigin: false },
  },
},
```

**Step 2:** Remove `VITE_USE_MOCKS=true` from `.env.development`. Add `web/.env.test` with `VITE_USE_MOCKS=true` so unit tests use mocks.

**Step 3 — Commit.**

---

## Phase 1 — API client wiring against real backend

### Task 1.1: Reshape `lib/types.ts` to match backend

**Files:**
- Modify: `web/src/lib/types.ts`

**Step 1:** Add real shapes:
- `ProjectListRow` — fields the list endpoint returns (now including `tags: string[]`)
- `ProjectDetail` — list row + `plugins: ProjectPlugin[]` + `samples: ProjectSample[]` (tags already on list)
- `BackendProposal` — `{ proposal_id, actor, actions: [{type, args}], rationale }`
- `JournalBatch` — `{ batch_id, actor, actions, created_at, ... }` (read backend manifest schema)
- Keep `Suggestion` type but mark `// frontend-only, no backend yet`

**Step 2:** Delete or migrate the legacy `Project` and `Proposal` to the new names.

**Step 3 — Commit.**

### Task 1.2: Rewrite `lib/api.ts` to fetch real endpoints

**Files:**
- Modify: `web/src/lib/api.ts`
- Modify: `web/src/mocks/handlers.ts` (keep for tests; align return shapes)
- Modify: `web/src/mocks/projects.json`, `proposals.json`, `journal.json` (re-shape to match new types)

**Step 1:** Replace `USE_MOCKS` branching with simple `fetch('/api/...')` calls in production. Keep a `// @testing-only` mock-mode path (`if (import.meta.env.MODE === 'test')`) for Vitest only.

**Step 2:** New API methods:
- `listProjects(filters?)` — query string: `?query=&tempo_min=&tempo_max=&archived=&limit=`
- `getProject(id)` — full detail w/ plugins & samples
- `listProposals()`
- `submitProposal(...)` — wired but unused by UI initially
- `approveProposal(id)`
- `rejectProposal(id)` — DELETE
- `listJournal(limit?)`
- `getBatch(batchId)`
- `undoBatch(batchId)`

**Step 3:** Update Vitest API tests to use new shapes.

**Step 4 — Commit.**

### Task 1.3: Proposal translation layer

**Files:**
- Create: `web/src/lib/proposals.ts`
- Create: `web/src/lib/proposals.test.ts`

**Step 1 — Failing test:** given a `BackendProposal` with one `RenameProject` action, `translateProposal(p, projectsById)` returns `[{ id, verb: 'rename', target, before, after, reason, source }]`.

**Step 2 — Implement:**
- `translateProposal(prop, projectsById): TranslatedProposalRow[]`
- Each backend `action` becomes one row; row id = `${prop.proposal_id}#${i}`
- For `RenameProject`: before = current project name, after = `args.new_dir_name`
- For `MoveProject`: before = current parent_dir, after = `args.new_parent`
- For `SetColorTag`: before = current als-N, after = `args.color`
- For `SetTags`: before = current tags joined, after = `args.tags` joined
- For `ArchiveProject`: before = "active", after = "archived"

**Step 3 — Commit.**

### Task 1.4: TanStack Query layer aligned with real endpoints

**Files:**
- Modify: `web/src/app/queries.ts` — drop `useSuggestions`; align `useProjects` / `useProposals` / `useJournal`

**Step 1:** Remove `useSuggestions`. Drop the suggestions JSON import.

**Step 2:** Add `useProjectDetail(id)` for the corkboard.

**Step 3:** Add `useUndoBatch()` mutation that invalidates `['journal']` and `['projects']`.

**Step 4 — Commit.**

---

## Phase 2 — Strip surface decorations, repurpose component files

### Task 2.1: Delete or rewrite the now-meaningless surface components

**Files:**
- Modify: `web/src/components/surface/Desk.tsx` — drop wood, keep top header + sidebar slot + main outlet, simplify className-only
- Modify: `web/src/components/surface/NotebookPage.tsx` — drop spiral binding, drop margin rule, drop lined-paper repeating-gradient. Just a simple rounded paper card.
- Modify: `web/src/components/surface/Shelf.tsx` — drop wood. Becomes a `gap-4 flex-wrap` container; rename to `<NotebookGrid>` (inside same file via export alias).
- Modify: `web/src/components/surface/NotebookSpine.tsx` — drop tilt jitter, drop kraft/manila kind backgrounds (keep `kind` prop as semantic marker only — affects accent color, not surface). Rename mentally to "notebook card."
- Modify: `web/src/components/surface/CorkboardPanel.tsx` — drop kraft sidebar; horizontal Radix Tabs at top of drawer; clean Dialog Slide-in.
- Modify: `web/src/components/surface/TornPagePile.tsx` — drop torn-edge motion; subtle slide+fade only on enter/exit; rotation removed.

**Step 1:** Component-by-component, strip the visual decorations. Keep all behavior + props identical so callers don't break.

**Step 2:** Run unit tests; fix anything broken by class changes.

**Step 3 — Commit per component (six commits, one per file).**

### Task 2.2: Rewrite SongStrip for stationery density

**Files:**
- Modify: `web/src/components/data/SongStrip.tsx`
- Modify: `web/src/components/data/SongStrip.test.tsx`

**Strip new spec:**
- Card with rounded-md, 1px ink-muted border, soft warm shadow
- Left edge: 6px solid `var(--als-N)` color block
- Title: `font-mono` 14–15px, single-line truncate, top of card
- Second row: 4 inline icons + value separated by ink-muted dots: BPM · key · tracks · length
- No background color from als — only the left edge stripe carries the color
- Removed: hold-method visuals, washi/staple/tape, rotation jitter, full-strip background
- Keep: `data-color-idx` (so existing tests pass), `data-hold` removed from tests

**Step 1:** Update tests for new layout. The "all 14 colors render" test stays; "hold method deterministic" test deleted.

**Step 2:** Implement.

**Step 3:** Verify in `/_dev` shows clean condensed cards in both themes.

**Step 4 — Commit.**

### Task 2.3: Rewrite ProposalCard for stationery look

**Files:**
- Modify: `web/src/components/data/ProposalCard.tsx`
- Modify: `web/src/components/data/ProposalCard.test.tsx`

**Spec:**
- Flat card, no rotation jitter
- Header: verb (display font) + target (mono truncate)
- Reason: sans, ink-muted
- Diff: two rows, before/after, with ✕/✓ marks instead of strikethrough/green
- Footer: approve (primary) + reject (ghost) + actor pill

**Step 1 — tests updated for non-rotated layout.**
**Step 2 — implement.**
**Step 3 — commit.**

### Task 2.4: Rewrite MarginStickyNote (or delete its use sites)

**Decision:** delete use sites in routes (since suggestions are gone), but **keep** the component file for `/_dev` so the pattern is preserved for v0.2.

**Files:**
- Modify: `web/src/routes/notebook.tsx` — remove suggestion lookup + sticky note rendering
- Modify: `web/src/components/data/MarginStickyNote.tsx` — drop rotation jitter, simplify to a small flat note for `/_dev` only

**Step 1 — Commit.**

### Task 2.5: Tighten NavStrip / Sidebar / BrandingHeader

**Files:**
- Modify: `web/src/components/data/NavStrip.tsx` — flat row, soft pastel hover, active = solid pastel
- Modify: `web/src/components/surface/Sidebar.tsx` — keep keyboard nav, restyle items
- Modify: `web/src/components/surface/BrandingHeader.tsx` — display-font text "Sketchbook", drop image. Use `var(--font-display)`.

**Step 1 — Commits per file.**

---

## Phase 3 — Token rewrite (the visual personality)

### Task 3.1: Rewrite `tokens.css` for warm pastel stationery

**Files:**
- Modify: `web/src/theme/tokens.css`
- Modify: `web/src/theme/contrast-table.ts` — re-verify contrast against new ink colors

**Light theme palette (semantic):**
- `--surface-desk`: `#faf6ec` (cream paper)
- `--surface-page`: `#fffaf0` (slightly brighter inset paper)
- `--surface-strip`: `#fffefa` (almost white, for cards)
- `--surface-panel`: `#f4eedd` (drawer)
- `--surface-overlay`: `rgba(60, 50, 40, 0.36)`
- `--ink-primary`: `#2b2925` (warm near-black)
- `--ink-secondary`: `#4a463f`
- `--ink-muted`: `#7a7268`
- `--rule-line`: `rgba(50, 40, 32, 0.12)`
- `--accent-action`: `#c2554a` (clay-red, calmer than the previous `#d63a3a`)
- `--accent-secondary`: `#7a8478` (sage)

**Dark theme overrides:**
- `--surface-desk`: `#1a1714` (warm slate)
- `--surface-page`: `#22201c`
- `--surface-strip`: `#2a2724`
- `--surface-panel`: `#1f1d1a`
- `--ink-primary`: `#e8e0d0` (warm cream ink, not white)
- `--ink-secondary`: `#c8c0af`
- `--ink-muted`: `#8a8275`
- `--rule-line`: `rgba(232, 224, 208, 0.12)`
- `--accent-action`: `#e07a6e` (lighter clay-red, better contrast)

**Ableton-14:** pastel-tweak each by mixing 10% with paper-cream (light) / paper-warm-slate (dark). Keep the entries but apply a `color-mix(in srgb, ...)` adjustment.

**Verify:** all 14 strip × ink combos still pass WCAG AA. Update `contrast-table.test.ts` to run against the new hex values.

**Step 1 — Edit tokens; re-run contrast test; if any combo fails, adjust the lightness on that als entry.**

**Step 2 — Visual sweep at `/_dev` for each component in both themes.**

**Step 3 — Commit.**

### Task 3.2: Drop self-hosted woff2 fonts we no longer need

**Files:**
- Modify: `web/src/theme/tokens.css` (remove unused `@font-face`)
- Possibly delete: nothing yet — keep all four fonts

We use all three font families. Skip this task unless a font is genuinely unused. Note for future trimming.

### Task 3.3: Drop dead assets

**Files:**
- Delete: `web/public/brand/sketchbook.png`
- Delete (if unreferenced after redesign): `web/public/raw/torn-edges-4.png`, `web/public/raw/washi-tape-6.png`, `web/public/raw/hold-methods.png`
- Delete: `web/src/mocks/suggestions.json`

**Step 1:** grep for each. Delete only what nothing references.

**Step 2 — Commit.**

---

## Phase 4 — Page composition

### Task 4.1: Home — clean grid of notebook cards

**Files:**
- Modify: `web/src/routes/index.tsx`

**Spec:**
- Top header strip: app name in display font (left), search bar (right). 64px tall, ink-on-cream, thin bottom border.
- Sidebar: pastel rounded items. Active item = filled pastel; inactive = transparent w/ hover.
- Main area: section header "Notebooks", then a flex-wrap of notebook cards (Inbox, year cards, tag cards, Archive, Claude). Each card 200×120, pastel tint per-kind.

**Step 1 — Implement.**
**Step 2 — Commit.**

### Task 4.2: Notebook view — three-column SongStrip grid

**Files:**
- Modify: `web/src/routes/notebook.tsx`

**Spec:**
- Sticky page header: title, subtitle (`N sketches`), search/filter chips (filter UI deferred — just count + title for now).
- Strips render in `grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3`.
- Drop the virtualized list; with 483 projects across many notebooks the per-notebook count is small enough that a vanilla grid suffices. (Re-add virtualization in v0.2 only if any notebook crosses ~500 strips.)
- Search filtering still works via `useSearchStore`.
- Esc cascade preserved.
- Click strip → corkboard slide-in.

**Step 1 — Implement.**
**Step 2 — Commit.**

### Task 4.3: Proposals — clean stacked cards

**Files:**
- Modify: `web/src/routes/proposals.tsx`

**Spec:**
- Same `<TornPagePile>` (now flat) container, but each child is one *translated row* per backend action (so a single backend proposal with 3 actions shows as 3 rows but they're visually grouped under a tiny "from proposal {id}" label).
- Approve = approve the entire backend proposal (we can't partial-approve; the action layer is atomic). UI shows a subtle "approving X actions" indicator.
- Reject = `DELETE /api/proposals/{id}` for the parent proposal id.

**Step 1 — Implement.**
**Step 2 — Commit.**

### Task 4.4: Claude's notebook — clean journal feed

**Files:**
- Modify: `web/src/routes/claude-notebook.tsx`

**Spec:**
- Each `JournalBatch` is one row: actor pill + verb (display) + N actions summary + timestamp + undo button.
- Click an action target → opens the project corkboard at the History tab.
- Undo button next to each batch.

**Step 1 — Implement + new query mutation `useUndoBatch`.**
**Step 2 — Commit.**

### Task 4.5: Corkboard tabs — work against real detail

**Files:**
- Modify: `web/src/components/corkboard/Overview.tsx` — uses real fields
- Modify: `web/src/components/corkboard/Tracks.tsx` — placeholder until track-list endpoint exists; show "track count: N"; remove fake row generation
- Modify: `web/src/components/corkboard/Plugins.tsx` — render `project.plugins` from real detail
- Modify: `web/src/components/corkboard/Samples.tsx` — render `project.samples` from real detail
- Modify: `web/src/components/corkboard/History.tsx` — real journal filtered by `batch.actions[].args.project_id` matching this project
- Modify: `web/src/components/corkboard/index.tsx` — use `useProjectDetail(id)` instead of passing the project from list

**Step 1 — Implement.**
**Step 2 — Commit.**

---

## Phase 5 — Tests + smoke

### Task 5.1: Update Vitest unit suite for new shapes

**Files:**
- All `*.test.tsx` files that reference the old `Project`/`Proposal` shapes

**Step 1 — Run `npm test`; fix every failure either by updating expectation or fixture.**
**Step 2 — Commit.**

### Task 5.2: Update Playwright E2Es for the new flow

**Files:**
- Modify: `web/e2e/browse-and-open.spec.ts`
- Modify: `web/e2e/approve-proposal.spec.ts`
- Modify: `web/e2e/axe.spec.ts`
- Modify: `web/e2e/reduced-motion.spec.ts`
- New: `web/e2e/_setup.ts` — boot `audio_web` + Vite together for the real run

**Decision:** there are two ways to E2E.
1. **Test against real backend** — set `AUDIO_ROOT` to a temp dir in CI, scan a fixture project, assert against the real DB.
2. **Test against Vite dev mock-mode** — set `VITE_USE_MOCKS=true`, isolate from backend.

**Recommendation:** mix. `axe` + `reduced-motion` use mock-mode (no backend needed). `browse-and-open` and `approve-proposal` set `VITE_USE_MOCKS=true` because we don't have a CI-friendly seed yet. Add a TODO to convert to real-backend in v0.2.

**Step 1 — Configure Playwright `webServer` to set `VITE_USE_MOCKS=true`.**

**Step 2 — Update test selectors/expectations for new layout (no torn cards, no hold methods, etc.).**

**Step 3 — Commit.**

### Task 5.3: End-to-end real-backend smoke (manual + scripted)

**Files:**
- Create: `web/scripts/smoke-real.ps1` (Windows-friendly) and `.sh` for parity

**Step 1:** Script that:
1. Spawns `audio_web` on 7878
2. Spawns Vite on 5173 without mocks
3. `curl http://127.0.0.1:5173/api/health` returns `{ ok: true }` via the proxy
4. `curl http://127.0.0.1:5173/api/projects?limit=3` returns 3 real rows from the catalog
5. Kills both

**Step 2:** Manually open `http://localhost:5173/` and verify:
- 483 real projects scan (likely some in inbox, year-bucketed by `parent_dir`)
- Click a year → notebook view shows real strips
- Click a strip → corkboard opens with real plugins, samples, tags
- Visit `/proposals` → empty state ("nothing pending")
- Visit `/n/claude` → 3 real journal entries

**Step 3:** Commit script + a screenshot.

---

## Phase 6 — Final cleanup

### Task 6.1: README + auto-memory

**Files:**
- Modify: `web/README.md` — drop "mock by default" sections; add "Run alongside the FastAPI backend"; add the script reference
- Modify: `C:\Users\jtfow\.claude\projects\Z--User-audio\memory\project_audio_overview.md` — update "Frontend" section to note the stationery theme + real backend wiring

**Step 1 — Commit.**

### Task 6.2: Bundle budget audit

**Step 1:** `npm run build` — confirm gzipped JS still under 300KB. Confirm fonts <180KB. Cold-load total <900KB.

**Step 2:** If brand PNG was successfully removed and no new heavy assets added, total cold-load shrinks meaningfully.

**Step 3 — Commit if any cleanup.**

---

## Definition of done

- `python -m audio_web.main` running, `cd web && npm run dev` running.
- Visit `http://localhost:5173/`, see the real 483 projects bucketed into notebooks.
- Both light and dark themes look distinct and intentional in `/_dev`.
- All Vitest unit tests pass.
- All Playwright E2Es pass.
- Axe sweep is clean both themes.
- Reduced-motion sweep confirms no animation leaks.
- Approve/reject a real proposal end-to-end (you'll need to submit one via `curl` since no client builds them yet).
- Undo a real journal batch via the Claude notebook UI.
- README + memory updated.
- `feat/web-stationery` branch is mergeable to `main`.

---

## Open questions to resolve before / during execution

1. **Catalog DB lock:** the FastAPI server holds the SQLite open. If `cli` is also running, contention could surface. Backend already opens with `WAL` mode (assumed) — verify in `audio_core/db/connection.py` and address if not.
2. **Plugin/sample listing in corkboard:** if 483 projects have many plugins, the detail endpoint payload could be large. Acceptable for v0.1; revisit if perf bites.
3. **Theme toggle UI:** deferred. Users wanting to force a theme can use OS-level `prefers-color-scheme`. Add a small toggle in v0.2 if desired.
4. **What does `/proposals` show when empty?** Currently shows "nothing pending — try `claude propose` in the CLI." That copy is fine for v0.1 since the MCP can post proposals.
