# Project Dedup (v0.2) — Design

## Problem

The v0.1 scan finds the same `.als` file at multiple disk locations (e.g. `2024/maria Project/maria.als` and `finish_these_tracks/maria Project/maria.als`) and stores both as separate `projects` rows. The catalog already records a `blake3` `file_hash` per project, so byte-identical duplicates are already trivially detectable in SQL — but there is no surface for surfacing them or cleaning them up.

## Scope

In:
- Detect `.als` duplicates by `projects.file_hash`.
- Per group, pick a keeper with a deterministic rule.
- Generate a batch proposal that archives the losers via the existing `ArchiveProject` action.
- Expose via CLI (`audio dedup`) and MCP (`find_duplicates` tool).

Out (deferred):
- Sample-level dedup or sample rediscovery.
- Hardlinking, deletion, or merging projects.
- A web "Duplicates" page.
- Near-duplicate detection (similar but not identical hashes).

## Keeper rule

Within each `file_hash` group:

1. Prefer non-archived rows over archived rows.
2. Newest `last_modified` wins.
3. Tie-break: shortest `path` length.
4. Final tie-break: lexicographic `path`.

Rationale: the user reorganizes by hand and the most recently touched copy is the one they're working with. A copy still under `_Archive/` should not become a keeper that then archives live copies.

If no live (non-archived) copy exists, the group is reported but `build_archive_proposal` produces zero actions for it (nothing to propose; it's already clean).

## Core module

`packages/core/audio_core/dedup.py`:

```python
@dataclass(frozen=True)
class DupGroup:
    file_hash: str
    keeper: dict           # project row
    losers: list[dict]     # project rows, sorted same way as keeper rule

def find_duplicates(conn) -> list[DupGroup]: ...

def build_archive_proposal(groups: list[DupGroup]) -> list[dict]:
    # one ArchiveProject action per loser; same JSON shape as web/MCP proposals
    ...
```

`find_duplicates` excludes rows where `file_hash IS NULL` (e.g. failed scans).

## CLI

```
audio dedup                  # human-readable group listing
audio dedup --json           # machine-readable
audio dedup --propose        # write a proposal JSON; print proposal_id
```

No `--apply` flag. To execute, use the existing `audio approve <id>`. This preserves the "all writes go through approval" invariant.

Human-readable output, one block per group:

```
hash  abc123… (3 copies)
  KEEP  id=52  2026-04-12  Z:\...\2024\maria Project\maria.als
  drop  id=88  2026-01-03  Z:\...\finish_these_tracks\maria Project\maria.als
  drop  id=141 2025-09-21  Z:\...\_old\maria Project\maria.als
```

## MCP

New tool in `packages/mcp/audio_mcp/main.py`:

```python
@mcp.tool
def find_duplicates(limit: int = 100) -> list[dict]:
    """Find .als files that are byte-identical (same blake3 hash). Returns groups
    with a recommended keeper (newest mtime, shortest path tiebreak) and losers."""
```

No new write tool — Claude calls the existing `propose_batch` with `ArchiveProject` actions on the loser ids.

## Edge cases

- **NULL `file_hash`** — excluded from dedup output.
- **All copies archived** — group reported, proposal contains zero actions for it.
- **Pre-archived keeper candidate** — keeper rule re-picks the newest non-archived row.
- **Same path collision** — impossible (`projects.path` is `UNIQUE`); defensive `DISTINCT` on id anyway.
- **Stale `ArchiveProject` against already-archived project** — verify the action is idempotent; fix if not. Validation happens at approval time, so the runner will reject any other malformed action.
- **Mid-batch failure** — existing `run_batch` validates-all-then-executes-all in a single transaction; one failure rolls back. No new behavior.

## Tests

Core (`packages/core/tests/test_dedup.py`):
- empty DB, 3 unique, 2 same-hash, 3 same-hash with mtime tie, archive mix, all-archived
- `build_archive_proposal` round-trip: build → `run_batch` → losers `is_archived=1`
- `ArchiveProject` idempotency on already-archived row

CLI (`packages/cli/tests/test_cli_dedup.py`):
- no-dup output, 1-group output, `--json`, `--propose` writes file and prints id

MCP (`packages/mcp/tests/test_mcp_tools.py`):
- `test_find_duplicates_tool`: seed two same-hash rows, call tool, assert 1 group with right keeper

E2E (manual):
- run against the real 483-row catalog; confirm the maria.als group picks the 2024 copy as keeper
- approve the proposal; confirm losers archived; `audio undo <batch_id>` reverses

## Non-goals

This design intentionally does not introduce delete, hardlink, merge, near-dup detection, or any sample-level work. Each is a separate v0.x feature when the need surfaces.
