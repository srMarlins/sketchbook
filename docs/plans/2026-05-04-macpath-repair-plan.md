# Mac-Path Repair Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. The user has waived per-batch checkpoints — drive through all tasks back-to-back, only stopping for genuine blockers.

**Goal:** Detect projects originally saved on macOS (absolute `/Volumes/`, `/Users/`, `/Applications/` paths in `<FileRef>` nodes, and/or missing `Ableton Project Info/` folder) and repair them so Live opens them on Windows without freezing on the missing-files search and without losing samples.

**Architecture:** Detection runs during the existing scan and is persisted as two new columns on `projects` (`mac_paths_count`, `has_project_info`). A new `audio_core.macpath` module both scans-on-the-fly and reads cached counts. Repair is a new `RepairMacPaths` action that (1) writes a `.als.bak`, (2) strips Mac-prefix `<Path Value>` children from every `FileRef`, (3) drops `<OriginalFileRef>` wrappers, (4) creates `Ableton Project Info/` if missing, (5) atomically replaces the `.als`. Exposed via CLI (`audio repair-mac-paths`) and MCP (`find_mac_imports` tool) following the same proposals/journal flow as dedup.

**Tech Stack:** Python 3.12, lxml (already in deps), gzip, SQLite, Typer, Rich, FastMCP, pytest.

**Reference incident:** `kiki_remix_02.als` froze on open on Windows. Diagnosis: 1193 `<Path Value="/Volumes/...">` children, 715 `<OriginalFileRef>` wrappers pointing at Splice paths, missing `Ableton Project Info/` folder. Fix script `_fix_kiki.py` (project root) is the prototype this plan productizes.

**Existing pieces this leans on:**
- `packages/core/audio_core/parser/als.py` — streaming parser; will gain Mac-path counting.
- `packages/core/audio_core/parser/model.py` — `ProjectMetadata` dataclass; will gain `mac_paths_count: int = 0`.
- `packages/core/audio_core/db/projects.py` — `upsert_project`; will persist the new columns.
- `packages/core/audio_core/db/connection.py` — `open_db`; will run the migration.
- `packages/core/audio_core/scanner/scan.py` — already calls `parse_als` per file; just needs the new fields persisted.
- `packages/core/audio_core/actions/base.py` — `Action` protocol.
- `packages/core/audio_core/actions/runner.py` — `run_batch`.
- `packages/core/audio_core/safety/paths.py` — `ensure_within` (block path-escape repairs).
- `packages/core/audio_core/safety/live_lock.py` — `is_open_in_live` (refuse repair while Live has the file open).
- `packages/cli/audio_cli/main.py` — Typer app, mirrors the dedup command's `--json` / `--propose` shape.
- `packages/mcp/audio_mcp/main.py` — `build_server()` + `propose_batch` tool.
- The reference fix lives at `Z:/User/audio/_fix_kiki.py` (treat as a working spec, not as production code — re-implement cleanly inside `audio_core/macpath.py`).

---

## Task 1: Schema migration — add `mac_paths_count`, `has_project_info`

**Files:**
- Modify: `packages/core/audio_core/db/connection.py`
- Test: `packages/core/tests/test_db_migration_macpath.py`

**Step 1: Write the failing test**

```python
# packages/core/tests/test_db_migration_macpath.py
from audio_core.db.connection import open_db


def test_macpath_columns_present(tmp_path):
    conn = open_db(tmp_path / "c.db")
    cols = {r[1] for r in conn.execute("PRAGMA table_info(projects)").fetchall()}
    assert "mac_paths_count" in cols
    assert "has_project_info" in cols


def test_migration_is_idempotent(tmp_path):
    db = tmp_path / "c.db"
    open_db(db).close()
    # Second open must not raise on duplicate-column ALTER.
    conn = open_db(db)
    cols = {r[1] for r in conn.execute("PRAGMA table_info(projects)").fetchall()}
    assert "mac_paths_count" in cols and "has_project_info" in cols


def test_existing_rows_get_null_defaults(tmp_path):
    db = tmp_path / "c.db"
    conn = open_db(db)
    # Simulate a pre-migration insert (raw SQL, bypassing upsert_project's new fields).
    conn.execute(
        "INSERT INTO projects(path, name, parent_dir, last_modified) VALUES (?,?,?,?)",
        ("/x.als", "x", "/", 0.0),
    )
    conn.commit()
    conn.close()
    conn2 = open_db(db)  # re-open triggers migration if it weren't already run
    row = conn2.execute(
        "SELECT mac_paths_count, has_project_info FROM projects WHERE path='/x.als'"
    ).fetchone()
    assert row[0] is None
    assert row[1] is None
```

**Step 2: Run tests to verify they fail**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_db_migration_macpath.py -v`
Expected: FAIL — column missing.

**Step 3: Add migration to `connection.py`**

Find the existing `_migrate(conn)` (or equivalent) function in `packages/core/audio_core/db/connection.py`. The codebase already does additive migrations; follow the same pattern. Add:

```python
def _add_column_if_missing(conn, table: str, col_def: str) -> None:
    col_name = col_def.split()[0]
    cols = {r[1] for r in conn.execute(f"PRAGMA table_info({table})").fetchall()}
    if col_name not in cols:
        conn.execute(f"ALTER TABLE {table} ADD COLUMN {col_def}")


