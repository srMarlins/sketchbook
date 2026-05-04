# Project Dedup (v0.2) Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. The user has waived per-batch checkpoints — drive through all tasks back-to-back, only stopping for genuine blockers.

**Goal:** Surface byte-identical `.als` duplicates from the catalog and let the user archive losers via the existing proposal/approval flow, exposed through CLI and MCP.

**Architecture:** A new `audio_core.dedup` module groups projects by `file_hash`, picks a keeper per group (newest non-archived, shortest-path tiebreak), and builds a list of `ArchiveProject` proposal actions for the losers. CLI (`audio dedup`) and MCP (`find_duplicates` tool) are thin wrappers. No new write paths; everything goes through `run_batch` / the existing approval endpoint.

**Tech Stack:** Python 3.12, SQLite, Typer, Rich, FastMCP, pytest.

**Reference design:** `docs/plans/2026-05-04-project-dedup-design.md`.

**Existing pieces this leans on:**
- `packages/core/audio_core/actions/archive.py` — `ArchiveProject` action.
- `packages/core/audio_core/actions/runner.py` — `run_batch(conn, actions, actor, journal_dir)`.
- `packages/core/audio_core/db/projects.py` — `upsert_project` (used by tests for seeding).
- `packages/cli/audio_cli/main.py` — Typer app; mutating commands delegate to `_run(...)`.
- `packages/mcp/audio_mcp/main.py` — `build_server()` + `propose_batch` tool.
- Proposals: `packages/web/audio_web/routes_proposals.py` (approval lives in the web layer; not changed here).

---

## Task 1: Core `find_duplicates` — empty + no-dup cases

**Files:**
- Create: `packages/core/audio_core/dedup.py`
- Test: `packages/core/tests/test_dedup.py`

**Step 1: Write the failing tests**

```python
# packages/core/tests/test_dedup.py
import time

from audio_core.db.connection import open_db
from audio_core.db.projects import upsert_project
from audio_core.dedup import find_duplicates
from audio_core.parser.model import ProjectMetadata


def _seed(conn, *, path, file_hash, mtime, name=None, archived=False):
    pid = upsert_project(
        conn,
        path=path,
        name=name or path.rsplit("/", 1)[-1].removesuffix(".als"),
        parent_dir=path.rsplit("/", 1)[0],
        file_hash=file_hash,
        last_modified=mtime,
        meta=ProjectMetadata(),
    )
    if archived:
        conn.execute("UPDATE projects SET is_archived=1 WHERE id=?", (pid,))
        conn.commit()
    return pid


def test_empty_db(tmp_path):
    conn = open_db(tmp_path / "c.db")
    assert find_duplicates(conn) == []


def test_no_duplicates(tmp_path):
    conn = open_db(tmp_path / "c.db")
    _seed(conn, path="/a/x.als", file_hash="h1", mtime=time.time())
    _seed(conn, path="/b/y.als", file_hash="h2", mtime=time.time())
    assert find_duplicates(conn) == []
```

**Step 2: Run tests to verify they fail**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_dedup.py -v`
Expected: FAIL — `ImportError: cannot import name 'find_duplicates'`.

**Step 3: Write minimal implementation**

```python
# packages/core/audio_core/dedup.py
from __future__ import annotations

import sqlite3
from dataclasses import dataclass


@dataclass(frozen=True)
class DupGroup:
    file_hash: str
    keeper: dict
    losers: list[dict]


def find_duplicates(conn: sqlite3.Connection) -> list[DupGroup]:
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
        """
        SELECT * FROM projects
        WHERE file_hash IS NOT NULL
          AND file_hash IN (
            SELECT file_hash FROM projects
            WHERE file_hash IS NOT NULL
            GROUP BY file_hash HAVING COUNT(*) > 1
          )
        """
    ).fetchall()
    if not rows:
        return []
    by_hash: dict[str, list[dict]] = {}
    for r in rows:
        by_hash.setdefault(r["file_hash"], []).append(dict(r))
    groups: list[DupGroup] = []
    for h, members in by_hash.items():
        members.sort(key=_keeper_key)
        groups.append(DupGroup(file_hash=h, keeper=members[0], losers=members[1:]))
    return groups


