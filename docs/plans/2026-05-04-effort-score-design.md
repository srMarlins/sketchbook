# Effort Score Design

**Date:** 2026-05-04
**Status:** Approved
**Related:** `docs/library-conventions.md`, `docs/plans/2026-05-04-library-organization-design.md`, `docs/plans/2026-05-04-home-shelves-design.md`

## Goal

Give every project in the catalog a numeric **effort score** (0–100) computed at scan time, so the user can sort and filter by "how much work did past-me put into this." The score is a derived field — the user never sets it directly. It powers the "Forgotten gems" shelf on the home page and a sortable column in the projects table.

## Design

### Storage

New columns on `projects`:

| Column                | Type     | Notes                                                          |
|-----------------------|----------|----------------------------------------------------------------|
| `effort_score`        | INTEGER  | 0–100, recomputed every scan                                   |
| `effort_breakdown`    | TEXT     | JSON blob: per-signal contributions, used for tooltips         |

Both are pure functions of fields the parser already extracts — no parser changes.

### Scoring function (v1)

Pure function in `core/scoring.py`, signature:

```python
def compute_effort(row: ProjectRow) -> tuple[int, dict[str, float]]:
    """Return (score 0..100, breakdown dict)."""
```

v1 weights (strawman, tunable):

```
score =
    log10(track_count + 1)      * 18
  + log10(plugin_count + 1)     * 12
  + log10(unique_plugins + 1)   * 10
  + log10(sample_count + 1)     *  6
  + log10(file_size_kb + 1)     *  8
  + (has_master_chain ? 10 : 0)
  + (has_automation   ? 8  : 0)
```

Result clamped to `[0, 100]`. Log scaling so heavy-tailed counts don't dominate.

The breakdown dict captures each signal's pre-clamp contribution so the UI can render "why is this an 80?" on hover.

### Recompute strategy

Recompute for **every project on every scan**, not only on parser-detected change:

- Math is microseconds per row.
- Keeps the score consistent with the current weight set even after weight tuning.
- Avoids a "score drift" class of bugs where some rows have stale scores after a config change.

The scan path becomes: parse → upsert project + plugins + samples → compute effort → write `effort_score` and `effort_breakdown`.

### Tuning workflow

After v1 lands, tune weights against the real library:

1. Query top 50 by score; eyeball — should match user's intuition for "high investment."
2. Query bottom 50; should be sketches and one-clip projects.
3. Adjust weights, recompute, repeat. Expect 2–3 passes.

This tuning happens in a separate small commit after the column exists.

### API

`ProjectSummary` gains:

```python
effort_score: int
```

(Breakdown stays server-side for now; only fetched for the detail page.)

`GET /api/projects` gains query params:

- `min_effort: int | None` — inclusive lower bound
- `max_effort: int | None` — inclusive upper bound
- `order_by: 'name' | 'mtime' | 'effort'` — defaults to `'mtime'`
- `order_dir: 'asc' | 'desc'` — defaults to `'desc'`

### Web UI

- New **Effort** column in the projects table, between Tempo and Last Modified.
- Cell renders a 10-segment colored bar plus the numeric score (e.g. `■■■■■□□□□□ 52`).
- Sortable by clicking the column header.
- New filter control in the existing filter bar: a numeric range (slider or two inputs) for effort.
- Tooltip on the score bar shows the breakdown (top 3 contributing signals) so the score is interpretable.

### MCP

`search` tool gains `min_effort`, `max_effort`, and `order_by` args. The `instructions` block updates to teach Claude:

- Effort is a 0–100 derived score, never set by the user.
- For "old projects with potential" or "forgotten gems," sort `order_by="effort"` desc and filter by year or last-modified.
- For batch triage, prefer high-effort untriaged projects first — wrong-tagging a high-effort project costs more than wrong-tagging a sketch.

### Score range guidance (qualitative)

For docs and tooltips:

- **0–25** — sketch / single idea
- **25–50** — meaningful work, partial arrangement
- **50–75** — substantial investment, near-complete arrangement or full mix in progress
- **75–100** — serious project: full track, complex arrangement, deep sound design or mixing

These bands are descriptive only — there are no hard thresholds in code beyond clamp(0, 100).

## Risks / caveats

1. **File size correlates with sample bytes, not effort.** A project that imports a 100 MB sample library scores high without real work. Mitigation: use sample *count* not bytes once we trust counts; or subtract sample-blob bytes from `file_size` before scoring. v1 leaves this as-is and we tune around it.

2. **Templates inflate scores.** A 24-track template starts every project at the same floor. Mitigation: weight `unique_plugins` higher than `plugin_count` (already the case) so cookie-cutter templates score lower than projects with diverse sound design.

3. **Effort ≠ quality.** The score answers "did past-me invest in this?" not "is this good?" That's intentional and matches the user's question.

4. **Re-tuning weights changes existing scores.** Acceptable — the score is derived, not user-curated, so callers should never assume stability across versions. Document this.

## Plan to land

1. Add `effort_score` and `effort_breakdown` columns + migration in `core/db/`.
2. Implement `compute_effort()` in `core/scoring.py` with v1 weights + breakdown dict.
3. Hook into the scan path: after upsert, compute and store the score for every row.
4. Backfill existing rows by running the scoring pass once over the catalog.
5. Extend `search_projects()` in `core/db/projects.py` with `min_effort`, `max_effort`, `order_by`, `order_dir`.
6. Update `ProjectSummary` model in `web/api/` to include `effort_score`.
7. Extend `GET /api/projects` endpoint with new query params.
8. Add Effort column to the projects table component in `web/src/components/data/`.
9. Add the effort range filter control to the filter bar.
10. Add tooltip with breakdown to the effort cell.
11. Extend MCP `search` tool with the new args; update `instructions`.
12. Update `docs/library-conventions.md` to mention effort as a fourth (computed, not user-set) axis with the qualitative band guide.
13. Tune weights against the real library — separate small follow-up commit.

## Out of scope

- Edit-session count from .als XML save events (deferred — bigger parser change).
- Total clip duration as a signal (deferred — bigger parser change).
- Per-user weight tuning UI (deferred — tune in code for now).
- Backwards-compat for old `effort_score` values across weight changes (intentionally not provided).
