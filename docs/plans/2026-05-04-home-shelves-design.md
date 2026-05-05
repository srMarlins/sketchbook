# Home Shelves Design

**Date:** 2026-05-04
**Status:** Approved
**Related:** `docs/plans/2026-05-04-effort-score-design.md` (depends on), `docs/plans/2026-05-04-library-organization-design.md`

## Goal

Replace the sparse home page with a Netflix-style discovery surface: a vertical stack of horizontal shelves, each one a curated answer to "why might I want to find this project right now?" The full projects table moves to `/projects`; the home page becomes about **discovery**, not data.

## Design

### Layout

Vertical stack of shelves on `/`. Each shelf is a horizontal scroll of project cards.

### Shelves (v1)

Five shelves, defined server-side. Order matters — most relevant at the top.

| Shelf                  | Filter                                                                                | Sort           | Cap |
|------------------------|---------------------------------------------------------------------------------------|----------------|-----|
| 🔵 Currently working on| color = blue OR last-modified within 14 days                                          | mtime desc     | 12  |
| 💎 Forgotten gems     | effort_score ≥ 60 AND last-modified > 180 days AND not archived AND color ∉ {green,red} | effort desc    | 20  |
| 🟠 Almost done        | color ∈ {orange, yellow}                                                              | effort desc    | 15  |
| 🟣 Has potential      | color = purple                                                                        | mtime desc     | 15  |
| ❓ Untriaged          | color IS NULL AND not archived                                                        | effort desc    | 12  |

Each shelf header has a "See all →" link that opens `/projects` pre-filtered to the shelf's query.

A shelf with zero results renders a one-line empty state ("Nothing to show — keep working!") rather than disappearing, so the home page layout stays predictable.

### Card design

```
┌─────────────────────────────┐
│ ●  [color dot]              │  state at a glance
│ Midnight Drive              │  project name (truncate)
│ 124 BPM · 4:32 · 28 tracks  │  key stats
│ ■■■■■□□□□□ effort 52         │  10-segment bar
│ 3 months ago                │  relative time
│ [▶ Open in Ableton]         │  primary action
└─────────────────────────────┘
```

- **Click card body** → `/projects/{id}` detail page.
- **Click "Open in Ableton"** → `POST /api/projects/{id}/open` (see below).
- Width sized so ~6 cards fit per row at 1440px viewport.
- Reduced-motion: scroll snaps disable, no hover transforms.
- Dark/light mode honoured via existing token system.

### "Open in Ableton" action

New endpoint:

```
POST /api/projects/{id}/open  →  { ok: true } | { ok: false, error }
```

Implementation: shell out via `start "" "<absolute_als_path>"` on Windows. `.als` is the registered handler for Ableton, so this opens the project directly.

Constraints:

- Server is localhost-only (already enforced) — process launching is acceptable here.
- The `.als` path must be inside the existing path allowlist (`Z:/User/audio/Projects`). Reject otherwise with 403.
- No journal entry — opening a project is not a write.
- No `propose_batch` involvement — this is a query-side action, like `search`.

### Server-side shelf definition

Single endpoint:

```
GET /api/home  →  HomeResponse
```

Response shape:

```python
class Shelf(BaseModel):
    id: str                          # 'currently-working' | 'forgotten-gems' | ...
    title: str
    description: str                  # one-line subtitle for the shelf header
    see_all_query: str                # query string for /projects?<this>
    projects: list[ProjectSummary]    # already includes effort_score

class HomeResponse(BaseModel):
    shelves: list[Shelf]
```

Shelves are defined in a server-side list (`web/api/home.py`) so:

- Adding a shelf is one entry, not a frontend change.
- The MCP can call equivalent searches and stay in sync with what the user sees.
- Filters and caps live in one place.

### Frontend

- New route `/` renders `<HomePage>`, which fetches `/api/home` and maps shelves to `<Shelf>` components.
- Existing big projects table moves to `/projects` (it's already there as the home — the change is conceptual: home stops being the table).
- New components in `web/src/components/data/`:
  - `<Shelf>` — horizontal scroller with header, "See all →", empty state.
  - `<ProjectCard>` — the card layout above.
- Card uses existing primitives (color dot, effort bar, time-ago) — no new design tokens needed.

### MCP integration

No new MCP tool. The home shelves are a UI surface, not a tool surface. But the `instructions` block gets a paragraph explaining:

- The user has a "Forgotten gems" shelf surfacing high-effort old projects.
- When the user mentions "gems" or "buried" or "forgotten," prefer searches matching that filter (effort_score ≥ 60, last-modified > 180 days).
- The home page is the user's primary discovery surface; the AI's job is to handle batch operations the UI can't ergonomically do (e.g. "tag all 47 of these as `house`").

### Performance

- Home endpoint runs ~5 small SQL queries against indexed columns. Total: well under 50 ms even at 1,628 projects.
- Each query already has the necessary indexes (mtime, effort_score, color) — no new indexes for v1.
- If shelves grow past ~10 in a future version, switch to a single CTE-based query.

## Out of scope

- 🆕 Recently scanned shelf — useful only during initial index, easy to add later (one server-side list entry).
- 🎲 Surprise me shelf — random sample weighted by effort. Deferred.
- Hover preview (waveform / screenshot) on cards — deferred to v0.3+ when we have audio preview.
- Right-click quick actions on cards (set color, archive, edit tags) — deferred; users can use the detail page.
- Drag a card from one shelf to another to retag — deferred.
- Per-user shelf customization — deferred; v1 ships with the fixed five.
- Pagination within a shelf — caps are intentional; "See all →" handles overflow.

## Plan to land

(Lands **after** the effort-score plan, since "Forgotten gems" depends on the column.)

1. Add `web/api/home.py` with the shelf definitions list and a `compute_shelves(db)` function.
2. Add `GET /api/home` endpoint returning `HomeResponse`.
3. Add `POST /api/projects/{id}/open` endpoint with allowlist check + shell-out.
4. Add `<ProjectCard>` and `<Shelf>` components in `web/src/components/data/`.
5. Add `<HomePage>` and route it at `/`.
6. Move existing projects table to `/projects` (route only — component unchanged).
7. Add a top nav linking Home / Projects / Proposals / Journal so the table is reachable.
8. Update MCP `instructions` to mention the Forgotten Gems framing for "gems"/"buried"/"forgotten" queries.
9. Add real-backend smoke coverage in `tools/smoke_real_backend.py` for `/api/home` and `/api/projects/{id}/open`.
10. Update component viewer at `/_dev` with `<ProjectCard>` and `<Shelf>` in light + dark + reduced-motion.

## Risks

1. **Empty library on first run** — every shelf will be empty. The empty state needs to feel like a *feature*, not a broken page. Recommendation: add a one-time "Welcome — start a scan to fill your shelves" banner above the shelves when the catalog is empty. Lands as part of step 5.

2. **Shell-out fails silently on Windows** — if Ableton isn't installed or `.als` isn't registered, `start` returns success but nothing opens. The endpoint will still 200. Mitigation: document the requirement; add a small in-UI tooltip on the button explaining the prereq. Don't try to detect Ableton's install — too brittle.

3. **Forgotten Gems filter is dependent on effort_score weight tuning.** If weights are bad, the shelf surfaces sketches. Acceptable — the tuning step in the effort-score plan addresses this before shelves go live.