def _keeper_key(row: dict) -> tuple:
    # Sort ascending; first row wins. Live before archived; newest mtime wins;
    # then shortest path; then lexicographic path for full determinism.
    return (
        1 if row.get("is_archived") else 0,
        -(row.get("last_modified") or 0.0),
        len(row["path"]),
        row["path"],
    )
```

**Step 4: Run tests to verify they pass**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_dedup.py -v`
Expected: 2 passed.

**Step 5: Commit**

```bash
git add packages/core/audio_core/dedup.py packages/core/tests/test_dedup.py
git commit -m "feat(core): scaffold dedup.find_duplicates with keeper-key ordering"
```

---

## Task 2: Core `find_duplicates` — keeper rule fully covered

**Files:**
- Modify: `packages/core/tests/test_dedup.py`

**Step 1: Add the rule-coverage tests**

```python
def test_two_way_dup_picks_newest_mtime(tmp_path):
    conn = open_db(tmp_path / "c.db")
    older = _seed(conn, path="/a/x.als", file_hash="h", mtime=1000.0)
    newer = _seed(conn, path="/b/x.als", file_hash="h", mtime=2000.0)
    groups = find_duplicates(conn)
    assert len(groups) == 1
    assert groups[0].keeper["id"] == newer
    assert [l["id"] for l in groups[0].losers] == [older]


def test_mtime_tie_picks_shortest_path(tmp_path):
    conn = open_db(tmp_path / "c.db")
    long_id = _seed(conn, path="/a/very/long/path/x.als", file_hash="h", mtime=1000.0)
    short_id = _seed(conn, path="/b/x.als", file_hash="h", mtime=1000.0)
    groups = find_duplicates(conn)
    assert groups[0].keeper["id"] == short_id
    assert [l["id"] for l in groups[0].losers] == [long_id]


def test_archived_never_chosen_as_keeper(tmp_path):
    conn = open_db(tmp_path / "c.db")
    arch_newer = _seed(conn, path="/arch/x.als", file_hash="h", mtime=2000.0, archived=True)
    live_older = _seed(conn, path="/live/x.als", file_hash="h", mtime=1000.0)
    groups = find_duplicates(conn)
    assert groups[0].keeper["id"] == live_older
    assert [l["id"] for l in groups[0].losers] == [arch_newer]


def test_all_archived_group_still_reported(tmp_path):
    conn = open_db(tmp_path / "c.db")
    a = _seed(conn, path="/arch1/x.als", file_hash="h", mtime=2000.0, archived=True)
    b = _seed(conn, path="/arch2/x.als", file_hash="h", mtime=1000.0, archived=True)
    groups = find_duplicates(conn)
    assert len(groups) == 1
    assert {groups[0].keeper["id"], *(l["id"] for l in groups[0].losers)} == {a, b}


def test_null_file_hash_excluded(tmp_path):
    conn = open_db(tmp_path / "c.db")
    _seed(conn, path="/a/x.als", file_hash="h", mtime=1000.0)
    _seed(conn, path="/b/x.als", file_hash="h", mtime=1000.0)
    # A NULL-hash row that would otherwise look like a sibling is ignored.
    pid = upsert_project(
        conn, path="/c/x.als", name="x", parent_dir="/c",
        file_hash=None, last_modified=1000.0, meta=ProjectMetadata(),
    )
    assert pid is not None
    groups = find_duplicates(conn)
    assert len(groups) == 1
    assert all(m["file_hash"] == "h" for m in [groups[0].keeper, *groups[0].losers])
```