# inside _migrate / open_db, after existing migrations:
_add_column_if_missing(conn, "projects", "mac_paths_count INTEGER")
_add_column_if_missing(conn, "projects", "has_project_info INTEGER")
```

If a helper of the same shape already exists, reuse it instead of duplicating.

**Step 4: Run tests to verify they pass**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_db_migration_macpath.py -v`
Expected: 3 passed.

Run the full DB suite to confirm nothing else regressed:
Run: `uv run --project Z:/User/audio pytest packages/core/tests/ -k db -v`

**Step 5: Commit**

```bash
git add packages/core/audio_core/db/connection.py packages/core/tests/test_db_migration_macpath.py
git commit -m "feat(core/db): add mac_paths_count and has_project_info columns"
```

---

## Task 2: Parser counts Mac-prefix `<Path Value>` children

**Files:**
- Modify: `packages/core/audio_core/parser/model.py`
- Modify: `packages/core/audio_core/parser/als.py`
- Test: `packages/core/tests/test_parser_macpath.py`
- Fixture (create): `packages/core/tests/fixtures/mac_imported_tiny.als` — see Step 0.

**Step 0: Build the fixture**

The catalog already ships `tiny.als`; we need a sibling that contains a few FileRefs with `<Path Value="/Volumes/..."/>` children. Generate it once by hand:

```python
# scripts/build_mac_fixture.py  (run-once, do not commit the script — the
# committed artifact is the .als it produces)
import gzip
from pathlib import Path
from lxml import etree

base = Path("packages/core/tests/fixtures/tiny.als")
out  = Path("packages/core/tests/fixtures/mac_imported_tiny.als")

with gzip.open(base, "rb") as fh:
    tree = etree.parse(fh, etree.XMLParser(huge_tree=True))
root = tree.getroot()

# Find any FileRef and inject a Mac <Path>.
injected = 0
for fr in root.iter("FileRef"):
    has_path = fr.find("Path") is not None
    if has_path:
        continue
    p = etree.SubElement(fr, "Path")
    p.set("Value", "/Volumes/Music/Ableton/Samples/fake.wav")
    injected += 1
    if injected >= 3:
        break

with gzip.open(out, "wb") as fh:
    tree.write(fh, xml_declaration=True, encoding="UTF-8")
print(f"injected {injected} mac paths into {out}")
```

