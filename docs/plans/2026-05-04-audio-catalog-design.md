# Audio: Ableton catalog & organizer — design

**Date:** 2026-05-04
**Owner:** srmarlins@gmail.com
**Status:** Approved, ready for implementation plan

## 1. Scope & non-goals

**v0.1 ships:** A read-and-write catalog over `Z:\User\audio\Projects`. The user can search, browse, open, rename, retag, recolor, move, and archive any of ~1,628 Ableton projects from a web UI or CLI; Claude can do the same via MCP. Every write is reversible via an undo journal.

**v0.1 does NOT include:** sample dedup, audio analysis (LUFS/spectral/reference-match), AbletonOSC live integration, MIDI clip extraction, AI auto-rename of garbage-named projects. All deferred to later milestones.

**Success criterion:** "Where's that 140 BPM minor-key sketch from 2024 with the Diva preset?" answered in under 10 seconds, from a browser, without opening Live.

**Working library:** `Z:\User\audio\Projects` is a *copy* of the real library. Destructive testing is allowed; the path will be promoted to the real library after v0.1 stabilizes.

## 2. Architecture

```
Z:\User\audio\
  pyproject.toml                # uv workspace
  packages/
    core/                       # all logic, no I/O outside it
      parser/                   # .als → ProjectMetadata (gzip + lxml XPath)
      scanner/                  # walk Projects/, hash, upsert catalog
      db/                       # SQLite + FTS5 schema, queries
      actions/                  # MoveProject, RenameProject, ArchiveProject, ...
      journal/                  # undo manifest read/write
      safety/                   # path allowlist, "is .als open in Live?" check
    cli/                        # `audio scan`, `audio search`, `audio undo`  (Typer)
    web/                        # FastAPI + Vite/React/TanStack/Tailwind
    mcp/                        # FastMCP server: tools = actions/ + queries
  data/
    catalog.db                  # SQLite (FTS5 virtual table)
    proposals/                  # Claude-authored action batches (JSON)
    journal/                    # undo manifests, one file per batch
    cache/                      # rendered waveform thumbnails, preview audio
  Projects/                     # the test library
```

Every surface (CLI, web, MCP) is a thin wrapper. `core` has no Flask/Typer/FastMCP imports. Tests target `core` directly. Single Python package, single venv (managed by `uv`), single test suite.

**Library choices, locked:**
- `.als` parser: roll our own using `gzip` + `lxml`, learning XPaths from hodel33/ableton-project-processor. No hard dependency on an existing parser.
- DB: SQLite with FTS5 for search.
- Web stack: FastAPI backend, Vite + React + TanStack Table + Tailwind frontend, served as static files in prod.
- CLI: Typer.
- MCP: FastMCP.
- Audio (deferred): pyloudnorm, soundfile, librosa, matchering.
- Sample dedup (deferred): BLAKE3 content hash; optional Chromaprint perceptual pass.
- Live integration (deferred): AbletonOSC.

## 3. Data model

**`projects`** (one row per `.als`, primary): `id`, `path`, `name`, `parent_dir`, `tempo`, `time_sig`, `key`, `track_count`, `audio_track_count`, `midi_track_count`, `length_seconds`, `live_version`, `last_modified`, `last_scanned`, `file_hash`, `is_archived`, `color_tag`, `notes`.

**`project_plugins`** (many-to-one): `project_id`, `plugin_name`, `plugin_type` (vst/au/ableton-native), `track_name`.

**`project_samples`** (many-to-one): `project_id`, `sample_path`, `sample_hash` (lazy, populated by v0.2 dedup), `is_missing`.

**`project_tags`** + **`tags`**: many-to-many freeform tags + a special `archived` tag.

**`projects_fts`** (FTS5 virtual): name + parent_dir + plugin_names + sample_filenames + notes → one search index.

**.als extraction (v0.1 minimum):** tempo, time signature, track count by type, plugin names per track, sample references, last-saved Live version. XPaths over the gunzipped XML.

## 4. Actions & undo

Every write is an `Action` subclass with `validate()` → `execute()` → `to_journal_entry()`. No raw filesystem writes elsewhere.

**v0.1 actions:** `MoveProject`, `RenameProject` (renames folder + updates `.als` self-references if any), `SetColorTag`, `SetTags`, `ArchiveProject` (move to `Projects/_Archive/`), `Undo`.