**Step 2: Run tests to verify behavior**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_dedup.py -v`
Expected: All pass on the existing implementation. If `test_null_file_hash_excluded` fails because `upsert_project` rejects `file_hash=None`, fall through to Step 3.

**Step 3 (only if needed): Loosen `upsert_project` typing**

If `upsert_project` requires `file_hash: str` and `None` raises a type error, change its signature to `file_hash: str | None`. The SQL accepts NULL; only the Python type is restrictive. No schema change needed.

**Step 4: Commit**

```bash
git add packages/core/tests/test_dedup.py packages/core/audio_core/db/projects.py
git commit -m "test(core): cover dedup keeper rule (mtime, path, archived, NULL hash)"
```

---

## Task 3: Core `build_archive_proposal`

**Files:**
- Modify: `packages/core/audio_core/dedup.py`
- Modify: `packages/core/tests/test_dedup.py`

**Step 1: Write the failing test**

```python
def test_build_archive_proposal_one_action_per_loser(tmp_path):
    from audio_core.dedup import build_archive_proposal
    conn = open_db(tmp_path / "c.db")
    keeper = _seed(conn, path="/k/x.als", file_hash="h", mtime=2000.0)
    l1 = _seed(conn, path="/a/x.als", file_hash="h", mtime=1000.0)
    l2 = _seed(conn, path="/b/x.als", file_hash="h", mtime=900.0)
    actions = build_archive_proposal(find_duplicates(conn))
    assert {a["args"]["project_id"] for a in actions} == {l1, l2}
    assert all(a["type"] == "ArchiveProject" for a in actions)
    assert keeper not in [a["args"]["project_id"] for a in actions]


def test_build_archive_proposal_skips_all_archived_groups(tmp_path):
    from audio_core.dedup import build_archive_proposal
    conn = open_db(tmp_path / "c.db")
    _seed(conn, path="/a/x.als", file_hash="h", mtime=2000.0, archived=True)
    _seed(conn, path="/b/x.als", file_hash="h", mtime=1000.0, archived=True)
    assert build_archive_proposal(find_duplicates(conn)) == []
```

**Step 2: Run tests to verify they fail**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_dedup.py -v`
Expected: ImportError on `build_archive_proposal`.

**Step 3: Implement**

Append to `packages/core/audio_core/dedup.py`:

```python
def build_archive_proposal(groups: list[DupGroup]) -> list[dict]:
    """Convert dup groups into ArchiveProject proposal actions for each loser.
    Groups whose keeper is already archived (i.e. all members archived) contribute
    zero actions — there's nothing to clean up."""
    out: list[dict] = []
    for g in groups:
        if g.keeper.get("is_archived"):
            continue
        for loser in g.losers:
            if loser.get("is_archived"):
                continue
            out.append({"type": "ArchiveProject", "args": {"project_id": loser["id"]}})
    return out
```

**Step 4: Run tests to verify they pass**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_dedup.py -v`
Expected: all pass.

**Step 5: Commit**

```bash
git add packages/core/audio_core/dedup.py packages/core/tests/test_dedup.py
git commit -m "feat(core): build_archive_proposal turns DupGroups into ArchiveProject actions"
```

---

## Task 4: Core round-trip — proposal actually archives losers

**Files:**
- Modify: `packages/core/tests/test_dedup.py`

**Step 1: Write the failing test**

```python
def test_round_trip_proposal_archives_losers(tmp_path):
    """End-to-end at the core layer: build_archive_proposal -> instantiate
    ArchiveProject -> run_batch. Losers should end up with is_archived=1."""
    import shutil
    from pathlib import Path

    from audio_core.actions.archive import ArchiveProject
    from audio_core.actions.runner import run_batch
    from audio_core.scanner.scan import scan_one

    fixtures = Path(__file__).parent / "fixtures"
    root = tmp_path / "root"
    (root / "Projects" / "keep Project").mkdir(parents=True)
    (root / "Projects" / "drop Project").mkdir(parents=True)
    shutil.copy(fixtures / "tiny.als", root / "Projects" / "keep Project" / "x.als")
    shutil.copy(fixtures / "tiny.als", root / "Projects" / "drop Project" / "x.als")

    conn = open_db(root / "data" / "catalog.db")
    keep_id = scan_one(conn, root / "Projects" / "keep Project" / "x.als")
    drop_id = scan_one(conn, root / "Projects" / "drop Project" / "x.als")
    # Force keeper to win: bump its last_modified.
    conn.execute(
        "UPDATE projects SET last_modified=? WHERE id=?", (9_999_999_999.0, keep_id)
    )
    conn.commit()

    actions_json = build_archive_proposal(find_duplicates(conn))
    assert {a["args"]["project_id"] for a in actions_json} == {drop_id}

    actions = [
        ArchiveProject(project_id=a["args"]["project_id"], root=root / "Projects")
        for a in actions_json
    ]
    run_batch(conn, actions, actor="test", journal_dir=root / "data" / "journal")

    flag = lambda pid: conn.execute(
        "SELECT is_archived FROM projects WHERE id=?", (pid,)
    ).fetchone()[0]
    assert flag(drop_id) == 1
    assert flag(keep_id) == 0