Run it: `uv run --project Z:/User/audio python scripts/build_mac_fixture.py`. Verify the output exists, then delete the script (it's a one-shot generator). Commit only `mac_imported_tiny.als`.

**Step 1: Add `mac_paths_count` to `ProjectMetadata`**

```python
# packages/core/audio_core/parser/model.py — extend the dataclass
@dataclass
class ProjectMetadata:
    # ... existing fields ...
    mac_paths_count: int = 0
```

**Step 2: Write the failing test**

```python
# packages/core/tests/test_parser_macpath.py
from pathlib import Path
from audio_core.parser.als import parse_als


def test_clean_project_has_zero_mac_paths():
    md = parse_als(Path(__file__).parent / "fixtures" / "tiny.als")
    assert md.mac_paths_count == 0


def test_mac_imported_fixture_counts_three():
    md = parse_als(Path(__file__).parent / "fixtures" / "mac_imported_tiny.als")
    assert md.mac_paths_count == 3
```

**Step 3: Run tests to verify they fail**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_parser_macpath.py -v`
Expected: FAIL — `ProjectMetadata` has no `mac_paths_count` (or it's always 0).

**Step 4: Wire counting into `parse_als`**

In `packages/core/audio_core/parser/als.py`, near the top:

```python
_MAC_PATH_PREFIXES = ("/Volumes/", "/Users/", "/Library/", "/Applications/", "/private/")
```

Add `"FileRef"` and `"Path"` to `_INTERESTING_END_TAGS` if they're not already there (they are not — the streaming parser doesn't currently react to FileRef).

In the iterparse loop in `parse_als`, accumulate the count. Cheapest path: react to `Path` end-events whose parent is `FileRef` and whose `Value` starts with a Mac prefix:

```python
mac_paths_count = 0
# ... inside the for-loop, in the "end" branch ...
elif tag == "Path":
    parent = elem.getparent()
    if parent is not None and parent.tag == "FileRef":
        v = elem.get("Value", "")
        if v.startswith(_MAC_PATH_PREFIXES):
            mac_paths_count += 1
    # don't free — Path is shallow; tracks/SampleRefs free their own subtrees
```

At the end, pass `mac_paths_count=mac_paths_count` into the `ProjectMetadata(...)` constructor.

**Step 5: Run tests to verify they pass**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_parser_macpath.py -v`
Expected: 2 passed.

Run the full parser suite — Mac-path counting must not regress existing parser tests:
Run: `uv run --project Z:/User/audio pytest packages/core/tests/ -k parser -v`

**Step 6: Commit**

```bash
git add packages/core/audio_core/parser/model.py packages/core/audio_core/parser/als.py packages/core/tests/test_parser_macpath.py packages/core/tests/fixtures/mac_imported_tiny.als
git commit -m "feat(core/parser): count Mac-prefix FileRef Path values during stream parse"
```

---

## Task 3: Scanner persists `mac_paths_count` and `has_project_info`

**Files:**
- Modify: `packages/core/audio_core/scanner/scan.py`
- Modify: `packages/core/audio_core/db/projects.py` (extend `upsert_project` signature)
- Test: `packages/core/tests/test_scanner_macpath.py`

**Step 1: Write the failing test**

```python
# packages/core/tests/test_scanner_macpath.py
import shutil
from pathlib import Path

from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_one


def test_scan_persists_mac_paths_count(tmp_path):
    fixtures = Path(__file__).parent / "fixtures"
    (tmp_path / "p Project").mkdir()
    shutil.copy(fixtures / "mac_imported_tiny.als", tmp_path / "p Project" / "x.als")

    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, tmp_path / "p Project" / "x.als")
    row = conn.execute(
        "SELECT mac_paths_count, has_project_info FROM projects WHERE id=?", (pid,)
    ).fetchone()
    assert row[0] == 3
    # No Ableton Project Info/ folder was created → 0
    assert row[1] == 0


def test_scan_with_project_info_folder(tmp_path):
    fixtures = Path(__file__).parent / "fixtures"
    proj = tmp_path / "p Project"
    proj.mkdir()
    (proj / "Ableton Project Info").mkdir()
    shutil.copy(fixtures / "tiny.als", proj / "x.als")

    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, proj / "x.als")
    row = conn.execute(
        "SELECT mac_paths_count, has_project_info FROM projects WHERE id=?", (pid,)
    ).fetchone()
    assert row[0] == 0
    assert row[1] == 1
```

**Step 2: Run tests to verify they fail**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_scanner_macpath.py -v`
Expected: FAIL — likely AttributeError on the row, or `upsert_project` rejecting unknown kwargs.

**Step 3: Extend `upsert_project`**

In `packages/core/audio_core/db/projects.py`, find `upsert_project`. Add two kwargs and bind them in both the INSERT and UPDATE:

```python
def upsert_project(
    conn,
    *,
    # ... existing kwargs ...
    mac_paths_count: int | None = None,
    has_project_info: int | None = None,
) -> int:
    # ...existing body, with mac_paths_count and has_project_info added to the
    # INSERT column list and the UPDATE SET clause...
```

Match the existing column-binding pattern exactly. Any column already touched by `upsert_project` (e.g. `last_modified`) is the template to follow.

**Step 4: Wire scanner**

In `packages/core/audio_core/scanner/scan.py`, modify `scan_one`:

```python
def scan_one(conn: sqlite3.Connection, als_path: str | Path) -> int:
    p = Path(als_path).resolve()
    meta = parse_als(p)
    stat = p.stat()
    has_pi = 1 if (p.parent / "Ableton Project Info").is_dir() else 0
    return upsert_project(
        conn,
        path=str(p),
        name=p.stem,
        parent_dir=str(p.parent),
        file_hash=hash_file(p),
        last_modified=stat.st_mtime,
        meta=meta,
        file_size_bytes=stat.st_size,
        mac_paths_count=meta.mac_paths_count,
        has_project_info=has_pi,
    )
```

**Step 5: Run tests to verify they pass**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_scanner_macpath.py -v`
Expected: 2 passed.

Re-run scan-related tests:
Run: `uv run --project Z:/User/audio pytest packages/core/tests/ -k scan -v`

**Step 6: Commit**

```bash
git add packages/core/audio_core/db/projects.py packages/core/audio_core/scanner/scan.py packages/core/tests/test_scanner_macpath.py
git commit -m "feat(core/scan): persist mac_paths_count and has_project_info"
```

---

## Task 4: `find_mac_imports` query

**Files:**
- Create: `packages/core/audio_core/macpath.py`
- Test: `packages/core/tests/test_macpath_find.py`

**Step 1: Write the failing test**

```python
# packages/core/tests/test_macpath_find.py
import shutil
from pathlib import Path

from audio_core.db.connection import open_db
from audio_core.macpath import find_mac_imports
from audio_core.scanner.scan import scan_one


def _seed_proj(tmp_path, fixture_name: str, name: str, with_info: bool = False):
    fixtures = Path(__file__).parent / "fixtures"
    proj = tmp_path / f"{name} Project"
    proj.mkdir()
    if with_info:
        (proj / "Ableton Project Info").mkdir()
    shutil.copy(fixtures / fixture_name, proj / f"{name}.als")
    return proj / f"{name}.als"


def test_clean_catalog_returns_no_findings(tmp_path):
    conn = open_db(tmp_path / "c.db")
    scan_one(conn, _seed_proj(tmp_path, "tiny.als", "clean", with_info=True))
    assert find_mac_imports(conn) == []


def test_mac_paths_only_is_flagged(tmp_path):
    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, _seed_proj(tmp_path, "mac_imported_tiny.als", "macpaths", with_info=True))
    findings = find_mac_imports(conn)
    assert len(findings) == 1
    assert findings[0].project_id == pid
    assert findings[0].mac_paths_count == 3
    assert findings[0].project_info_missing is False


def test_missing_project_info_only_is_flagged(tmp_path):
    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, _seed_proj(tmp_path, "tiny.als", "noinfo", with_info=False))
    findings = find_mac_imports(conn)
    assert len(findings) == 1
    assert findings[0].project_id == pid
    assert findings[0].mac_paths_count == 0
    assert findings[0].project_info_missing is True


def test_archived_excluded(tmp_path):
    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, _seed_proj(tmp_path, "mac_imported_tiny.als", "arch", with_info=False))
    conn.execute("UPDATE projects SET is_archived=1 WHERE id=?", (pid,))
    conn.commit()
    assert find_mac_imports(conn) == []
```

**Step 2: Run tests to verify they fail**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_macpath_find.py -v`
Expected: FAIL — `ImportError: cannot import name 'find_mac_imports'`.

**Step 3: Implement**

```python
# packages/core/audio_core/macpath.py
from __future__ import annotations

import sqlite3
from dataclasses import dataclass


@dataclass(frozen=True)
class MacImportFinding:
    project_id: int
    path: str
    name: str
    parent_dir: str
    mac_paths_count: int
    project_info_missing: bool


def find_mac_imports(conn: sqlite3.Connection) -> list[MacImportFinding]:
    """Projects that look Mac-saved-on-Windows: at least one Mac-prefix Path
    in the .als, OR no Ableton Project Info/ folder. Archived rows excluded."""
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
        """
        SELECT id, path, name, parent_dir,
               COALESCE(mac_paths_count, 0)  AS mp,
               COALESCE(has_project_info, 0) AS pi
        FROM projects
        WHERE COALESCE(is_archived, 0) = 0
          AND (COALESCE(mac_paths_count, 0) > 0
               OR COALESCE(has_project_info, 0) = 0)
        ORDER BY mp DESC, id ASC
        """
    ).fetchall()
    return [
        MacImportFinding(
            project_id=r["id"],
            path=r["path"],
            name=r["name"],
            parent_dir=r["parent_dir"],
            mac_paths_count=int(r["mp"]),
            project_info_missing=(int(r["pi"]) == 0),
        )
        for r in rows
    ]
