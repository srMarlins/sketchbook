# Library Conventions

Canonical vocabulary for organizing the Ableton project library. This is the single source of truth referenced by the MCP server (`shared/mcp-server/`), the desktop app's color/tag legends, and any AI-driven triage workflow.

## Folder structure

```
Projects/
  2026/
  2025/
  2024/
  2023/
  ...
  _archive/
```

- One level deep. The folder is the **year the project was created** and never changes once set.
- A 2023 sketch finished in 2026 stays in `2023/` — origin year wins.
- `_archive/` holds projects with `is_archived=1`; managed via `ArchiveProject` action, not browsed manually.

## State — color tag

The Ableton color tag (`SetColorTag` action) carries the project's lifecycle state. Visible inside Ableton itself and in the catalog UI.

| Color  | State                  | Meaning                                                |
|--------|------------------------|--------------------------------------------------------|
| green  | done                   | Finished, mixed, mastered, ready or released.          |
| yellow | needs mix/master       | Arrangement complete; only mixing/mastering remains.   |
| orange | almost done            | Most of the work is in; small gaps to close.           |
| blue   | in progress            | Actively being worked on right now.                    |
| purple | has potential          | Stalled but worth revisiting.                          |
| red    | dead                   | Candidate for archive; no intent to revisit.           |
| gray / none | untriaged         | Not yet evaluated.                                     |

Every project has exactly one color (or none = untriaged).

## Content — free-form tags

Free-form tags (`SetTags` action) describe what a project *is*, independent of state. Multiple tags per project; lowercase, hyphenated.

**Type** (pick one):
- `full-track` — a complete song or song-in-progress
- `sketch` — a short idea, not yet song-shaped
- `melody-loop`, `drum-loop`, `bass-loop`, `vocal-chop` — loop-only projects
- `remix` — built on someone else's stems
- `collab` — co-authored

**Genre / vibe** (pick zero or more):
- `house`, `techno`, `ambient`, `lofi`, `hip-hop`, `dnb`, `trap`, `pop`, `experimental`

**Faceted tags** (`key:value` form, pick zero or more):
- `key:Cm`, `key:F#`, etc. — musical key
- `bpm:140` — only if not already captured by the parsed tempo column
- `client:x` — work-for-hire context

Avoid duplicating information the catalog already extracts (tempo, time signature, plugins, sample list). Tags are for things the parser can't infer.

## Triage workflow

1. Filter: untriaged + a year bucket (e.g. `color IS NULL AND year_created = 2024`).
2. For each project, the AI inspects metadata (`get_project`) — track count, plugin list, sample list, last-modified.
3. AI proposes a color + tag set per project via `propose_batch`.
4. User approves in the web UI; the journal records every change.