```

**Step 2: Run test to verify it fails**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_dedup.py::test_round_trip_proposal_archives_losers -v`
Expected: should already pass (no new code needed) — this test exists to lock the contract. If it fails, fix whatever it surfaces (likely an import issue or a fixture path).

**Step 3: Commit**

```bash
git add packages/core/tests/test_dedup.py
git commit -m "test(core): round-trip dedup proposal through run_batch archives losers"
```

---

## Task 5: `ArchiveProject` idempotency on already-archived rows

**Files:**
- Modify: `packages/core/tests/test_actions_archive.py` (or create if missing — search first)
- Modify (only if needed): `packages/core/audio_core/actions/archive.py`

**Step 1: Locate or create the archive test file**

Run: `Glob "packages/core/tests/test_actions_archive*"`. If a file exists, append to it; otherwise create `packages/core/tests/test_actions_archive_idempotent.py`.

**Step 2: Write the failing test**

```python
def test_archive_already_archived_is_safe(tmp_path):
    """Re-archiving a row that's already is_archived=1 must not raise.
    Dedup proposals can race with manual archives; the runner must tolerate it."""
    import shutil
    from pathlib import Path
    from audio_core.actions.archive import ArchiveProject
    from audio_core.actions.runner import run_batch
    from audio_core.db.connection import open_db
    from audio_core.scanner.scan import scan_one

    fixtures = Path(__file__).parent / "fixtures"
    root = tmp_path / "root"
    (root / "Projects" / "p Project").mkdir(parents=True)
    shutil.copy(fixtures / "tiny.als", root / "Projects" / "p Project" / "x.als")
    conn = open_db(root / "data" / "catalog.db")
    pid = scan_one(conn, root / "Projects" / "p Project" / "x.als")
    run_batch(
        conn,
        [ArchiveProject(project_id=pid, root=root / "Projects")],
        actor="test",
        journal_dir=root / "data" / "journal",
    )
    # Second archive is a no-op, not a crash.
    run_batch(
        conn,
        [ArchiveProject(project_id=pid, root=root / "Projects")],
        actor="test",
        journal_dir=root / "data" / "journal",
    )
    assert conn.execute(
        "SELECT is_archived FROM projects WHERE id=?", (pid,)
    ).fetchone()[0] == 1
```

**Step 3: Run test to verify it fails**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_actions_archive_idempotent.py -v`
Expected: likely fails — the second `validate()` will see the row is already inside `_Archive/_Archive/p Project` and either raise `FileExistsError` on the new target or `LookupError` on the old `parent_dir`.

**Step 4: Make `ArchiveProject` idempotent**

Modify `validate` and `execute` in `packages/core/audio_core/actions/archive.py`:

```python
def validate(self, conn: sqlite3.Connection) -> None:
    row = self._row(conn)
    if row["is_archived"]:
        return  # idempotent: already archived, execute() will be a no-op
    old_dir = Path(row["parent_dir"])
    ensure_within(old_dir, self.root)
    archive = self.root / ARCHIVE_DIR_NAME
    target = archive / old_dir.name
    if target.exists():
        raise FileExistsError(target)
    if is_open_in_live(row["path"]):
        raise RuntimeError(f"Live has {row['path']} open; close it first")