```

**Step 4: Run tests to verify they pass**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_macpath_find.py -v`
Expected: 4 passed.

**Step 5: Commit**

```bash
git add packages/core/audio_core/macpath.py packages/core/tests/test_macpath_find.py
git commit -m "feat(core): find_mac_imports query for Mac-saved projects"
```

---

## Task 5: `RepairMacPaths` action — happy path

**Files:**
- Create: `packages/core/audio_core/actions/repair_mac_paths.py`
- Test: `packages/core/tests/test_actions_repair_mac_paths.py`

**Step 1: Write the failing test**

```python
# packages/core/tests/test_actions_repair_mac_paths.py
import gzip
import shutil
from pathlib import Path

from lxml import etree

from audio_core.actions.repair_mac_paths import RepairMacPaths
from audio_core.actions.runner import run_batch
from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_one


def _seed_mac_project(tmp_path, root_name="root"):
    fixtures = Path(__file__).parent / "fixtures"
    root = tmp_path / root_name
    proj = root / "Projects" / "p Project"
    proj.mkdir(parents=True)
    shutil.copy(fixtures / "mac_imported_tiny.als", proj / "x.als")
    conn = open_db(root / "data" / "catalog.db")
    pid = scan_one(conn, proj / "x.als")
    return root, conn, pid, proj / "x.als"


def _als_xml(path: Path):
    with gzip.open(path, "rb") as fh:
        return etree.parse(fh, etree.XMLParser(huge_tree=True)).getroot()


def test_repair_strips_mac_paths_and_creates_project_info(tmp_path):
    root, conn, pid, als = _seed_mac_project(tmp_path)
    assert not (als.parent / "Ableton Project Info").exists()

    run_batch(
        conn,
        [RepairMacPaths(project_id=pid, root=root / "Projects")],
        actor="test",
        journal_dir=root / "data" / "journal",
    )

    rxml = _als_xml(als)
    mac = [
        p for p in rxml.iter("Path")
        if p.get("Value", "").startswith(
            ("/Volumes/", "/Users/", "/Library/", "/Applications/", "/private/")
        )
    ]
    assert mac == []
    assert list(rxml.iter("OriginalFileRef")) == []
    assert (als.parent / "Ableton Project Info").is_dir()

    # Backup is left next to the .als.
    assert (als.with_suffix(".als.bak")).is_file()

    # Catalog row reflects the repair.
    row = conn.execute(
        "SELECT mac_paths_count, has_project_info FROM projects WHERE id=?", (pid,)
    ).fetchone()
    assert row[0] == 0
    assert row[1] == 1


def test_repair_is_idempotent(tmp_path):
    root, conn, pid, als = _seed_mac_project(tmp_path)
    run_batch(
        conn,
        [RepairMacPaths(project_id=pid, root=root / "Projects")],
        actor="test",
        journal_dir=root / "data" / "journal",
    )
    # Second run: nothing to do, must not raise.
    run_batch(
        conn,
        [RepairMacPaths(project_id=pid, root=root / "Projects")],
        actor="test",
        journal_dir=root / "data" / "journal",
    )
    row = conn.execute(
        "SELECT mac_paths_count, has_project_info FROM projects WHERE id=?", (pid,)
    ).fetchone()
    assert row[0] == 0 and row[1] == 1


def test_repair_refuses_outside_root(tmp_path):
    """An action whose project's parent_dir escapes self.root must be blocked."""
    root, conn, pid, als = _seed_mac_project(tmp_path)
    # Point root somewhere unrelated; ensure_within should reject.
    bogus_root = tmp_path / "elsewhere"
    bogus_root.mkdir()
    import pytest as _pytest
    with _pytest.raises(Exception):
        RepairMacPaths(project_id=pid, root=bogus_root).validate(conn)


def test_repair_refuses_when_live_has_file_open(tmp_path, monkeypatch):
    root, conn, pid, als = _seed_mac_project(tmp_path)
    monkeypatch.setattr(
        "audio_core.actions.repair_mac_paths.is_open_in_live", lambda _path: True
    )
    import pytest as _pytest
    with _pytest.raises(RuntimeError):
        RepairMacPaths(project_id=pid, root=root / "Projects").validate(conn)
```

