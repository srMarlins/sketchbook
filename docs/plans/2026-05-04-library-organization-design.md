# Library Organization Design

**Date:** 2026-05-04
**Status:** Approved
**Related:** `docs/library-conventions.md` (canonical vocabulary), `docs/plans/2026-05-04-audio-catalog-design.md` (system design)

## Goal

Give the user a low-friction way to organize ~1,628 Ableton projects so they can answer:

1. What am I working on right now?
2. What did I make in year X?
3. What old projects have potential and should be revisited?
4. What's almost done? What needs mixing?
5. What melody/drum/bass loops do I have?

…without nesting folders deeply or forcing a project into one bucket forever.

## Design

Three orthogonal axes, each landing in a different place:

| Axis     | Lives in              | Set via                         | Purpose                              |
|----------|-----------------------|----------------------------------|--------------------------------------|
| Year     | Folder on disk        | `MoveProject` (one-time backfill) | Origin year — when did I start this? |
| State    | Ableton color tag     | `SetColorTag`                    | Lifecycle stage (done, in-progress…) |
| Content  | Catalog free-form tags| `SetTags`                        | What kind of thing is it?            |

### Folder structure

```
Projects/
  2026/
  2025/
  2024/
  ...
  _archive/   (is_archived=1, hidden)
```

One level deep. Year = year created, locked once set. Browsing in Explorer/Finder gives a chronological view; everything else is queried through the catalog.

### State — color tag

See `docs/library-conventions.md` for the canonical color→state map. Summary:

- **green** done · **yellow** needs mix/master · **orange** almost done
- **blue** in progress · **purple** has potential — revisit
- **red** dead · **gray/none** untriaged

The color is visible inside Ableton itself when the project is opened, so triage state survives the catalog and shows up in the user's actual editing workflow.

### Content — free-form tags

Type (`full-track`, `sketch`, `melody-loop`, `drum-loop`, `bass-loop`, `vocal-chop`, `remix`, `collab`), plus optional genre and faceted tags (`key:Cm`, `client:x`). See conventions file for full vocabulary.

## Year-created assignment

The catalog needs a reliable `year_created` signal. Options ranked by reliability:

1. Earliest `<LastModDate>` inside the .als XML (survives moves, resets on Save As).
2. Filesystem `ctime` (often = copy date on Windows/network drives — unreliable).
3. Parent folder name regex match for `(20\d\d)`.

**Approach:** during initial backfill, propose a year per project using best-available signal (XML date → ctime → folder regex). User batch-approves. After approval, `year_created` is locked and never recomputed by future scans.

This requires:

- A new `year_created INTEGER` column on the `projects` table.
- A migration that adds the column and backfills NULL.
- A backfill helper that emits a `MoveProject` proposal per project mapping it into `Projects/<year>/<existing-folder-name>/`.
- A new action type `SetYearCreated` (or treat it as set-once via the move + a column update inside the same action).

This work is **deferred to a separate plan** (`docs/plans/2026-05-XX-year-created-backfill-plan.md`) since it touches schema, parser, and actions.

## MCP integration

Extend `build_server()` `instructions=` in `packages/mcp/audio_mcp/main.py` to teach Claude the conventions, so triage proposals stay consistent across sessions without the user re-explaining.

The expanded instructions will:

- State the three-axis model (year folder / color state / free tags).
- Embed the color→state map.
- List the canonical free-tag vocabulary (type + genre + faceted).
- Describe the triage workflow expectation (filter → inspect → propose batch).
- Reference `docs/library-conventions.md` as the source of truth.

No new tools are required for v0.1 of this work — `search`, `get_project`, `find_duplicates`, and `propose_batch` already cover the use cases. A future `find_untriaged(year, limit)` sugar tool can land alongside the schema migration if needed.

## Triage workflow

The expected interaction pattern in a Claude Code session:

1. User: *"Triage 50 untriaged projects from 2024."*
2. Claude: `search` filtered to year + no color tag.
3. For each result, Claude reads `get_project` (plugins, samples, track count, last-modified) and infers a likely state + content tags.
4. Claude calls `propose_batch` with `SetColorTag` + `SetTags` actions and a rationale.
5. User approves in the web UI; journal records the diff for undo.

## Out of scope

- Schema migration for `year_created` (separate plan).
- Any UI surface for the color legend (web UI work, separate plan).
- Auto-demotion of stale "in progress" projects to "has potential" (future tool).

## Plan to land

1. Write `docs/library-conventions.md` (canonical vocab).
2. Write this design doc.
3. Extend MCP `build_server()` `instructions=` to embed the conventions.
4. Commit as one logical change.
5. Write a follow-up plan for the `year_created` schema migration + backfill.