def execute(self, conn: sqlite3.Connection) -> dict:
    row = self._row(conn)
    if row["is_archived"]:
        # No filesystem move; record a noop entry so the journal stays linear.
        return {
            "type": "ArchiveProject",
            "project_id": self.project_id,
            "from_": row["parent_dir"],
            "to": row["parent_dir"],
            "hash_before": row["file_hash"],
            "noop": True,
        }
    # ... rest unchanged ...
```

Also update `audio_core/actions/undo.py` if it has an `ArchiveProject` reverse handler that would try to "un-archive" a noop entry. Check it; if it does a filesystem move based on `from_ != to`, the noop case (`from_ == to`) naturally skips it; otherwise gate on `entry.get("noop")`.

**Step 5: Run test to verify it passes**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_actions_archive_idempotent.py -v`
Expected: pass.

Run the full action test suite to make sure nothing regressed:
Run: `uv run --project Z:/User/audio pytest packages/core/tests/ -v -k archive`
Expected: all pass.

**Step 6: Commit**

```bash
git add packages/core/audio_core/actions/archive.py packages/core/audio_core/actions/undo.py packages/core/tests/test_actions_archive_idempotent.py
git commit -m "fix(core): ArchiveProject is idempotent on already-archived rows"
```

---

## Task 6: CLI `audio dedup` — read-only listing

**Files:**
- Modify: `packages/cli/audio_cli/main.py`
- Test: `packages/cli/tests/test_cli_dedup.py`

**Step 1: Write the failing test**

```python
# packages/cli/tests/test_cli_dedup.py
import json
import time

from audio_cli.main import app
from audio_core.db.connection import open_db
from audio_core.db.projects import upsert_project
from audio_core.parser.model import ProjectMetadata
from typer.testing import CliRunner


def _seed_dup(conn, *, path, file_hash, mtime):
    return upsert_project(
        conn, path=path, name=path.rsplit("/", 1)[-1].removesuffix(".als"),
        parent_dir=path.rsplit("/", 1)[0], file_hash=file_hash, last_modified=mtime,
        meta=ProjectMetadata(),
    )


def test_dedup_no_dups(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    open_db(tmp_path / "data" / "catalog.db")  # create empty DB
    result = CliRunner().invoke(app, ["dedup"])
    assert result.exit_code == 0
    assert "No duplicates" in result.stdout


def test_dedup_lists_groups_human(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    keeper = _seed_dup(conn, path="/short.als", file_hash="abc123", mtime=2000.0)
    loser = _seed_dup(conn, path="/much/longer/path/x.als", file_hash="abc123", mtime=1000.0)
    result = CliRunner().invoke(app, ["dedup"])
    assert result.exit_code == 0
    assert "abc123" in result.stdout
    assert "KEEP" in result.stdout
    assert str(keeper) in result.stdout
    assert str(loser) in result.stdout


def test_dedup_json_output(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    _seed_dup(conn, path="/a.als", file_hash="h", mtime=2000.0)
    _seed_dup(conn, path="/b.als", file_hash="h", mtime=1000.0)
    result = CliRunner().invoke(app, ["dedup", "--json"])
    assert result.exit_code == 0
    payload = json.loads(result.stdout)
    assert len(payload) == 1
    assert payload[0]["file_hash"] == "h"
    assert "keeper" in payload[0] and "losers" in payload[0]
```

**Step 2: Run tests to verify they fail**

Run: `uv run --project Z:/User/audio pytest packages/cli/tests/test_cli_dedup.py -v`
Expected: FAIL — `No such command 'dedup'`.

**Step 3: Add the command to `audio_cli/main.py`**