**Step 2: Run tests to verify they fail**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_actions_repair_mac_paths.py -v`
Expected: ImportError on `RepairMacPaths`.

**Step 3: Implement the action**

```python
# packages/core/audio_core/actions/repair_mac_paths.py
from __future__ import annotations

import gzip
import shutil
import sqlite3
from dataclasses import dataclass
from pathlib import Path

from lxml import etree

from audio_core.safety.live_lock import is_open_in_live
from audio_core.safety.paths import ensure_within

_MAC_PREFIXES = ("/Volumes/", "/Users/", "/Library/", "/Applications/", "/private/")
_PROJECT_INFO = "Ableton Project Info"
_MIN_CFG = "Project11.cfg"  # Live 11 marker; presence of folder is what matters,
                            # but the .cfg keeps Live's File Manager happy.


@dataclass
class RepairMacPaths:
    """Fix a Mac-saved .als so it loads on Windows without freezing.

    Steps (atomic): strip Mac-prefix <Path Value> children from every FileRef,
    drop <OriginalFileRef> wrappers (they trigger Live's missing-files dialog),
    and create the Ableton Project Info/ marker folder if it's missing.

    Backup is left next to the .als as <stem>.als.bak. Undo restores from it.
    """

    project_id: int
    root: Path

    def _row(self, conn: sqlite3.Connection) -> sqlite3.Row:
        conn.row_factory = sqlite3.Row
        r = conn.execute("SELECT * FROM projects WHERE id=?", (self.project_id,)).fetchone()
        if r is None:
            raise LookupError(f"no project id={self.project_id}")
        return r

    def validate(self, conn: sqlite3.Connection) -> None:
        row = self._row(conn)
        ensure_within(Path(row["parent_dir"]), self.root)
        if is_open_in_live(row["path"]):
            raise RuntimeError(f"Live has {row['path']} open; close it first")

    def execute(self, conn: sqlite3.Connection) -> dict:
        row = self._row(conn)
        als = Path(row["path"])
        proj = als.parent
        bak = als.with_suffix(".als.bak")
        had_pi = (proj / _PROJECT_INFO).is_dir()
        no_op = (row["mac_paths_count"] or 0) == 0 and had_pi

        if no_op:
            return {
                "type": "RepairMacPaths",
                "project_id": self.project_id,
                "path": str(als),
                "noop": True,
            }

        # 1) Backup (only if missing — re-running keeps the original original).
        if not bak.exists():
            shutil.copy2(als, bak)

        # 2) Rewrite .als if Mac paths or OriginalFileRef are present.
        if (row["mac_paths_count"] or 0) > 0:
            with gzip.open(als, "rb") as fh:
                tree = etree.parse(fh, etree.XMLParser(huge_tree=True))
            xroot = tree.getroot()
            for orig in list(xroot.iter("OriginalFileRef")):
                parent = orig.getparent()
                if parent is not None:
                    parent.remove(orig)
            for fr in xroot.iter("FileRef"):
                p_attr = fr.get("Path")
                if p_attr and p_attr.startswith(_MAC_PREFIXES):
                    del fr.attrib["Path"]
                pc = fr.find("Path")
                if pc is not None and pc.get("Value", "").startswith(_MAC_PREFIXES):
                    fr.remove(pc)
            tmp = als.with_suffix(".als.tmp")
            with gzip.open(tmp, "wb") as fh:
                tree.write(fh, xml_declaration=True, encoding="UTF-8")
            tmp.replace(als)  # atomic on Windows when target exists

        # 3) Create project info marker.
        if not had_pi:
            (proj / _PROJECT_INFO).mkdir(exist_ok=True)
            cfg = proj / _PROJECT_INFO / _MIN_CFG
            if not cfg.exists():
                cfg.write_bytes(b"")  # empty placeholder; Live populates on save.

        # 4) Refresh catalog row.
        conn.execute(
            "UPDATE projects SET mac_paths_count=0, has_project_info=1 WHERE id=?",
            (self.project_id,),
        )
        conn.commit()

        return {
            "type": "RepairMacPaths",
            "project_id": self.project_id,
            "path": str(als),
            "backup": str(bak),
            "created_project_info": not had_pi,
            "stripped_mac_paths": int(row["mac_paths_count"] or 0),
        }
