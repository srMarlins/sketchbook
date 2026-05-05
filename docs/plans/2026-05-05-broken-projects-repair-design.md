# Broken-Projects Repair — Design

**Status:** Locked 2026-05-05. Implementation plan to follow via `superpowers:writing-plans`.

**Goal:** Let the user fix broken projects (Mac-imports + missing samples) one-at-a-time from the project detail view, or in bulk from a new `/repair` route, using the existing proposals/journal pattern.

## Context

The catalog already detects broken projects (`projects.parse_failed`, `projects.is_missing`, per-sample `project_samples.is_missing`, plus `mac_paths_count` / `has_project_info` from the macpath plan). Repair tooling exists for Mac-imports only: `audio_core/macpath.py`, `actions/repair_mac_paths.py`, CLI `audio repair-mac-paths`, MCP `find_mac_imports`. There is **no UI for triggering repair** and **no relink-missing-samples action**. Today, repair is CLI-only — which contradicts the "app owns scan; DB is source of truth" feedback that says the Tauri app must run repair flows on launch with progress and live updates, not via terminal commands.

This design fills both gaps: a new action (`RelinkMissingSamples`) that mirrors `RepairMacPaths`, a sample-index extension to the existing indexer so the matcher has a corpus, and two UI surfaces that layer onto the existing stationery components without introducing new aesthetics.

## Architecture

Three layers, each mirrored on existing precedent:

1. **Core** — `RelinkMissingSamples` action (rewrites `<SampleRef>` paths, `.als.bak`, atomic replace, live-lock guard). New `samples` table populated by the indexer. New `audio_core/relink.py` exposing `find_missing_samples()` and `build_relink_proposal()`. New `audio_core/repair.py` thin facade that returns combined findings (Mac + samples) so the UI gets one query.

2. **Web API** — `GET /api/repair/findings` returns the combined per-project view. Existing `POST /api/proposals` already accepts arbitrary action types; no changes there. Existing `POST /api/proposals/{id}/approve` runs the batch through the journal.

3. **Web UI** — `<RepairPanel>` (a `<MarginStickyNote>`-styled section) inlined at the top of the project detail view when issues exist. New `/repair` route with two `<Shelf>` groups (Mac imports, Missing samples), checkbox selection, footer "Propose batch" → posts to `/api/proposals` → redirects to existing proposal-review page.

## Components

### `audio_core/db/schema.sql` — new `samples` table

Indexed independently of `project_samples` (which records what each project *expects*). `samples` records what's *actually on disk* in the search corpus.

```sql
CREATE TABLE IF NOT EXISTS samples (
  id           INTEGER PRIMARY KEY,
  path         TEXT NOT NULL UNIQUE,
  filename     TEXT NOT NULL,
  size_bytes   INTEGER NOT NULL,
  mtime        REAL NOT NULL,
  parent_dir   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_samples_filename_size ON samples(filename, size_bytes);
CREATE INDEX IF NOT EXISTS idx_samples_parent ON samples(parent_dir);
```

No hash column in v1 — filename + size is the conservative-match key. Hash can be added later if dedup or fuzzy match needs it.

### `audio_core/config.py` — `sample_roots`

Add a `sample_roots: list[Path]` field to the existing config, defaulting to empty. The project tree (`Z:\User\audio\Projects`) is always indexed; `sample_roots` are additive.

### Indexer extension (`audio_core/indexer/`)

The existing `discovery.py` walks `.als` files and queues scan jobs. Extend it:
- New `walk_samples(root)` generator yields audio-file paths (`.wav`, `.aif`, `.aiff`, `.flac`, `.mp3`, `.ogg`).
- New job type `IndexSampleJob(path)` upserts into `samples`.
- `driver.py` runs sample-walks across `Projects/` ∪ `sample_roots` after the .als pass on each indexer cycle.
- `watcher.py` registers handlers for audio file create/modify/delete on the same roots.
- The existing "indexer status" pill in the UI reflects combined progress; no new UI primitive.

### `RelinkMissingSamples` action

Same shape as `RepairMacPaths`:

```python
@dataclass
class RelinkMissingSamples:
    project_id: int
    relinks: list[Relink]   # [(old_path, new_path), ...]
    root: Path

    def validate(self, conn): ...   # ensure_within, is_open_in_live
    def execute(self, conn) -> dict: ...   # backup → rewrite → atomic replace → update project_samples
```

Rewrite scope: walk every `<SampleRef>` (and any `<FileRef>` inside one) where the resolved path matches `old_path`, replace `<Path Value="...">` with `new_path`, set `<HasRelativePath>` accordingly, and update `<RelativePath>`/`<RelativePathType>` to a project-relative form when possible. Backup convention: `<stem>.als.bak`, created only if missing. If a batch contains both `RepairMacPaths` and `RelinkMissingSamples` for the same project, the first action wins the backup; the second sees an existing `.als.bak` and skips creation. Undo restores from the single backup and rescans the project.

### `audio_core/relink.py`

```python
@dataclass(frozen=True)
class MissingSampleFinding:
    project_id: int
    project_path: str
    project_name: str
    missing_path: str
    candidates: list[SampleCandidate]   # ranked, may be empty
    auto_match: SampleCandidate | None  # only set when len(candidates)==1 and size matches

def find_missing_samples(conn) -> list[MissingSampleFinding]: ...
def build_relink_proposal(findings, picks: dict[str, str]) -> list[dict]: ...
```

Matcher (conservative):
- Query `samples` for rows with `filename = basename(missing_path)`.
- Filter to those whose `size_bytes` equals the size recorded in `project_samples` (we already capture this on scan; if not present, treat as "no auto-match").
- If exactly one row remains → `auto_match`. Otherwise candidates are returned ranked by (filename-exact, size-exact, mtime desc) for the user to pick from.