Add this import next to the others:

```python
import json as _json
from audio_core.dedup import find_duplicates, build_archive_proposal
```

And this command, placed near the other read-only commands (after `show`):

```python
@app.command()
def dedup(
    json_out: bool = typer.Option(False, "--json", help="Emit machine-readable JSON."),
    propose: bool = typer.Option(False, "--propose", help="Write a proposal JSON for the losers."),
) -> None:
    """List byte-identical .als duplicate groups in the catalog.

    --propose writes a proposal JSON (one ArchiveProject action per loser) to the
    proposals dir; approve it via the web UI to actually archive the losers.
    """
    conn = open_db(db_path())
    groups = find_duplicates(conn)
    if propose:
        # Filled in next task; placeholder so the flag exists.
        raise NotImplementedError
    if json_out:
        con.print_json(
            data=[
                {
                    "file_hash": g.file_hash,
                    "keeper": _row_summary(g.keeper),
                    "losers": [_row_summary(l) for l in g.losers],
                }
                for g in groups
            ]
        )
        return
    if not groups:
        con.print("No duplicates found.")
        return
    for g in groups:
        n = 1 + len(g.losers)
        con.print(f"\n[bold]hash[/bold]  {g.file_hash}  ({n} copies)")
        con.print(f"  [green]KEEP[/green]  id={g.keeper['id']}  {g.keeper['path']}")
        for loser in g.losers:
            con.print(f"  [red]drop[/red]  id={loser['id']}  {loser['path']}")


def _row_summary(row: dict) -> dict:
    return {
        "id": row["id"],
        "name": row["name"],
        "path": row["path"],
        "last_modified": row["last_modified"],
        "is_archived": bool(row["is_archived"]),
    }
```

Note: use `con.print_json` (Rich) so output is parseable JSON in `--json` mode.

**Step 4: Run tests to verify they pass**

Run: `uv run --project Z:/User/audio pytest packages/cli/tests/test_cli_dedup.py::test_dedup_no_dups packages/cli/tests/test_cli_dedup.py::test_dedup_lists_groups_human packages/cli/tests/test_cli_dedup.py::test_dedup_json_output -v`
Expected: 3 passed.

**Step 5: Commit**

```bash
git add packages/cli/audio_cli/main.py packages/cli/tests/test_cli_dedup.py
git commit -m "feat(cli): audio dedup lists duplicate groups (human + --json)"
```

---

## Task 7: CLI `audio dedup --propose`

**Files:**
- Modify: `packages/cli/audio_cli/main.py`
- Modify: `packages/cli/tests/test_cli_dedup.py`

**Step 1: Write the failing test**

```python
def test_dedup_propose_writes_proposal_file(tmp_path, monkeypatch):
    import json as _json
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    keeper = _seed_dup(conn, path="/k.als", file_hash="h", mtime=2000.0)
    loser = _seed_dup(conn, path="/l.als", file_hash="h", mtime=1000.0)
    result = CliRunner().invoke(app, ["dedup", "--propose"])
    assert result.exit_code == 0
    # The CLI prints the proposal id; one JSON file should now exist.
    proposals = list((tmp_path / "data" / "proposals").glob("*.json"))
    assert len(proposals) == 1
    payload = _json.loads(proposals[0].read_text(encoding="utf-8"))
    assert payload["actor"] == "cli"
    pids = {a["args"]["project_id"] for a in payload["actions"]}
    assert pids == {loser}
    assert keeper not in pids
    assert payload["proposal_id"] in result.stdout
```

**Step 2: Run test to verify it fails**

Run: `uv run --project Z:/User/audio pytest packages/cli/tests/test_cli_dedup.py::test_dedup_propose_writes_proposal_file -v`
Expected: FAIL — `NotImplementedError`.

**Step 3: Implement `--propose`**

Replace the `if propose: raise NotImplementedError` block in `dedup`:

```python
    if propose:
        actions = build_archive_proposal(groups)
        if not actions:
            con.print("No actionable losers. Nothing to propose.")
            raise typer.Exit(code=0)
        from datetime import UTC, datetime
        from uuid import uuid4
        from audio_cli.config import proposals_dir
        d = proposals_dir()
        d.mkdir(parents=True, exist_ok=True)
        pid = f"{datetime.now(UTC).strftime('%Y-%m-%dT%H-%M-%S')}_{uuid4().hex[:8]}"
        payload = {
            "proposal_id": pid,
            "actor": "cli",
            "actions": actions,
            "rationale": f"audio dedup: {len(groups)} group(s), {len(actions)} loser(s)",
        }
        (d / f"{pid}.json").write_text(_json.dumps(payload, indent=2), encoding="utf-8")
        con.print(f"proposal {pid} written ({len(actions)} action(s))")
        return
```

If `audio_cli.config` doesn't expose `proposals_dir`, import from `audio_core.config` instead — match whichever the rest of the codebase uses (check with `Grep "proposals_dir" packages/`).

**Step 4: Run test to verify it passes**

Run: `uv run --project Z:/User/audio pytest packages/cli/tests/test_cli_dedup.py -v`
Expected: 4 passed.

**Step 5: Commit**

```bash
git add packages/cli/audio_cli/main.py packages/cli/tests/test_cli_dedup.py
git commit -m "feat(cli): audio dedup --propose writes ArchiveProject batch to proposals dir"
```

---

## Task 8: MCP `find_duplicates` tool

**Files:**
- Modify: `packages/mcp/audio_mcp/main.py`
- Modify: `packages/mcp/tests/test_mcp_tools.py`

**Step 1: Write the failing test**

Append to `packages/mcp/tests/test_mcp_tools.py`:

```python
def test_find_duplicates_tool_returns_groups(tmp_path, monkeypatch):
    import time
    from audio_core.db.connection import open_db
    from audio_core.db.projects import upsert_project
    from audio_core.parser.model import ProjectMetadata
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    keep = upsert_project(
        conn, path="/k.als", name="k", parent_dir="/",
        file_hash="abc", last_modified=2000.0, meta=ProjectMetadata(),
    )
    drop = upsert_project(
        conn, path="/d.als", name="d", parent_dir="/",
        file_hash="abc", last_modified=1000.0, meta=ProjectMetadata(),
    )
    server = build_server()

    async def go():
        async with Client(server) as c:
            res = await c.call_tool("find_duplicates", {})
            data = res.data
            assert len(data) == 1
            assert data[0]["file_hash"] == "abc"
            assert data[0]["keeper"]["id"] == keep
            assert [l["id"] for l in data[0]["losers"]] == [drop]

    asyncio.run(go())
```

**Step 2: Run test to verify it fails**

Run: `uv run --project Z:/User/audio pytest packages/mcp/tests/test_mcp_tools.py::test_find_duplicates_tool_returns_groups -v`
Expected: FAIL — `Tool 'find_duplicates' not found`.

**Step 3: Add the tool**

In `packages/mcp/audio_mcp/main.py`, inside `build_server()`:

```python
from audio_core.dedup import find_duplicates as _find_duplicates  # at the top of the file

@mcp.tool
def find_duplicates(limit: int = 100) -> list[dict]:
    """Find .als files in the catalog that are byte-identical (same blake3 hash).

    Returns groups, each with a recommended keeper (newest non-archived mtime,
    shortest-path tiebreak) and a list of losers. To clean them up, call
    `propose_batch` with one `ArchiveProject` action per loser id.
    """
    conn = open_db(db_path())
    groups = _find_duplicates(conn)[:limit]
    return [
        {
            "file_hash": g.file_hash,
            "keeper": _summary(g.keeper),
            "losers": [_summary(l) for l in g.losers],
        }
        for g in groups
    ]


def _summary(row: dict) -> dict:
    return {
        "id": row["id"],
        "name": row["name"],
        "path": row["path"],
        "last_modified": row["last_modified"],
        "is_archived": bool(row["is_archived"]),
    }
```