```

**Step 4: Run tests to verify they pass**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_actions_repair_mac_paths.py -v`
Expected: 4 passed.

**Step 5: Commit**

```bash
git add packages/core/audio_core/actions/repair_mac_paths.py packages/core/tests/test_actions_repair_mac_paths.py
git commit -m "feat(core/actions): RepairMacPaths strips Mac paths + ensures Project Info"
```

---

## Task 6: Undo for `RepairMacPaths`

**Files:**
- Modify: `packages/core/audio_core/actions/undo.py`
- Test: `packages/core/tests/test_actions_repair_mac_paths.py` (append)

**Step 1: Inspect existing undo dispatch**

Run: `Read packages/core/audio_core/actions/undo.py` to see the dispatch shape (probably a switch on `entry["type"]`).

**Step 2: Write the failing test**

Append to `test_actions_repair_mac_paths.py`:

```python
def test_undo_restores_als_from_backup(tmp_path):
    from audio_core.actions.runner import run_batch
    from audio_core.actions.undo import undo_batch

    root, conn, pid, als = _seed_mac_project(tmp_path)
    pre_bytes = als.read_bytes()

    batch_id = run_batch(
        conn,
        [RepairMacPaths(project_id=pid, root=root / "Projects")],
        actor="test",
        journal_dir=root / "data" / "journal",
    )
    assert als.read_bytes() != pre_bytes

    undo_batch(conn, batch_id, journal_dir=root / "data" / "journal", root=root / "Projects")

    assert als.read_bytes() == pre_bytes
    # Backup file is left in place after undo (cheap rollback).
    assert (als.with_suffix(".als.bak")).exists()
    row = conn.execute(
        "SELECT mac_paths_count, has_project_info FROM projects WHERE id=?", (pid,)
    ).fetchone()
    assert row[0] == 3
    # Project Info folder created by repair stays — undo doesn't delete dirs.
```

If `undo_batch`'s real signature differs (e.g. it doesn't take `root`), match the actual signature; the contract is "undo applies the inverse of every entry in batch_id".

**Step 3: Run test to verify it fails**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_actions_repair_mac_paths.py::test_undo_restores_als_from_backup -v`
Expected: FAIL — undo dispatch has no handler for `RepairMacPaths`.

**Step 4: Implement undo handler**

In `packages/core/audio_core/actions/undo.py`, find the dispatch table / if-tree. Add:

```python
def _undo_repair_mac_paths(entry: dict, conn) -> None:
    if entry.get("noop"):
        return
    bak = Path(entry["backup"])
    als = Path(entry["path"])
    if not bak.exists():
        raise FileNotFoundError(f"backup missing: {bak}")
    shutil.copy2(bak, als)
    # Re-parse so catalog reflects pre-repair state.
    from audio_core.scanner.scan import scan_one
    scan_one(conn, als)
```

Wire it into the dispatch (`elif entry["type"] == "RepairMacPaths": _undo_repair_mac_paths(entry, conn)` or whatever pattern is in use).

**Step 5: Run test to verify it passes**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_actions_repair_mac_paths.py -v`
Expected: 5 passed.

**Step 6: Commit**

```bash
git add packages/core/audio_core/actions/undo.py packages/core/tests/test_actions_repair_mac_paths.py
git commit -m "feat(core/actions): undo restores .als from .als.bak for RepairMacPaths"
```

---

## Task 7: `build_repair_proposal` helper

**Files:**
- Modify: `packages/core/audio_core/macpath.py`
- Modify: `packages/core/tests/test_macpath_find.py`

**Step 1: Write the failing test**

```python
def test_build_repair_proposal_one_action_per_finding(tmp_path):
    from audio_core.macpath import build_repair_proposal

    conn = open_db(tmp_path / "c.db")
    p1 = scan_one(conn, _seed_proj(tmp_path, "mac_imported_tiny.als", "a"))
    p2 = scan_one(conn, _seed_proj(tmp_path, "tiny.als", "b"))   # missing project info
    p3 = scan_one(conn, _seed_proj(tmp_path, "tiny.als", "c", with_info=True))  # clean
    actions = build_repair_proposal(find_mac_imports(conn))
    pids = {a["args"]["project_id"] for a in actions}
    assert pids == {p1, p2}
    assert all(a["type"] == "RepairMacPaths" for a in actions)
    assert p3 not in pids
```

**Step 2: Run test, expect ImportError**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_macpath_find.py::test_build_repair_proposal_one_action_per_finding -v`

**Step 3: Implement**

Append to `packages/core/audio_core/macpath.py`:

```python
def build_repair_proposal(findings: list[MacImportFinding]) -> list[dict]:
    return [
        {"type": "RepairMacPaths", "args": {"project_id": f.project_id}}
        for f in findings
    ]
```

**Step 4: Run, expect pass.**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_macpath_find.py -v`

**Step 5: Commit**

```bash
git add packages/core/audio_core/macpath.py packages/core/tests/test_macpath_find.py
git commit -m "feat(core): build_repair_proposal turns findings into RepairMacPaths actions"
```

---

## Task 8: CLI `audio repair-mac-paths`

**Files:**
- Modify: `packages/cli/audio_cli/main.py`
- Test: `packages/cli/tests/test_cli_repair_mac_paths.py`