**Safety primitives:**
- Path allowlist hardcoded to `Z:\User\audio\Projects` for v0.1. Action raises if `target` escapes.
- "Live has it open" check: scan running processes for `Ableton Live` + open file handles to the `.als`. Refuse if open.
- Pre-write hash check: re-hash `.als` before mutating; if it changed since last scan, abort and re-scan.

**Undo journal** (`data/journal/2026-05-04T14-22-31_batch-7.json`):
```json
{
  "batch_id": "...",
  "actor": "claude" | "user",
  "actions": [
    {"type": "RenameProject", "from": "...", "to": "...", "hash_before": "..."}
  ]
}
```
`audio undo <batch-id>` or `audio undo last` reverses the batch in reverse order.

## 5. AI integration

The MCP server exposes two tool categories:

- **Queries** (read-only, freely callable): `search_projects(query, filters)`, `get_project(id)`, `list_recent`, `find_similar` (v0.5).
- **Actions** (writes, gated): `propose_batch(actions[])` writes a JSON proposal to `data/proposals/`; user approves in the web UI; only then is it executed and journaled. Claude *cannot* execute writes directly — it can only propose.

This gives `terraform plan/apply` ergonomics: Claude proposes, user approves in bulk, journal records who did what.

## 6. Roadmap

- **v0.1:** scanner, parser, SQLite catalog, web UI, CLI, MCP, actions + undo journal, proposals queue. Localhost-only. Color tags mirror Ableton's 14-color palette.
- **v0.2:** sample dedup, missing-sample relink, orphan sample report, `Backup/` pruner.
- **v0.3:** preview audio rendered at scan time, parallelized (worker pool, ~30 sec bounce of master per project, cached); waveform thumbnails. Resumable scan.
- **v0.4:** audio analysis (pyloudnorm, librosa, matchering wrap), one-page mix report per rendered master.
- **v0.5:** cross-project intelligence (style fingerprint, "find similar projects" by chroma+MFCC).
- **v0.6:** AbletonOSC live integration (read open-session state, batch recolor/rename via OSC).
- **v0.7:** Templates. `audio template new <name> --from <project_id>`, `audio template spawn`. New `Templates/` root.
- **v0.8:** Plugin catalog & favorites. Scan VST3/VST2/Ableton User Library; tables for `plugins`, `plugin_usage_stats`, `plugin_favorites`. MCP: `find_unused_plugins`, `find_plugin_usage`.
- **v0.9:** Preset & rack library. Index `.adv`, `.adg`, `.fxp/.fxb`, `.vstpreset`. Same patterns: catalog, tag, favorite, dedup.
- **v1.0:** Sample library curator. Configurable second root (`SampleLibrary/`). Auto-tagging classifier; BPM/key detection; folder reorg proposals.
- **v1.1+:** MIDI clip extraction → searchable bank. Project-to-project diff. Release packager. "Why does this mix sound bad?" diagnostic.

## 7. Open questions (resolved)

- **LAN access?** → localhost only. FastAPI binds 127.0.0.1.
- **Preview audio at scan or lazy?** → at scan, parallelized. Default workers = CPU count - 1.
- **Color tags?** → mirror Ableton's 14-color palette. Free OSC sync in v0.6.

## 8. Configuration shape (foreshadowing later milestones)

`audio.config.toml`:
```toml
[roots]
projects  = "Z:/User/audio/Projects"
templates = "Z:/User/audio/Templates"                                            # v0.7
samples   = "Z:/User/audio/SampleLibrary"                                        # v1.0
presets   = "C:/Users/jtfow/Documents/Ableton/User Library/Presets"              # v0.9

[scan]
preview_audio   = true
preview_workers = 7
```

v0.1 reads only `roots.projects`; the schema is fixed up front so later milestones don't need a config migration.

## 9. Web UI design language (deferred to build phase)

The visual/interaction language is decided as the first task of v0.1 build, after the data layer is wireable. Constraints baked in now:
- Dense data table (1,628 rows) is the primary surface — TanStack Table virtualized rows.
- Dark mode default (this is a music-production tool).
- Color tags must render the Ableton palette truthfully.
- Proposals queue is a first-class screen, not a sidebar — it's how the AI loop closes.
- Single-pane focus per project (overview / tracks / samples / plugins / history) rather than nested modals.

A `docs/design-language.md` artifact will be written before the React app is scaffolded.