**Step 4: Run test to verify it passes**

Run: `uv run --project Z:/User/audio pytest packages/mcp/tests/test_mcp_tools.py -v`
Expected: all pass (existing 4 + 1 new = 5).

**Step 5: Commit**

```bash
git add packages/mcp/audio_mcp/main.py packages/mcp/tests/test_mcp_tools.py
git commit -m "feat(mcp): find_duplicates tool returns dup groups with keeper/losers"
```

---

## Task 9: Update MCP setup docs

**Files:**
- Modify: `docs/mcp-setup.md`

**Step 1: Add `find_duplicates` to the tool list**

Find the tool bullet list near the top of `docs/mcp-setup.md` and add a fourth bullet:

```markdown
- `find_duplicates(limit)` — list byte-identical .als groups with a recommended keeper per group; pair with `propose_batch` to archive losers.
```

**Step 2: Commit**

```bash
git add docs/mcp-setup.md
git commit -m "docs(mcp): document find_duplicates tool"
```

---

## Task 10: Full test sweep

**Step 1: Run every package's tests**

Run: `uv run --project Z:/User/audio pytest packages/core packages/cli packages/mcp packages/web -q`
Expected: all pass; ~150+ tests.

If anything fails that wasn't touched, investigate before committing further.

**Step 2: Commit (only if anything was fixed)**

If a regression was introduced and fixed:

```bash
git add -A
git commit -m "test: fix regressions surfaced during dedup full-suite sweep"
```

Otherwise skip.

---

## Task 11: E2E smoke against the real catalog

This is manual verification, not automated.

**Step 1: List dups in the live catalog**

Run: `uv run --project Z:/User/audio audio dedup`
Expected: at least one group containing the two `maria.als` copies (`2024/...` and `finish_these_tracks/...`). Confirm `KEEP` is the `2024/` copy.

If the keeper is wrong, stop and inspect — likely the mtime sort is flipped, or `is_archived` is set on the row that should be the keeper.

**Step 2: Write a proposal**

Run: `uv run --project Z:/User/audio audio dedup --propose`
Expected: prints a `proposal_id`. A JSON file appears under `data/proposals/`.

Inspect: `Glob "data/proposals/*.json"`, read the latest. Verify each `ArchiveProject` action targets a loser id (cross-check against `audio dedup` output).

**Step 3: Approve via the web API**

The CLI doesn't have an `approve` command. Use the web layer:

```bash
# Start the web server in another shell:
uv run --project Z:/User/audio uvicorn audio_web.app:create_app --factory --port 8000

# Approve:
curl -X POST http://localhost:8000/api/proposals/<proposal_id>/approve
```

Expected: 200 OK with a `batch_id` in the response. The losers should now show `is_archived=1`.

Verify: `uv run --project Z:/User/audio audio search --archived` should list them.

**Step 4: Undo**

Run: `uv run --project Z:/User/audio audio undo <batch_id>`
Expected: losers move back to their original parents and `is_archived=0`.

Verify: `uv run --project Z:/User/audio audio dedup` again — group should reappear unchanged.

**Step 5: Done — no commit needed (manual test)**

---

## Notes

- **No `audio approve` CLI command** exists today (despite a passing reference in `docs/mcp-setup.md`). E2E approval goes through the web layer. If the user wants a CLI `approve` later, it's a separate feature.
- **`con.print_json`** comes from Rich's Console; it's already used elsewhere in this codebase. If `--json` output looks pretty-printed in the terminal but parses as JSON when piped, that's expected.
- **`AUDIO_ROOT` env var** is what `db_path()` / `proposals_dir()` resolve from. Tests set it via `monkeypatch.setenv`; same pattern as the existing MCP tests.
- **The `audio_cli.config` vs `audio_core.config` split** — the cli has its own thin module that re-exports from core. Match whichever the existing CLI commands use; don't refactor as part of this work.