**Step 1: Write the failing tests**

Mirror `packages/cli/tests/test_cli_dedup.py` exactly — same fixtures path, same `monkeypatch.setenv("AUDIO_ROOT", ...)`, same three test shapes:

```python
import json as _json
import shutil
from pathlib import Path

from audio_cli.main import app
from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_one
from typer.testing import CliRunner


def _seed(tmp_path, fixture_name: str, name: str, with_info=False):
    fixtures = Path(__file__).parents[2] / "core" / "tests" / "fixtures"
    proj = tmp_path / f"{name} Project"
    proj.mkdir(parents=True, exist_ok=True)
    if with_info:
        (proj / "Ableton Project Info").mkdir(exist_ok=True)
    shutil.copy(fixtures / fixture_name, proj / f"{name}.als")
    return proj / f"{name}.als"


def test_no_findings(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    open_db(tmp_path / "data" / "catalog.db")
    res = CliRunner().invoke(app, ["repair-mac-paths"])
    assert res.exit_code == 0
    assert "No Mac-imported projects" in res.stdout


def test_lists_findings(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    pid = scan_one(conn, _seed(tmp_path, "mac_imported_tiny.als", "macpaths"))
    res = CliRunner().invoke(app, ["repair-mac-paths"])
    assert res.exit_code == 0
    assert "macpaths" in res.stdout
    assert str(pid) in res.stdout


def test_json(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    scan_one(conn, _seed(tmp_path, "mac_imported_tiny.als", "j"))
    res = CliRunner().invoke(app, ["repair-mac-paths", "--json"])
    assert res.exit_code == 0
    assert _json.loads(res.stdout)


def test_propose_writes_proposal(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    pid = scan_one(conn, _seed(tmp_path, "mac_imported_tiny.als", "p"))
    res = CliRunner().invoke(app, ["repair-mac-paths", "--propose"])
    assert res.exit_code == 0
    files = list((tmp_path / "data" / "proposals").glob("*.json"))
    assert len(files) == 1
    payload = _json.loads(files[0].read_text(encoding="utf-8"))
    assert payload["actor"] == "cli"
    assert {a["args"]["project_id"] for a in payload["actions"]} == {pid}
```

**Step 2: Run tests, expect FAIL**

Run: `uv run --project Z:/User/audio pytest packages/cli/tests/test_cli_repair_mac_paths.py -v`
Expected: FAIL — no such command.

**Step 3: Add the command**

Mirror the structure of the existing `dedup` command. In `packages/cli/audio_cli/main.py`:

```python
from audio_core.macpath import find_mac_imports, build_repair_proposal


@app.command("repair-mac-paths")
def repair_mac_paths(
    json_out: bool = typer.Option(False, "--json"),
    propose: bool = typer.Option(False, "--propose"),
) -> None:
    """List projects that look Mac-saved-on-Windows; --propose to draft a repair batch."""
    conn = open_db(db_path())
    findings = find_mac_imports(conn)

    if propose:
        actions = build_repair_proposal(findings)
        if not actions:
            con.print("No Mac-imported projects to repair.")
            raise typer.Exit(code=0)
        # Reuse whatever proposal-writing helper the dedup command uses; if
        # there's no shared helper yet, copy the same inline block.
        _write_proposal(actions, rationale=f"repair-mac-paths: {len(findings)} project(s)")
        return

    if json_out:
        con.print_json(
            data=[
                {
                    "project_id": f.project_id,
                    "path": f.path,
                    "name": f.name,
                    "mac_paths_count": f.mac_paths_count,
                    "project_info_missing": f.project_info_missing,
                }
                for f in findings
            ]
        )
        return

    if not findings:
        con.print("No Mac-imported projects found.")
        return
    for f in findings:
        flags = []
        if f.mac_paths_count:
            flags.append(f"{f.mac_paths_count} mac path(s)")
        if f.project_info_missing:
            flags.append("no Ableton Project Info/")
        con.print(f"  id={f.project_id}  {f.path}  [{', '.join(flags)}]")
```

If `_write_proposal` isn't a real helper yet, factor it out from the dedup command in this same task — both commands need the identical block.

**Step 4: Run, expect pass.**

Run: `uv run --project Z:/User/audio pytest packages/cli/tests/test_cli_repair_mac_paths.py -v`

**Step 5: Commit**

```bash
git add packages/cli/audio_cli/main.py packages/cli/tests/test_cli_repair_mac_paths.py
git commit -m "feat(cli): audio repair-mac-paths lists & proposes Mac-import repairs"
```

---

## Task 9: MCP `find_mac_imports` tool

**Files:**
- Modify: `packages/mcp/audio_mcp/main.py`
- Modify: `packages/mcp/tests/test_mcp_tools.py`

**Step 1: Write the failing test**

Append to `packages/mcp/tests/test_mcp_tools.py`, mirroring `test_find_duplicates_tool_returns_groups`:

```python
def test_find_mac_imports_tool_returns_findings(tmp_path, monkeypatch):
    import shutil
    from pathlib import Path
    from audio_core.db.connection import open_db
    from audio_core.scanner.scan import scan_one

    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    fixtures = Path(__file__).parents[2] / "core" / "tests" / "fixtures"
    proj = tmp_path / "p Project"
    proj.mkdir()
    shutil.copy(fixtures / "mac_imported_tiny.als", proj / "x.als")
    pid = scan_one(conn, proj / "x.als")

    server = build_server()

    async def go():
        async with Client(server) as c:
            res = await c.call_tool("find_mac_imports", {})
            data = res.data
            assert len(data) == 1
            assert data[0]["project_id"] == pid
            assert data[0]["mac_paths_count"] == 3

    asyncio.run(go())
```

**Step 2: Run, expect FAIL on missing tool.**

Run: `uv run --project Z:/User/audio pytest packages/mcp/tests/test_mcp_tools.py -k mac_imports -v`

**Step 3: Add the tool**

In `packages/mcp/audio_mcp/main.py`:

```python
from audio_core.macpath import find_mac_imports as _find_mac_imports

@mcp.tool
def find_mac_imports(limit: int = 200) -> list[dict]:
    """Projects that look Mac-saved-on-Windows: at least one Mac-style absolute
    path inside the .als, OR no `Ableton Project Info/` folder. Pair with
    `propose_batch` and one `RepairMacPaths` action per project_id to fix.
    """
    conn = open_db(db_path())
    findings = _find_mac_imports(conn)[:limit]
    return [
        {
            "project_id": f.project_id,
            "path": f.path,
            "name": f.name,
            "mac_paths_count": f.mac_paths_count,
            "project_info_missing": f.project_info_missing,
        }
        for f in findings
    ]
```

**Step 4: Run, expect pass.**

Run: `uv run --project Z:/User/audio pytest packages/mcp/tests/test_mcp_tools.py -v`

**Step 5: Commit**

```bash
git add packages/mcp/audio_mcp/main.py packages/mcp/tests/test_mcp_tools.py
git commit -m "feat(mcp): find_mac_imports tool"
```

---

## Task 10: Docs

**Files:**
- Modify: `docs/mcp-setup.md`
- Modify: any user-facing CLI doc that lists commands (search the codebase first; if there isn't one, skip).

**Step 1: Add bullet to the MCP tool list**

```markdown
- `find_mac_imports(limit)` — list projects that look Mac-saved-on-Windows; pair with `propose_batch` + `RepairMacPaths` actions to fix.
```

**Step 2: Commit**

```bash
git add docs/mcp-setup.md
git commit -m "docs(mcp): document find_mac_imports tool"
```

---

## Task 11: Full test sweep

**Step 1: Run every package's tests**

Run: `uv run --project Z:/User/audio pytest packages/core packages/cli packages/mcp packages/web -q`
Expected: all pass.

If a regression surfaced, fix it before continuing.

---

## Task 12: E2E smoke against the real catalog

Manual verification only.

**Step 1: Detect**

Run: `uv run --project Z:/User/audio audio repair-mac-paths`
Expected: at least the `kiki_remix Project/kiki_remix_02.als` does not appear (we already repaired it manually). Likely many siblings do — the user has ~1,628 projects, many Mac-originated.

**Step 2: Re-scan first if results look stale**

Counts only update on rescan. If `--json` shows a project with `mac_paths_count: 0` that you suspect has Mac paths, re-scan that subtree:
Run: `uv run --project Z:/User/audio audio scan <subdir>`
then re-run `repair-mac-paths`.

**Step 3: Propose & approve a small batch**

Run: `uv run --project Z:/User/audio audio repair-mac-paths --propose`
A proposal JSON appears under `data/proposals/`. Pick **one** project, edit the proposal to keep just that action (so the first real-world run is small), then approve via the web layer:

```bash
uv run --project Z:/User/audio uvicorn audio_web.app:create_app --factory --port 8000
curl -X POST http://localhost:8000/api/proposals/<proposal_id>/approve
```

Open the repaired `.als` in Live. Confirm: no missing-files dialog, samples load, play doesn't freeze.

**Step 4: Undo the smoke project, then redo with confidence**

Run: `uv run --project Z:/User/audio audio undo <batch_id>`
Verify the `.als` matches the `.bak` byte-for-byte. Then re-approve / re-run on a larger batch.

**Step 5: Done.**

---

## Notes

- **Why detection lives in two places** (cached column + on-the-fly query): scanning ~1,628 projects to detect Mac paths every CLI invocation would re-parse multi-GB of XML. The column lets `find_mac_imports` be a single SQL query. The price is one extra column and a re-scan after the migration to populate it.
- **Why we don't strip `RelativePath`:** initial prototype did, and wiped 9 Envelope Follower M4L refs (`RelativePathType=7`, resolved through Live's Builtin folder). Leave `<RelativePath>` alone — it's platform-neutral and Live's own resolver handles non-project types.
- **Why we don't rewrite `<Path>` to a Windows path:** the moment Live saves the project, it'll write the new local path itself. Rewriting now adds a brittle dependency on the user's drive layout.
- **Backup retention:** `.als.bak` is left in place after undo. There's no automatic cleanup; if the user wants one, that's a follow-up (probably a `audio cleanup-backups --older-than 30d` command).
- **The reference script `_fix_kiki.py`** in the project root can be deleted once Task 5 lands — its behavior is now in `RepairMacPaths`.