`build_relink_proposal(findings, picks)` accepts a `picks: dict[missing_path → new_path]` so the UI can submit a mix of auto-matches and user-picked candidates as one proposal.

### `audio_core/repair.py` — combined facade

```python
def get_repair_findings(conn) -> RepairFindings:
    return RepairFindings(
        mac_imports=find_mac_imports(conn),
        missing_samples=find_missing_samples(conn),
    )
```

Used by the new web route and by future MCP tooling.

### Web API — `GET /api/repair/findings`

New file `routes_repair.py` with one endpoint that returns the combined findings as a JSON shape ready for both UI surfaces. Mirrors the structure of `routes_home.py`. No new POST endpoints — proposal creation goes through the existing `POST /api/proposals` with `actions=[{type, args}]`.

### UI — per-project `<RepairPanel>` (inline `<MarginStickyNote>`)

Renders at the top of the project detail view *only when issues exist*. Three optional rows, each in the existing stationery vocabulary:

- **Mac paths** — chip showing count + "Repair Mac paths" action (single-action proposal).
- **Project Info missing** — chip + "Create Project Info folder" (folded into the Mac-path repair if it's also flagged).
- **Missing samples** — list of `<SongStrip>`-styled rows, one per missing path, each with either an auto-match badge (`✓ k_03.wav at /samples/.../k_03.wav`) or a "Pick candidate" button that opens a small candidate list. A footer "Propose repair (3)" creates the proposal and navigates to the existing proposal-review page.

No new component primitives. Reuses `MarginStickyNote`, `FilterChip`, `SongStrip`, existing chip styles.

### UI — `/repair` route (bulk)

Two `<Shelf>` groups, in this order:

1. **Mac imports — N projects** — `<SongStrip>` rows with a leading checkbox in the ruled margin. Row content: project name, parent dir, count of Mac paths.
2. **Missing samples — N projects** — same shape. Each row expands to show its missing-samples list with auto-match status. A row is "ready" (selectable for batch) only if every missing sample has either an auto-match or a user-picked candidate; otherwise the checkbox is disabled with a "Needs review" note.

Footer: "Propose batch (12 selected)" — POSTs one proposal containing the mix of `RepairMacPaths` and `RelinkMissingSamples` actions, redirects to the proposal page. The batch is reviewed/approved through the existing proposals flow; nothing new there.

## Data flow

1. Indexer walks `Projects/` + `sample_roots` → upserts `samples` rows; scans `.als` files → upserts `projects` and `project_samples` rows (existing).
2. UI loads `GET /api/repair/findings` → renders bulk view or per-project panel.
3. User selects rows / picks candidates → UI builds `actions=[...]` → `POST /api/proposals` (existing).
4. User reviews on existing proposal page → `POST /api/proposals/{id}/approve` runs the batch through the journal (existing).
5. Each action: validate (ensure_within + live-lock) → backup `.als` if no `.als.bak` → rewrite XML → atomic replace → update DB rows.
6. Undo: existing per-action handlers; for the new action, copy `.als.bak` back over `.als` and rescan.

## Error handling

- **Live has the file open** — action's `validate()` raises `RuntimeError`; the proposal-runner records the error per-action and continues (existing behavior). UI shows per-action status from the journal entry.
- **Backup already exists** — leave it; backup represents the pre-batch state, which is what we want.
- **Auto-match candidate file moved/deleted between indexing and approval** — action re-checks the candidate exists during `execute()`; if missing, raises and the per-action entry is logged as failed.
- **No candidates for a missing sample** — the row simply isn't selectable in bulk mode, and shows "no match found" in the per-project panel. User can still propose `RepairMacPaths` for the same project independently.
- **Rescan stale** — if the catalog hasn't seen a sample yet, it won't match. The "Refresh" affordance is implicit: the indexer is always running, and the indexer status pill tells the user when it's busy. No manual button.

## Testing

Mirror the macpath test pattern exactly:

- Schema migration test for `samples` table.
- Indexer test: walk a tmp tree with audio files; assert rows persisted; modify/delete events update the table.
- Matcher tests: zero candidates, one candidate (auto-match), multiple candidates (review required), filename match but wrong size (no auto-match).
- Action test: golden-path relink rewrites `<SampleRef>` correctly, leaves backup, updates `project_samples.is_missing`. Idempotency, live-lock refusal, ensure_within refusal.
- Undo test: action restores byte-identical `.als` from `.als.bak` and rescans.
- Combined-batch test: a project flagged with both Mac paths and missing samples produces one `.als.bak` shared by both actions; undo of the batch restores cleanly.
- Web API test: `GET /api/repair/findings` returns combined shape; `POST /api/proposals` accepts `RelinkMissingSamples` actions; existing approve flow runs them.
- UI test (RTL + MSW): `<RepairPanel>` renders for a broken project, hides for clean ones, builds correct proposal payload on click. `/repair` route renders both shelves, disables rows that need review, batch submission posts the right JSON.
- E2E smoke (TestClient): scan a tmp tree with one Mac-imported project + one missing-sample project → propose-approve via API → assert journal has the right entries and the .als files were rewritten.

## Out of scope

- Parse-failure recovery (corrupted gzip, malformed XML).
- Fuzzy / filename-only auto-relink (manual-pick only in v1).
- Backup cleanup command.
- Bulk move/archive of unfixable projects.
- Sample-content analysis (waveform similarity, BLAKE3 dedup).
- New aesthetic primitives — everything reuses existing stationery components.
