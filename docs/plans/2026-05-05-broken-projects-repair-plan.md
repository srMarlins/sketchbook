# Broken-Projects Repair — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. The user has waived per-batch checkpoints — drive through all tasks back-to-back, only stopping for genuine blockers. Browser-verify UI tasks visually as you go.

**Goal:** Build the missing tooling to fix broken projects (Mac-imports + missing samples) one-at-a-time from the project detail view, or in bulk from a new `/repair` route, using the existing proposals/journal pattern.

**Architecture:** Three layers. Core gets a new `samples` index (table + walker + indexer jobs), a `RelinkMissingSamples` action mirroring `RepairMacPaths`, and a `repair.py` facade returning combined findings. Web exposes `GET /api/repair/findings`; existing `POST /api/proposals` already accepts arbitrary action types. UI adds an inline `<RepairPanel>` (`<MarginStickyNote>` styled) on project detail, plus a `/repair` route with two `<Shelf>` groups and a mixed-action proposal flow.

**Tech Stack:** Python 3.12, lxml, gzip, SQLite (existing), watchdog (existing), FastAPI, Typer, FastMCP, React 18 + TS strict + Vite + TanStack Query (existing), Vitest + RTL + MSW, pytest.

**Reference design:** `docs/plans/2026-05-05-broken-projects-repair-design.md`.

**Reference shape:** `docs/plans/2026-05-04-macpath-repair-plan.md` is the template for action-shape tasks (5–8). Mirror its conventions: `.als.bak` next to file, atomic temp-replace, `is_open_in_live` guard, `ensure_within` guard, undo via byte-restore + rescan.

**Pre-flight context every task assumes:**
- `audio_core/db/schema.sql` is the canonical schema; `audio_core/db/connection.py::_apply_migrations` runs idempotent `ALTER TABLE` migrations.
- `audio_core/indexer/` has `discovery.py`, `walker.py`, `jobs.py` (`FullScan`, `IncrementalScan`, `BackfillColumn`), `queue.py` (single-flight FIFO), `watcher.py` (watchdog wrapper), `driver.py` (`boot()` + findings listener), `events.py` (event bus).
- `audio_core/actions/repair_mac_paths.py` is the reference action; `audio_core/actions/undo.py::_undo_repair_mac_paths` is the reference undo handler.
- `audio_web/routes_proposals.py::_materialize` is where new action types get wired into the approve flow; `audio_core/proposals/schema.py::ARG_SCHEMAS` is where their pydantic arg models live.
- `web/src/components/data/SongStrip.tsx`, `MarginStickyNote.tsx`, `FilterChip.tsx` are the stationery primitives. Bulk view uses `<Shelf>` (already exists in the corkboard or surface set — verify in Task 19's Step 0). Per-project panel reuses `<MarginStickyNote>`.
- v1 matcher conservativity: **filename-only single candidate auto-match** (we don't have recorded size for missing samples, so size-match isn't available in v1). Filename ties or zero matches → "needs review". Size column on `project_samples` is added forward-compat for stricter matching later.

---

## Task 1: Schema — `samples` table + `project_samples.size_bytes`

**Files:**
- Modify: `packages/core/audio_core/db/schema.sql`
- Modify: `packages/core/audio_core/db/connection.py`
- Test: `packages/core/tests/test_db_migration_samples.py`

**Step 1: Write the failing test**

```python
# packages/core/tests/test_db_migration_samples.py
from audio_core.db.connection import open_db


def test_samples_table_present(tmp_path):
    conn = open_db(tmp_path / "c.db")
    cols = {r[1] for r in conn.execute("PRAGMA table_info(samples)").fetchall()}
    assert {"id", "path", "filename", "size_bytes", "mtime", "parent_dir"} <= cols


def test_project_samples_has_size_bytes(tmp_path):
    conn = open_db(tmp_path / "c.db")
    cols = {r[1] for r in conn.execute("PRAGMA table_info(project_samples)").fetchall()}
    assert "size_bytes" in cols


def test_samples_indexes_present(tmp_path):
    conn = open_db(tmp_path / "c.db")
    idx = {r[1] for r in conn.execute("PRAGMA index_list(samples)").fetchall()}
    assert "idx_samples_filename_size" in idx
    assert "idx_samples_parent" in idx


def test_migration_idempotent(tmp_path):
    db = tmp_path / "c.db"
    open_db(db).close()
    conn = open_db(db)
    cols = {r[1] for r in conn.execute("PRAGMA table_info(samples)").fetchall()}
    assert "filename" in cols
```

**Step 2: Run, expect FAIL**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_db_migration_samples.py -v`
Expected: FAIL — table missing.

**Step 3: Update `schema.sql`**

Append to `packages/core/audio_core/db/schema.sql`:

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

Also add `size_bytes` to the existing `project_samples` definition:

```sql
CREATE TABLE IF NOT EXISTS project_samples (
  id           INTEGER PRIMARY KEY,
  project_id   INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  sample_path  TEXT NOT NULL,
  sample_hash  TEXT,
  is_missing   INTEGER NOT NULL DEFAULT 0,
  size_bytes   INTEGER
);
```

**Step 4: Add migration to `connection.py::_apply_migrations`**

After the existing `ALTER` checks, add:

```python
# samples table — idempotent create.
conn.executescript(
    "CREATE TABLE IF NOT EXISTS samples ("
    "id INTEGER PRIMARY KEY,"
    "path TEXT NOT NULL UNIQUE,"
    "filename TEXT NOT NULL,"
    "size_bytes INTEGER NOT NULL,"
    "mtime REAL NOT NULL,"
    "parent_dir TEXT NOT NULL"
    ");"
    "CREATE INDEX IF NOT EXISTS idx_samples_filename_size ON samples(filename, size_bytes);"
    "CREATE INDEX IF NOT EXISTS idx_samples_parent ON samples(parent_dir);"
)
ps_cols = {r[1] for r in conn.execute("PRAGMA table_info(project_samples)").fetchall()}
if ps_cols and "size_bytes" not in ps_cols:
    conn.execute("ALTER TABLE project_samples ADD COLUMN size_bytes INTEGER")
```

**Step 5: Run tests, expect PASS**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_db_migration_samples.py -v`
Expected: 4 passed.

Run the wider migration suite to confirm no regression:
Run: `uv run --project Z:/User/audio pytest packages/core/tests/ -k "migration or db_indexer" -v`

**Step 6: Commit**

```bash
git add packages/core/audio_core/db/schema.sql packages/core/audio_core/db/connection.py packages/core/tests/test_db_migration_samples.py
git commit -m "feat(core/db): samples table + project_samples.size_bytes for relink"
```

---

## Task 2: `sample_roots` config

**Files:**
- Modify: `packages/core/audio_core/config.py`
- Test: `packages/core/tests/test_config_sample_roots.py`

**Step 1: Write the failing test**

```python
# packages/core/tests/test_config_sample_roots.py
from pathlib import Path

from audio_core.config import sample_roots


def test_default_is_empty(monkeypatch, tmp_path):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    assert sample_roots() == []


def test_reads_toml(monkeypatch, tmp_path):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    extra1 = tmp_path / "extra1"
    extra2 = tmp_path / "extra2"
    extra1.mkdir()
    extra2.mkdir()
    (tmp_path / "config.toml").write_text(
        f'sample_roots = ["{extra1.as_posix()}", "{extra2.as_posix()}"]\n',
        encoding="utf-8",
    )
    roots = sample_roots()
    assert {r.resolve() for r in roots} == {extra1.resolve(), extra2.resolve()}


def test_skips_nonexistent(monkeypatch, tmp_path):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    (tmp_path / "config.toml").write_text(
        'sample_roots = ["Z:/does/not/exist"]\n', encoding="utf-8"
    )
    assert sample_roots() == []
```

**Step 2: Run, expect FAIL**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_config_sample_roots.py -v`
Expected: FAIL — `cannot import name 'sample_roots'`.

**Step 3: Implement**

Append to `packages/core/audio_core/config.py`:

```python
import tomllib


def _config_toml() -> dict:
    p = workspace_root() / "config.toml"
    if not p.is_file():
        return {}
    try:
        return tomllib.loads(p.read_text(encoding="utf-8"))
    except (OSError, tomllib.TOMLDecodeError):
        return {}


def sample_roots() -> list[Path]:
    """Extra audio-file roots the sample indexer walks (in addition to
    Projects/). Configured in `config.toml` as `sample_roots = ["...", ...]`.
    Nonexistent or unreadable entries are silently dropped — the catalog
    still works without them, and a typo shouldn't crash the indexer."""
    raw = _config_toml().get("sample_roots", [])
    out: list[Path] = []
    for entry in raw:
        try:
            p = Path(entry).resolve()
        except (OSError, ValueError):
            continue
        if p.is_dir():
            out.append(p)
    return out
```

**Step 4: Run, expect PASS**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_config_sample_roots.py -v`
Expected: 3 passed.

**Step 5: Commit**

```bash
git add packages/core/audio_core/config.py packages/core/tests/test_config_sample_roots.py
git commit -m "feat(core/config): sample_roots from config.toml for sample indexer"
```

---

## Task 3: `walk_samples` audio-file iterator

**Files:**
- Modify: `packages/core/audio_core/scanner/walker.py`
- Test: `packages/core/tests/test_walker_samples.py`

**Step 1: Write the failing test**

```python
# packages/core/tests/test_walker_samples.py
from pathlib import Path

from audio_core.scanner.walker import walk_samples


def _touch(p: Path):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(b"x")


def test_finds_audio_extensions(tmp_path):
    _touch(tmp_path / "a.wav")
    _touch(tmp_path / "sub/b.AIFF")
    _touch(tmp_path / "sub/c.flac")
    _touch(tmp_path / "sub/d.mp3")
    _touch(tmp_path / "ignore.als")
    _touch(tmp_path / "ignore.txt")
    paths = sorted(p.name.lower() for p in walk_samples(tmp_path))
    assert paths == ["a.wav", "b.aiff", "c.flac", "d.mp3"]


def test_excludes_backup_dirs(tmp_path):
    _touch(tmp_path / "Backup" / "a.wav")
    _touch(tmp_path / "_Archive" / "b.wav")
    _touch(tmp_path / "Ableton Project Info" / "c.wav")
    _touch(tmp_path / "real.wav")
    paths = [p.name for p in walk_samples(tmp_path)]
    assert paths == ["real.wav"]
```

**Step 2: Run, expect FAIL**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_walker_samples.py -v`

**Step 3: Implement**

Append to `packages/core/audio_core/scanner/walker.py`:

```python
SAMPLE_EXTENSIONS = frozenset({".wav", ".aif", ".aiff", ".flac", ".mp3", ".ogg"})


def walk_samples(root: str | Path) -> Iterator[Path]:
    """Walk `root` and yield every audio sample file (by extension). Reuses
    the project walker's `EXCLUDED_DIRS` so we don't crawl Backup/, _Archive/,
    or Ableton Project Info/."""
    root = Path(root)
    for p in root.rglob("*"):
        if p.suffix.lower() not in SAMPLE_EXTENSIONS:
            continue
        if not p.is_file():
            continue
        try:
            rel_parts = p.relative_to(root).parts
        except ValueError:
            continue
        if any(part in EXCLUDED_DIRS for part in rel_parts):
            continue
        yield p
```

**Step 4: Run, expect PASS**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_walker_samples.py -v`

**Step 5: Commit**

```bash
git add packages/core/audio_core/scanner/walker.py packages/core/tests/test_walker_samples.py
git commit -m "feat(core/scanner): walk_samples iterator for audio-file roots"
```

---

## Task 4: `samples.py` — upsert + lookup

**Files:**
- Create: `packages/core/audio_core/samples.py`
- Test: `packages/core/tests/test_samples_index.py`

**Step 1: Write the failing test**

```python
# packages/core/tests/test_samples_index.py
from pathlib import Path

from audio_core.db.connection import open_db
from audio_core.samples import (
    SampleRow,
    delete_sample,
    find_by_filename,
    upsert_sample,
)


def test_upsert_inserts(tmp_path):
    conn = open_db(tmp_path / "c.db")
    p = tmp_path / "a.wav"
    p.write_bytes(b"abc")
    upsert_sample(conn, p)
    rows = list(conn.execute("SELECT path, filename, size_bytes FROM samples"))
    assert rows == [(str(p.resolve()), "a.wav", 3)]


def test_upsert_updates(tmp_path):
    conn = open_db(tmp_path / "c.db")
    p = tmp_path / "a.wav"
    p.write_bytes(b"abc")
    upsert_sample(conn, p)
    p.write_bytes(b"abcdef")
    upsert_sample(conn, p)
    row = conn.execute("SELECT size_bytes FROM samples WHERE path=?", (str(p.resolve()),)).fetchone()
    assert row[0] == 6


def test_find_by_filename(tmp_path):
    conn = open_db(tmp_path / "c.db")
    a = tmp_path / "kick.wav"
    a.write_bytes(b"x")
    b = tmp_path / "sub" / "kick.wav"
    b.parent.mkdir()
    b.write_bytes(b"yy")
    c = tmp_path / "snare.wav"
    c.write_bytes(b"z")
    upsert_sample(conn, a)
    upsert_sample(conn, b)
    upsert_sample(conn, c)
    matches = find_by_filename(conn, "kick.wav")
    assert {m.path for m in matches} == {str(a.resolve()), str(b.resolve())}
    assert all(isinstance(m, SampleRow) for m in matches)


def test_delete_sample(tmp_path):
    conn = open_db(tmp_path / "c.db")
    p = tmp_path / "a.wav"
    p.write_bytes(b"x")
    upsert_sample(conn, p)
    delete_sample(conn, p)
    assert conn.execute("SELECT COUNT(*) FROM samples").fetchone()[0] == 0
```

**Step 2: Run, expect FAIL**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_samples_index.py -v`

**Step 3: Implement**

```python
# packages/core/audio_core/samples.py
from __future__ import annotations

import sqlite3
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class SampleRow:
    id: int
    path: str
    filename: str
    size_bytes: int
    mtime: float
    parent_dir: str


def upsert_sample(conn: sqlite3.Connection, path: str | Path) -> None:
    """Index one audio file. Idempotent. Stat failure → skip silently (the
    file vanished mid-walk; next pass picks it up or it stays absent)."""
    p = Path(path).resolve()
    try:
        st = p.stat()
    except OSError:
        return
    conn.execute(
        "INSERT INTO samples (path, filename, size_bytes, mtime, parent_dir) "
        "VALUES (?, ?, ?, ?, ?) "
        "ON CONFLICT(path) DO UPDATE SET "
        "filename=excluded.filename, size_bytes=excluded.size_bytes, "
        "mtime=excluded.mtime, parent_dir=excluded.parent_dir",
        (str(p), p.name, st.st_size, st.st_mtime, str(p.parent)),
    )
    conn.commit()


def delete_sample(conn: sqlite3.Connection, path: str | Path) -> None:
    p = Path(path).resolve()
    conn.execute("DELETE FROM samples WHERE path=?", (str(p),))
    conn.commit()


def find_by_filename(conn: sqlite3.Connection, filename: str) -> list[SampleRow]:
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
        "SELECT id, path, filename, size_bytes, mtime, parent_dir "
        "FROM samples WHERE filename = ? ORDER BY mtime DESC, path ASC",
        (filename,),
    ).fetchall()
    return [SampleRow(**dict(r)) for r in rows]
```

**Step 4: Run, expect PASS**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_samples_index.py -v`

**Step 5: Commit**

```bash
git add packages/core/audio_core/samples.py packages/core/tests/test_samples_index.py
git commit -m "feat(core/samples): upsert_sample + find_by_filename"
```

---

## Task 5: Scanner captures `size_bytes` into `project_samples`

**Files:**
- Modify: `packages/core/audio_core/db/projects.py` — extend `_sample_exists` to return size, change `project_samples` insert to include size.
- Test: `packages/core/tests/test_db_project_sample_size.py`

**Step 1: Write the failing test**

```python
# packages/core/tests/test_db_project_sample_size.py
import shutil
from pathlib import Path

from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_one


def test_existing_sample_persists_size(tmp_path):
    proj = tmp_path / "p Project"
    proj.mkdir()
    fixtures = Path(__file__).parent / "fixtures"
    shutil.copy(fixtures / "tiny.als", proj / "p.als")
    sample = proj / "Samples" / "kick.wav"
    sample.parent.mkdir()
    sample.write_bytes(b"\x00" * 1234)

    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, proj / "p.als")
    rows = conn.execute(
        "SELECT sample_path, is_missing, size_bytes FROM project_samples WHERE project_id=?",
        (pid,),
    ).fetchall()
    by_name = {Path(r[0]).name: r for r in rows}
    if "kick.wav" in by_name:  # tiny.als may or may not reference kick.wav
        r = by_name["kick.wav"]
        assert r[1] == 0
        assert r[2] == 1234


def test_missing_sample_size_is_null(tmp_path):
    """Samples that don't exist on disk get NULL size_bytes (we never had a
    chance to stat them). The relink matcher treats NULL as 'no size info'."""
    proj = tmp_path / "p Project"
    proj.mkdir()
    fixtures = Path(__file__).parent / "fixtures"
    shutil.copy(fixtures / "tiny.als", proj / "p.als")
    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, proj / "p.als")
    rows = conn.execute(
        "SELECT is_missing, size_bytes FROM project_samples WHERE project_id=?",
        (pid,),
    ).fetchall()
    for is_missing, size in rows:
        if is_missing == 1:
            assert size is None
```

**Step 2: Run, expect FAIL**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_db_project_sample_size.py -v`

**Step 3: Modify `_sample_exists` and the upsert**

In `packages/core/audio_core/db/projects.py`:

```python
def _sample_exists_with_size(sample_path: str, parent_dir: str) -> tuple[bool, int | None]:
    try:
        p = Path(sample_path)
        if not p.is_absolute():
            p = Path(parent_dir) / p
        if p.is_file():
            return True, p.stat().st_size
        return False, None
    except (OSError, ValueError):
        return False, None
```

Replace the `project_samples` `executemany` block with:

```python
sample_rows = []
for s in meta.samples:
    exists, size = _sample_exists_with_size(s.path, parent_dir)
    sample_rows.append((pid, s.path, 0 if exists else 1, size))
conn.executemany(
    "INSERT INTO project_samples (project_id, sample_path, is_missing, size_bytes) "
    "VALUES (?, ?, ?, ?)",
    sample_rows,
)
```

Keep `_sample_exists` if anything else uses it; if grep shows no other callers, replace inline.

**Step 4: Run, expect PASS**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_db_project_sample_size.py -v`
Run: `uv run --project Z:/User/audio pytest packages/core/tests/ -k "scanner or projects" -v`

**Step 5: Commit**

```bash
git add packages/core/audio_core/db/projects.py packages/core/tests/test_db_project_sample_size.py
git commit -m "feat(core/db): record size_bytes for existing project samples"
```

---

## Task 6: Indexer jobs — `FullSampleScan`, `IncrementalSampleScan`

**Files:**
- Modify: `packages/core/audio_core/indexer/jobs.py`
- Test: `packages/core/tests/test_indexer_sample_jobs.py`

**Step 1: Write the failing test**

```python
# packages/core/tests/test_indexer_sample_jobs.py
from pathlib import Path

from audio_core.db.connection import open_db
from audio_core.indexer.events import EventBus
from audio_core.indexer.jobs import FullSampleScan, IncrementalSampleScan


def test_full_sample_scan_indexes_root(tmp_path):
    db = tmp_path / "c.db"
    open_db(db).close()
    root = tmp_path / "samples"
    root.mkdir()
    (root / "a.wav").write_bytes(b"x")
    (root / "b.aif").write_bytes(b"yy")
    bus = EventBus()
    FullSampleScan(db_path=db, roots=[root], bus=bus)()
    conn = open_db(db)
    rows = list(conn.execute("SELECT filename, size_bytes FROM samples ORDER BY filename"))
    assert rows == [("a.wav", 1), ("b.aif", 2)]


def test_full_sample_scan_emits_events(tmp_path):
    db = tmp_path / "c.db"
    open_db(db).close()
    root = tmp_path / "samples"
    root.mkdir()
    (root / "a.wav").write_bytes(b"x")
    bus = EventBus()
    seen = []
    q = bus.subscribe()
    FullSampleScan(db_path=db, roots=[root], bus=bus)()
    while not q.empty():
        seen.append(q.get_nowait())
    kinds = [e["kind"] for e in seen]
    assert "sample_scan_started" in kinds
    assert "sample_scan_finished" in kinds


def test_incremental_create_modify_delete(tmp_path):
    db = tmp_path / "c.db"
    open_db(db).close()
    bus = EventBus()
    p = tmp_path / "a.wav"
    p.write_bytes(b"x")

    IncrementalSampleScan(db_path=db, paths=[p], bus=bus)()
    conn = open_db(db)
    assert conn.execute("SELECT size_bytes FROM samples WHERE path=?", (str(p.resolve()),)).fetchone()[0] == 1

    p.write_bytes(b"yy")
    IncrementalSampleScan(db_path=db, paths=[p], bus=bus)()
    assert conn.execute("SELECT size_bytes FROM samples WHERE path=?", (str(p.resolve()),)).fetchone()[0] == 2

    p.unlink()
    IncrementalSampleScan(db_path=db, paths=[p], bus=bus)()
    assert conn.execute("SELECT COUNT(*) FROM samples WHERE path=?", (str(p.resolve()),)).fetchone()[0] == 0
```

**Step 2: Run, expect FAIL**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_indexer_sample_jobs.py -v`

**Step 3: Implement**

Append to `packages/core/audio_core/indexer/jobs.py`:

```python
@dataclass
class FullSampleScan:
    """Walk every configured sample root and upsert a row per audio file.
    Idempotent. Emits sample_scan_started / sample_scan_progress / sample_scan_finished."""

    db_path: Path
    roots: list[Path]
    bus: EventBus

    def __call__(self) -> None:
        from audio_core.samples import upsert_sample
        from audio_core.scanner.walker import walk_samples

        conn = sqlite3.connect(self.db_path)
        try:
            self.bus.publish({"kind": "sample_scan_started", "roots": [str(r) for r in self.roots]})
            done = 0
            for root in self.roots:
                for p in walk_samples(root):
                    try:
                        upsert_sample(conn, p)
                    except Exception:
                        # one bad file shouldn't kill the pass
                        pass
                    done += 1
                    if done % 100 == 0:
                        self.bus.publish({"kind": "sample_scan_progress", "done": done})
            self.bus.publish({"kind": "sample_scan_finished", "done": done})
        finally:
            conn.close()


@dataclass
class IncrementalSampleScan:
    db_path: Path
    paths: list[Path]
    bus: EventBus

    def __call__(self) -> None:
        from audio_core.samples import delete_sample, upsert_sample

        conn = sqlite3.connect(self.db_path)
        try:
            for p in self.paths:
                if p.exists():
                    upsert_sample(conn, p)
                    self.bus.publish({"kind": "sample_row", "path": str(p), "status": "updated"})
                else:
                    delete_sample(conn, p)
                    self.bus.publish({"kind": "sample_row", "path": str(p), "status": "removed"})
        finally:
            conn.close()
```

**Step 4: Run, expect PASS**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_indexer_sample_jobs.py -v`

**Step 5: Commit**

```bash
git add packages/core/audio_core/indexer/jobs.py packages/core/tests/test_indexer_sample_jobs.py
git commit -m "feat(core/indexer): FullSampleScan + IncrementalSampleScan jobs"
```

---

## Task 7: Watcher — react to audio-file events on configured roots

**Files:**
- Modify: `packages/core/audio_core/indexer/watcher.py`
- Test: `packages/core/tests/test_indexer_watcher_samples.py`

**Step 0: Read the existing watcher tests**

Run: `Read packages/core/tests/test_indexer_watcher.py packages/core/tests/test_indexer_watcher_live_save.py` so the new test follows the same fixture patterns (file write + small sleep + `_pending` flush).

**Step 1: Write the failing test**

```python
# packages/core/tests/test_indexer_watcher_samples.py
import time
from pathlib import Path

from audio_core.indexer.events import EventBus
from audio_core.indexer.queue import JobQueue
from audio_core.indexer.watcher import FsWatcher


def test_audio_file_create_enqueues_incremental_sample(tmp_path):
    db = tmp_path / "c.db"
    bus = EventBus()
    q = JobQueue()
    submitted: list = []
    real_submit = q.submit
    q.submit = lambda fn: (submitted.append(fn), real_submit(fn))[1]
    q.start()

    w = FsWatcher(
        root=tmp_path,
        queue=q,
        bus=bus,
        db_path=db,
        debounce_s=0.1,
        sample_roots=[tmp_path],
    )
    try:
        w.start()
        (tmp_path / "k.wav").write_bytes(b"x")
        time.sleep(0.5)
    finally:
        w.stop()
        q.shutdown(wait=True)

    from audio_core.indexer.jobs import IncrementalSampleScan
    assert any(isinstance(s, IncrementalSampleScan) for s in submitted)
```

**Step 2: Run, expect FAIL**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_indexer_watcher_samples.py -v`

**Step 3: Extend `FsWatcher`**

Modify `packages/core/audio_core/indexer/watcher.py`:

- Constructor: add `sample_roots: list[Path] | None = None` kwarg, store `self._sample_roots = sample_roots or []`.
- Add a `_pending_samples: dict[str, float]` dict + companion lock-protected access.
- `_Handler._maybe`: also check `path.lower().endswith(tuple(SAMPLE_EXTENSIONS_LOWER))` (where `SAMPLE_EXTENSIONS_LOWER` is the lowercased `walker.SAMPLE_EXTENSIONS`); if so, route to `self._owner._touch_sample(path)`.
- `start()`: also `observer.schedule(_Handler(self), str(root), recursive=True)` for every `sample_roots` entry that's not already a subtree of the project root.
- `_flush_loop`: in addition to the .als pending dict, drain `_pending_samples` and submit `IncrementalSampleScan(db_path=..., paths=ready, bus=...)`.

Sketch (consolidating only the new bits — keep all existing logic):

```python
from audio_core.scanner.walker import SAMPLE_EXTENSIONS

_SAMPLE_EXT_LOWER = tuple(SAMPLE_EXTENSIONS)


class _Handler(FileSystemEventHandler):
    # ... existing ...
    def _maybe(self, path: str) -> None:
        low = path.lower()
        if low.endswith(".als"):
            self._owner._touch(path)
        elif low.endswith(_SAMPLE_EXT_LOWER):
            self._owner._touch_sample(path)


class FsWatcher:
    def __init__(self, *, ..., sample_roots: list[Path] | None = None, ...):
        # ...existing...
        self._sample_roots = list(sample_roots or [])
        self._pending_samples: dict[str, float] = {}

    def _touch_sample(self, path: str) -> None:
        with self._lock:
            self._pending_samples[path] = time.monotonic() + self._debounce_s

    def start(self) -> None:
        # ... existing local-drive branch ...
        if self._drive_check(self._root):
            # ... existing schedule on self._root ...
            for extra in self._sample_roots:
                # don't double-schedule a path already covered by self._root
                try:
                    extra.relative_to(self._root)
                    continue
                except ValueError:
                    pass
                self._observer.schedule(_Handler(self), str(extra), recursive=True)
            # ... existing flusher start ...

    def _flush_loop(self) -> None:
        from audio_core.indexer.jobs import IncrementalSampleScan
        while not self._stop.wait(self._poll_s):
            now = time.monotonic()
            ready: list[str] = []
            ready_samples: list[str] = []
            with self._lock:
                for path, deadline in list(self._pending.items()):
                    if deadline <= now:
                        ready.append(path)
                        del self._pending[path]
                for path, deadline in list(self._pending_samples.items()):
                    if deadline <= now:
                        ready_samples.append(path)
                        del self._pending_samples[path]
            if ready:
                self._queue.submit(IncrementalScan(db_path=self._db_path, paths=[Path(p) for p in ready], bus=self._bus))
            if ready_samples:
                self._queue.submit(IncrementalSampleScan(db_path=self._db_path, paths=[Path(p) for p in ready_samples], bus=self._bus))
```

**Step 4: Run, expect PASS**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_indexer_watcher_samples.py packages/core/tests/test_indexer_watcher.py packages/core/tests/test_indexer_watcher_live_save.py -v`

**Step 5: Commit**

```bash
git add packages/core/audio_core/indexer/watcher.py packages/core/tests/test_indexer_watcher_samples.py
git commit -m "feat(core/indexer): watcher reacts to audio-file events on sample_roots"
```

---

## Task 8: Driver `boot()` submits sample scan; web app passes `sample_roots`

**Files:**
- Modify: `packages/core/audio_core/indexer/driver.py`
- Modify: `packages/web/audio_web/app.py`
- Test: `packages/core/tests/test_indexer_driver_samples.py`
- Test: `packages/web/tests/test_lifespan_sample_roots.py`

**Step 1: Write the failing tests**

```python
# packages/core/tests/test_indexer_driver_samples.py
from pathlib import Path

from audio_core.db.connection import open_db
from audio_core.indexer import driver
from audio_core.indexer.events import EventBus
from audio_core.indexer.jobs import FullSampleScan
from audio_core.indexer.queue import JobQueue


def test_boot_submits_full_sample_scan(tmp_path):
    db = tmp_path / "c.db"
    open_db(db).close()
    root = tmp_path / "Projects"
    root.mkdir()
    extra = tmp_path / "Samples"
    extra.mkdir()
    bus = EventBus()
    q = JobQueue()
    submitted: list = []
    q.submit = lambda fn: submitted.append(fn)
    driver.boot(db_path=db, root=root, bus=bus, queue=q, sample_roots=[extra])
    assert any(isinstance(s, FullSampleScan) for s in submitted)
    fss = next(s for s in submitted if isinstance(s, FullSampleScan))
    assert root in fss.roots
    assert extra in fss.roots
```

```python
# packages/web/tests/test_lifespan_sample_roots.py
from fastapi.testclient import TestClient


def test_watcher_started_with_sample_roots(monkeypatch, tmp_path):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    (tmp_path / "Projects").mkdir()
    extra = tmp_path / "Samples"
    extra.mkdir()
    (tmp_path / "config.toml").write_text(
        f'sample_roots = ["{extra.as_posix()}"]\n', encoding="utf-8"
    )

    from audio_web.app import create_app
    app = create_app()
    with TestClient(app) as client:
        client.get("/api/health")
        watcher = app.state.fs_watcher
        assert extra.resolve() in [Path(p).resolve() for p in watcher._sample_roots]
```

(Adjust import path if needed.)

**Step 2: Run, expect FAIL**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_indexer_driver_samples.py packages/web/tests/test_lifespan_sample_roots.py -v`

**Step 3: Implement**

In `packages/core/audio_core/indexer/driver.py`, change `boot`:

```python
def boot(
    *,
    db_path: Path,
    root: Path,
    bus: EventBus,
    queue: JobQueue,
    sample_roots: list[Path] | None = None,
) -> None:
    from audio_core.indexer.jobs import BackfillColumn, FullSampleScan, FullScan

    conn = open_db(db_path)
    try:
        pending = needs_backfill(conn)
    finally:
        conn.close()
    for name in pending:
        queue.submit(BackfillColumn(db_path=db_path, spec_name=name, bus=bus))
    queue.submit(FullScan(db_path=db_path, root=root, bus=bus))
    all_sample_roots = [root, *(sample_roots or [])]
    queue.submit(FullSampleScan(db_path=db_path, roots=all_sample_roots, bus=bus))
```

In `packages/web/audio_web/app.py::_lifespan`, after `from audio_core.config import ...`, add `sample_roots`:

```python
from audio_core.config import db_path, journal_dir, projects_root, sample_roots, workspace_root
# ...
roots = sample_roots()
driver.boot(db_path=db_path(), root=projects_root(), bus=bus, queue=queue, sample_roots=roots)
watcher = FsWatcher(
    root=projects_root(),
    queue=queue,
    bus=bus,
    db_path=db_path(),
    sample_roots=roots,
)
```

**Step 4: Run, expect PASS**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_indexer_driver_samples.py packages/web/tests/test_lifespan_sample_roots.py -v`

**Step 5: Commit**

```bash
git add packages/core/audio_core/indexer/driver.py packages/web/audio_web/app.py packages/core/tests/test_indexer_driver_samples.py packages/web/tests/test_lifespan_sample_roots.py
git commit -m "feat(indexer/web): boot sample indexer with configured roots"
```

---

## Task 9: `relink.py` — find missing samples + matcher + proposal builder

**Files:**
- Create: `packages/core/audio_core/relink.py`
- Test: `packages/core/tests/test_relink.py`

**Step 1: Write the failing test**

```python
# packages/core/tests/test_relink.py
import shutil
from pathlib import Path

from audio_core.db.connection import open_db
from audio_core.relink import (
    MissingSampleFinding,
    SampleCandidate,
    build_relink_proposal,
    find_missing_samples,
)
from audio_core.samples import upsert_sample
from audio_core.scanner.scan import scan_one


def _seed_proj_with_missing(tmp_path, name="p"):
    fixtures = Path(__file__).parent / "fixtures"
    proj = tmp_path / f"{name} Project"
    proj.mkdir()
    shutil.copy(fixtures / "tiny.als", proj / f"{name}.als")
    return proj / f"{name}.als"


def test_clean_project_no_findings(tmp_path):
    """A project with all samples present (or none referenced) returns []."""
    conn = open_db(tmp_path / "c.db")
    scan_one(conn, _seed_proj_with_missing(tmp_path, "clean"))
    # tiny.als references no samples → no missing-sample findings
    findings = find_missing_samples(conn)
    assert findings == [] or all(not f.candidates for f in findings)


def test_single_filename_match_is_auto(tmp_path):
    """When project_samples has a missing entry for kick.wav and exactly one
    sample row matches that filename, finding.auto_match is set."""
    conn = open_db(tmp_path / "c.db")
    proj = tmp_path / "p Project"
    proj.mkdir()
    fixtures = Path(__file__).parent / "fixtures"
    shutil.copy(fixtures / "tiny.als", proj / "p.als")
    pid = scan_one(conn, proj / "p.als")
    # Hand-insert a missing project_sample row
    conn.execute(
        "INSERT INTO project_samples (project_id, sample_path, is_missing, size_bytes) "
        "VALUES (?, ?, 1, NULL)",
        (pid, "Samples/kick.wav"),
    )
    conn.commit()
    # Index one matching candidate elsewhere
    cand = tmp_path / "lib" / "kick.wav"
    cand.parent.mkdir()
    cand.write_bytes(b"x")
    upsert_sample(conn, cand)

    findings = find_missing_samples(conn)
    assert len(findings) == 1
    f = findings[0]
    assert f.project_id == pid
    assert f.missing_path == "Samples/kick.wav"
    assert f.auto_match is not None
    assert f.auto_match.path == str(cand.resolve())


def test_multiple_filename_matches_no_auto(tmp_path):
    conn = open_db(tmp_path / "c.db")
    proj = tmp_path / "p Project"
    proj.mkdir()
    fixtures = Path(__file__).parent / "fixtures"
    shutil.copy(fixtures / "tiny.als", proj / "p.als")
    pid = scan_one(conn, proj / "p.als")
    conn.execute(
        "INSERT INTO project_samples (project_id, sample_path, is_missing, size_bytes) "
        "VALUES (?, ?, 1, NULL)",
        (pid, "kick.wav"),
    )
    conn.commit()
    a = tmp_path / "a" / "kick.wav"
    b = tmp_path / "b" / "kick.wav"
    a.parent.mkdir()
    b.parent.mkdir()
    a.write_bytes(b"x")
    b.write_bytes(b"yy")
    upsert_sample(conn, a)
    upsert_sample(conn, b)

    findings = find_missing_samples(conn)
    assert len(findings) == 1
    f = findings[0]
    assert f.auto_match is None
    assert {c.path for c in f.candidates} == {str(a.resolve()), str(b.resolve())}


def test_zero_matches(tmp_path):
    conn = open_db(tmp_path / "c.db")
    proj = tmp_path / "p Project"
    proj.mkdir()
    fixtures = Path(__file__).parent / "fixtures"
    shutil.copy(fixtures / "tiny.als", proj / "p.als")
    pid = scan_one(conn, proj / "p.als")
    conn.execute(
        "INSERT INTO project_samples (project_id, sample_path, is_missing, size_bytes) "
        "VALUES (?, ?, 1, NULL)",
        (pid, "ghost.wav"),
    )
    conn.commit()
    findings = find_missing_samples(conn)
    f = next(f for f in findings if f.missing_path == "ghost.wav")
    assert f.auto_match is None
    assert f.candidates == []


def test_archived_excluded(tmp_path):
    conn = open_db(tmp_path / "c.db")
    proj = tmp_path / "p Project"
    proj.mkdir()
    fixtures = Path(__file__).parent / "fixtures"
    shutil.copy(fixtures / "tiny.als", proj / "p.als")
    pid = scan_one(conn, proj / "p.als")
    conn.execute(
        "INSERT INTO project_samples (project_id, sample_path, is_missing, size_bytes) "
        "VALUES (?, ?, 1, NULL)",
        (pid, "kick.wav"),
    )
    conn.execute("UPDATE projects SET is_archived=1 WHERE id=?", (pid,))
    conn.commit()
    findings = find_missing_samples(conn)
    assert findings == []


def test_build_relink_proposal(tmp_path):
    findings = [
        MissingSampleFinding(
            project_id=10,
            project_path="/p1.als",
            project_name="p1",
            missing_path="kick.wav",
            candidates=[SampleCandidate(path="/lib/kick.wav", filename="kick.wav", size_bytes=1, mtime=0.0)],
            auto_match=SampleCandidate(path="/lib/kick.wav", filename="kick.wav", size_bytes=1, mtime=0.0),
        ),
        MissingSampleFinding(
            project_id=10,
            project_path="/p1.als",
            project_name="p1",
            missing_path="snare.wav",
            candidates=[SampleCandidate(path="/lib/snare1.wav", filename="snare.wav", size_bytes=2, mtime=0.0),
                        SampleCandidate(path="/lib/snare2.wav", filename="snare.wav", size_bytes=3, mtime=0.0)],
            auto_match=None,
        ),
    ]
    # auto_match is used when no explicit pick. snare.wav has no auto and no pick → omitted.
    actions = build_relink_proposal(findings, picks={})
    assert actions == [
        {
            "type": "RelinkMissingSamples",
            "args": {"project_id": 10, "relinks": [{"old": "kick.wav", "new": "/lib/kick.wav"}]},
        }
    ]
    # With an explicit pick for snare.wav both are included; one action per project.
    actions = build_relink_proposal(findings, picks={"snare.wav": "/lib/snare2.wav"})
    assert actions == [
        {
            "type": "RelinkMissingSamples",
            "args": {
                "project_id": 10,
                "relinks": [
                    {"old": "kick.wav", "new": "/lib/kick.wav"},
                    {"old": "snare.wav", "new": "/lib/snare2.wav"},
                ],
            },
        }
    ]
```

**Step 2: Run, expect FAIL**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_relink.py -v`

**Step 3: Implement**

```python
# packages/core/audio_core/relink.py
from __future__ import annotations

import sqlite3
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

from audio_core.samples import find_by_filename


@dataclass(frozen=True)
class SampleCandidate:
    path: str
    filename: str
    size_bytes: int
    mtime: float


@dataclass(frozen=True)
class MissingSampleFinding:
    project_id: int
    project_path: str
    project_name: str
    missing_path: str
    candidates: list[SampleCandidate] = field(default_factory=list)
    auto_match: SampleCandidate | None = None


def _basename(path: str) -> str:
    return path.rsplit("/", 1)[-1].rsplit("\\", 1)[-1]


def find_missing_samples(conn: sqlite3.Connection) -> list[MissingSampleFinding]:
    """One finding per (project, missing sample). Auto-match is set when the
    `samples` index has exactly one row whose filename matches the missing
    basename. Archived projects are excluded.
    """
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
        """
        SELECT ps.project_id, ps.sample_path, p.path AS project_path, p.name AS project_name
        FROM project_samples ps
        JOIN projects p ON p.id = ps.project_id
        WHERE ps.is_missing = 1
          AND COALESCE(p.is_archived, 0) = 0
        ORDER BY ps.project_id, ps.sample_path
        """
    ).fetchall()
    findings: list[MissingSampleFinding] = []
    for r in rows:
        fname = _basename(r["sample_path"])
        sample_rows = find_by_filename(conn, fname)
        candidates = [
            SampleCandidate(
                path=s.path,
                filename=s.filename,
                size_bytes=s.size_bytes,
                mtime=s.mtime,
            )
            for s in sample_rows
        ]
        auto = candidates[0] if len(candidates) == 1 else None
        findings.append(
            MissingSampleFinding(
                project_id=r["project_id"],
                project_path=r["project_path"],
                project_name=r["project_name"],
                missing_path=r["sample_path"],
                candidates=candidates,
                auto_match=auto,
            )
        )
    return findings


def build_relink_proposal(
    findings: list[MissingSampleFinding],
    picks: dict[str, str],
) -> list[dict]:
    """Group findings by project_id. Each project becomes one
    RelinkMissingSamples action with a list of (old, new) relinks. A finding
    is included if it has either an auto_match or an explicit pick keyed by
    its missing_path. Findings without resolution are silently skipped — the
    UI is responsible for not selecting them."""
    by_proj: dict[int, list[tuple[str, str]]] = defaultdict(list)
    for f in findings:
        new_path: str | None = None
        if f.missing_path in picks:
            new_path = picks[f.missing_path]
        elif f.auto_match is not None:
            new_path = f.auto_match.path
        if new_path:
            by_proj[f.project_id].append((f.missing_path, new_path))
    return [
        {
            "type": "RelinkMissingSamples",
            "args": {
                "project_id": pid,
                "relinks": [{"old": old, "new": new} for old, new in items],
            },
        }
        for pid, items in by_proj.items()
    ]
```

**Step 4: Run, expect PASS**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_relink.py -v`

**Step 5: Commit**

```bash
git add packages/core/audio_core/relink.py packages/core/tests/test_relink.py
git commit -m "feat(core/relink): find_missing_samples + build_relink_proposal"
```

---

## Task 10: `RelinkMissingSamples` action

**Files:**
- Create: `packages/core/audio_core/actions/relink_missing_samples.py`
- Test: `packages/core/tests/test_actions_relink_missing_samples.py`

**Step 0: Build a fixture with a known-broken `<SampleRef>`**

The catalog ships `tiny.als` (no samples) and `mac_imported_tiny.als`. We need a third with at least one `<SampleRef>` whose path resolves to a missing file. Generate it once:

```python
# scripts/build_relink_fixture.py  (run-once, do not commit)
import gzip
from pathlib import Path
from lxml import etree

base = Path("packages/core/tests/fixtures/tiny.als")
out  = Path("packages/core/tests/fixtures/missing_sample_tiny.als")

with gzip.open(base, "rb") as fh:
    tree = etree.parse(fh, etree.XMLParser(huge_tree=True))
root = tree.getroot()

# Find any AudioTrack and inject a SampleRef pointing at a non-existent file.
# If tiny.als has no AudioClip skeleton to graft into, the cheapest path is
# to clone an existing FileRef and re-target its <Path Value>.
file_refs = list(root.iter("FileRef"))
assert file_refs, "tiny.als has no FileRef to clone — pick a different base fixture"
fr = file_refs[0]
# Wrap a copy in a SampleRef inside an AudioClip-ish ancestor.
# (Detail: see _fix_kiki.py for the structure; we just need the parser to
# pick it up as a sample path.)
new = etree.SubElement(root, "SampleRef")
file_clone = etree.SubElement(new, "FileRef")
path_node = etree.SubElement(file_clone, "Path")
path_node.set("Value", "Samples/relink_test_kick.wav")
has_rel = etree.SubElement(file_clone, "HasRelativePath")
has_rel.set("Value", "true")
rel_path = etree.SubElement(file_clone, "RelativePath")
rel_path.set("Value", "Samples/relink_test_kick.wav")

with gzip.open(out, "wb") as fh:
    tree.write(fh, xml_declaration=True, encoding="UTF-8")
print("ok")
```

Run, verify `parse_als(out).samples` contains a sample with `path` ending in `relink_test_kick.wav`. If the parser doesn't pick it up, adjust the wrapping until it does — `audio_core/parser/als.py` is the source of truth for what counts as a sample. Then delete the script and commit only the .als artifact.

**Step 1: Write the failing test**

```python
# packages/core/tests/test_actions_relink_missing_samples.py
import gzip
import shutil
from pathlib import Path

import pytest
from lxml import etree

from audio_core.actions.relink_missing_samples import Relink, RelinkMissingSamples
from audio_core.actions.runner import run_batch
from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_one


def _seed_relink_project(tmp_path):
    fixtures = Path(__file__).parent / "fixtures"
    root = tmp_path / "Projects"
    proj = root / "p Project"
    proj.mkdir(parents=True)
    shutil.copy(fixtures / "missing_sample_tiny.als", proj / "x.als")
    # Index a candidate file that should match.
    cand = tmp_path / "lib" / "relink_test_kick.wav"
    cand.parent.mkdir()
    cand.write_bytes(b"\x00" * 42)

    conn = open_db(tmp_path / "data" / "catalog.db")
    pid = scan_one(conn, proj / "x.als")
    return tmp_path, root, conn, pid, proj / "x.als", cand


def test_relink_rewrites_path_and_clears_missing(tmp_path):
    base, root, conn, pid, als, cand = _seed_relink_project(tmp_path)
    # Confirm the project has a missing sample referencing relink_test_kick.wav.
    miss_rows = conn.execute(
        "SELECT sample_path FROM project_samples WHERE project_id=? AND is_missing=1",
        (pid,),
    ).fetchall()
    missing_path = next(r[0] for r in miss_rows if "relink_test_kick.wav" in r[0])

    run_batch(
        conn,
        [
            RelinkMissingSamples(
                project_id=pid,
                relinks=[Relink(old=missing_path, new=str(cand))],
                root=root,
            )
        ],
        actor="test",
        journal_dir=base / "data" / "journal",
    )

    # Backup left in place.
    assert (als.with_suffix(".als.bak")).is_file()

    # The .als now has the new absolute path on the corresponding FileRef.
    with gzip.open(als, "rb") as fh:
        xroot = etree.parse(fh, etree.XMLParser(huge_tree=True)).getroot()
    paths = [
        n.get("Value", "")
        for n in xroot.iter("Path")
        if n.getparent() is not None and n.getparent().tag == "FileRef"
    ]
    assert any(str(cand) in p or str(cand.resolve()) in p for p in paths)

    # project_samples now reflects the relink (no longer missing for that path).
    after = conn.execute(
        "SELECT is_missing FROM project_samples WHERE project_id=? AND sample_path=?",
        (pid, str(cand.resolve())),
    ).fetchone()
    assert after is not None and after[0] == 0


def test_relink_idempotent(tmp_path):
    base, root, conn, pid, als, cand = _seed_relink_project(tmp_path)
    missing_path = conn.execute(
        "SELECT sample_path FROM project_samples WHERE project_id=? AND is_missing=1 LIMIT 1",
        (pid,),
    ).fetchone()[0]
    args = dict(project_id=pid, relinks=[Relink(old=missing_path, new=str(cand))], root=root)
    run_batch(conn, [RelinkMissingSamples(**args)], actor="test", journal_dir=base / "data" / "journal")
    # Second run: nothing left to relink → no-op (no raise).
    run_batch(conn, [RelinkMissingSamples(**args)], actor="test", journal_dir=base / "data" / "journal")


def test_relink_refuses_outside_root(tmp_path):
    base, root, conn, pid, als, cand = _seed_relink_project(tmp_path)
    bogus = tmp_path / "elsewhere"
    bogus.mkdir()
    a = RelinkMissingSamples(
        project_id=pid,
        relinks=[Relink(old="anything", new=str(cand))],
        root=bogus,
    )
    with pytest.raises(Exception):
        a.validate(conn)


def test_relink_refuses_when_live_open(tmp_path, monkeypatch):
    base, root, conn, pid, als, cand = _seed_relink_project(tmp_path)
    monkeypatch.setattr(
        "audio_core.actions.relink_missing_samples.is_open_in_live", lambda _p: True
    )
    a = RelinkMissingSamples(
        project_id=pid,
        relinks=[Relink(old="anything", new=str(cand))],
        root=root,
    )
    with pytest.raises(RuntimeError):
        a.validate(conn)


def test_relink_refuses_missing_candidate(tmp_path):
    base, root, conn, pid, als, cand = _seed_relink_project(tmp_path)
    cand.unlink()
    missing_path = conn.execute(
        "SELECT sample_path FROM project_samples WHERE project_id=? AND is_missing=1 LIMIT 1",
        (pid,),
    ).fetchone()[0]
    a = RelinkMissingSamples(
        project_id=pid,
        relinks=[Relink(old=missing_path, new=str(cand))],
        root=root,
    )
    with pytest.raises(FileNotFoundError):
        a.validate(conn)
```

**Step 2: Run, expect FAIL**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_actions_relink_missing_samples.py -v`

**Step 3: Implement**

```python
# packages/core/audio_core/actions/relink_missing_samples.py
from __future__ import annotations

import gzip
import shutil
import sqlite3
from dataclasses import dataclass
from pathlib import Path

from lxml import etree

from audio_core.safety.live_lock import is_open_in_live
from audio_core.safety.paths import ensure_within


@dataclass(frozen=True)
class Relink:
    old: str
    new: str


@dataclass
class RelinkMissingSamples:
    """Rewrite missing-sample paths in a .als to point at indexed candidates.

    For each (old, new) pair, find every <FileRef> whose <Path Value> resolves
    to `old` (treating `old` as either the recorded sample_path, or its
    basename), and replace it with the absolute `new` path; clear
    <HasRelativePath> so Live re-resolves on save.

    Backup convention matches RepairMacPaths: <stem>.als.bak created if absent.
    Atomic temp-replace. Refuses to run if Live has the file open or the
    project's parent_dir is outside `root`. Each `new` candidate must exist on
    disk at validate-time.
    """

    project_id: int
    relinks: list[Relink]
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
        for r in self.relinks:
            if not Path(r.new).is_file():
                raise FileNotFoundError(f"relink candidate missing: {r.new}")

    def execute(self, conn: sqlite3.Connection) -> dict:
        row = self._row(conn)
        als = Path(row["path"])
        bak = als.with_suffix(".als.bak")

        if not self.relinks:
            return {"type": "RelinkMissingSamples", "project_id": self.project_id, "path": str(als), "noop": True}

        # 1) Backup.
        if not bak.exists():
            shutil.copy2(als, bak)

        # 2) Read XML, rewrite matching FileRefs.
        with gzip.open(als, "rb") as fh:
            tree = etree.parse(fh, etree.XMLParser(huge_tree=True))
        xroot = tree.getroot()

        # Build a map old → new_absolute.
        relink_map = {r.old: str(Path(r.new).resolve()) for r in self.relinks}
        # Also index by basename so SampleRefs that store a different relative
        # form than what we recorded still match.
        basename_map: dict[str, str] = {}
        for old, new in relink_map.items():
            base = old.rsplit("/", 1)[-1].rsplit("\\", 1)[-1]
            basename_map.setdefault(base, new)

        rewritten = 0
        for fr in xroot.iter("FileRef"):
            pn = fr.find("Path")
            if pn is None:
                continue
            cur = pn.get("Value", "")
            new = relink_map.get(cur)
            if new is None:
                base = cur.rsplit("/", 1)[-1].rsplit("\\", 1)[-1]
                new = basename_map.get(base)
            if new is None:
                continue
            pn.set("Value", new)
            # Clear relative-path metadata so Live recomputes on save.
            for tag in ("RelativePath", "HasRelativePath", "RelativePathType"):
                el = fr.find(tag)
                if el is not None:
                    fr.remove(el)
            rewritten += 1

        if rewritten == 0:
            return {
                "type": "RelinkMissingSamples",
                "project_id": self.project_id,
                "path": str(als),
                "noop": True,
            }

        # 3) Atomic write.
        tmp = als.with_suffix(".als.tmp")
        with gzip.open(tmp, "wb") as fh:
            tree.write(fh, xml_declaration=True, encoding="UTF-8")
        tmp.replace(als)

        # 4) Update project_samples — mark old paths cleared, new paths present.
        for r in self.relinks:
            new_abs = str(Path(r.new).resolve())
            stat = Path(r.new).stat()
            conn.execute(
                "UPDATE project_samples SET sample_path=?, is_missing=0, size_bytes=? "
                "WHERE project_id=? AND sample_path=?",
                (new_abs, stat.st_size, self.project_id, r.old),
            )
        conn.commit()

        return {
            "type": "RelinkMissingSamples",
            "project_id": self.project_id,
            "path": str(als),
            "backup": str(bak),
            "relinks": [{"old": r.old, "new": r.new} for r in self.relinks],
            "rewritten": rewritten,
        }
```

**Step 4: Run, expect PASS**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_actions_relink_missing_samples.py -v`

**Step 5: Commit**

```bash
git add packages/core/audio_core/actions/relink_missing_samples.py packages/core/tests/test_actions_relink_missing_samples.py packages/core/tests/fixtures/missing_sample_tiny.als
git commit -m "feat(core/actions): RelinkMissingSamples rewrites <FileRef> paths"
```

---

## Task 11: Undo for `RelinkMissingSamples`

**Files:**
- Modify: `packages/core/audio_core/actions/undo.py`
- Test: append to `packages/core/tests/test_actions_relink_missing_samples.py`

**Step 1: Write the failing test**

Append:

```python
def test_undo_restores_als_from_backup(tmp_path):
    from audio_core.actions.undo import undo_batch

    base, root, conn, pid, als, cand = _seed_relink_project(tmp_path)
    pre = als.read_bytes()
    missing_path = conn.execute(
        "SELECT sample_path FROM project_samples WHERE project_id=? AND is_missing=1 LIMIT 1",
        (pid,),
    ).fetchone()[0]

    bid = run_batch(
        conn,
        [
            RelinkMissingSamples(
                project_id=pid,
                relinks=[Relink(old=missing_path, new=str(cand))],
                root=root,
            )
        ],
        actor="test",
        journal_dir=base / "data" / "journal",
    )
    assert als.read_bytes() != pre
    undo_batch(conn, base / "data" / "journal", bid)
    assert als.read_bytes() == pre
    # The post-undo project_samples row reflects the pre-relink state.
    miss = conn.execute(
        "SELECT is_missing FROM project_samples WHERE project_id=? AND sample_path=?",
        (pid, missing_path),
    ).fetchone()
    assert miss is not None and miss[0] == 1
```

**Step 2: Run, expect FAIL**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_actions_relink_missing_samples.py::test_undo_restores_als_from_backup -v`

**Step 3: Implement**

In `packages/core/audio_core/actions/undo.py`, mirror `_undo_repair_mac_paths`:

```python
def _undo_relink_missing_samples(entry: dict, conn: sqlite3.Connection) -> None:
    if entry.get("noop"):
        return
    from audio_core.scanner.scan import scan_one

    als = Path(entry["path"])
    bak = Path(entry["backup"])
    if not bak.exists():
        raise FileNotFoundError(f"cannot undo RelinkMissingSamples: backup missing at {bak}")
    shutil.copy2(bak, als)
    scan_one(conn, als)
```

Add the dispatch branch:

```python
elif t == "RelinkMissingSamples":
    _undo_relink_missing_samples(entry, conn)
    any_reversed = True
```

**Step 4: Run, expect PASS**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_actions_relink_missing_samples.py -v`

**Step 5: Commit**

```bash
git add packages/core/audio_core/actions/undo.py packages/core/tests/test_actions_relink_missing_samples.py
git commit -m "feat(core/actions): undo restores .als for RelinkMissingSamples"
```

---

## Task 12: Proposal arg schema + materialization

**Files:**
- Modify: `packages/core/audio_core/proposals/schema.py`
- Modify: `packages/web/audio_web/routes_proposals.py`
- Test: `packages/web/tests/test_proposals_relink.py`

**Step 1: Write the failing test**

```python
# packages/web/tests/test_proposals_relink.py
from fastapi.testclient import TestClient


def test_post_relink_proposal_accepted(monkeypatch, tmp_path):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    (tmp_path / "Projects").mkdir()
    (tmp_path / "data" / "proposals").mkdir(parents=True)

    from audio_web.app import create_app
    app = create_app()
    body = {
        "actor": "test",
        "rationale": "relink",
        "actions": [
            {
                "type": "RelinkMissingSamples",
                "args": {
                    "project_id": 1,
                    "relinks": [{"old": "k.wav", "new": "Z:/lib/k.wav"}],
                },
            }
        ],
    }
    with TestClient(app) as client:
        r = client.post("/api/proposals", json=body)
        assert r.status_code == 201, r.text


def test_post_relink_rejects_empty_relinks(monkeypatch, tmp_path):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    (tmp_path / "Projects").mkdir()
    (tmp_path / "data" / "proposals").mkdir(parents=True)
    from audio_web.app import create_app
    app = create_app()
    body = {
        "actor": "test",
        "actions": [{"type": "RelinkMissingSamples", "args": {"project_id": 1, "relinks": []}}],
    }
    with TestClient(app) as client:
        r = client.post("/api/proposals", json=body)
        assert r.status_code == 400
```

**Step 2: Run, expect FAIL**

Run: `uv run --project Z:/User/audio pytest packages/web/tests/test_proposals_relink.py -v`

**Step 3: Implement schema**

In `packages/core/audio_core/proposals/schema.py`:

```python
class RelinkSpec(BaseModel):
    old: str = Field(min_length=1)
    new: str = Field(min_length=1)


class RelinkMissingSamplesArgs(BaseModel):
    project_id: int
    relinks: list[RelinkSpec] = Field(min_length=1)


ARG_SCHEMAS["RelinkMissingSamples"] = RelinkMissingSamplesArgs
```

**Step 4: Implement materialization**

In `packages/web/audio_web/routes_proposals.py::_materialize`, add:

```python
if action_type == "RelinkMissingSamples":
    from audio_core.actions.relink_missing_samples import Relink, RelinkMissingSamples
    return RelinkMissingSamples(
        project_id=int(args["project_id"]),
        relinks=[Relink(old=r["old"], new=r["new"]) for r in args["relinks"]],
        root=root,
    )
```

Add the import at the top of the file alongside the other action imports.

**Step 5: Run, expect PASS**

Run: `uv run --project Z:/User/audio pytest packages/web/tests/test_proposals_relink.py -v`
Run the wider proposals suite: `uv run --project Z:/User/audio pytest packages/web/tests/ -k proposal -v`

**Step 6: Commit**

```bash
git add packages/core/audio_core/proposals/schema.py packages/web/audio_web/routes_proposals.py packages/web/tests/test_proposals_relink.py
git commit -m "feat(web/proposals): accept RelinkMissingSamples action"
```

---

## Task 13: `repair.py` facade + `GET /api/repair/findings`

**Files:**
- Create: `packages/core/audio_core/repair.py`
- Create: `packages/web/audio_web/routes_repair.py`
- Modify: `packages/web/audio_web/app.py`
- Test: `packages/core/tests/test_repair_facade.py`
- Test: `packages/web/tests/test_routes_repair.py`

**Step 1: Write the failing tests**

```python
# packages/core/tests/test_repair_facade.py
from audio_core.db.connection import open_db
from audio_core.repair import get_repair_findings


def test_empty_catalog(tmp_path):
    conn = open_db(tmp_path / "c.db")
    f = get_repair_findings(conn)
    assert f.mac_imports == []
    assert f.missing_samples == []
```

```python
# packages/web/tests/test_routes_repair.py
from fastapi.testclient import TestClient


def test_findings_endpoint(monkeypatch, tmp_path):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    (tmp_path / "Projects").mkdir()
    from audio_web.app import create_app
    with TestClient(create_app()) as client:
        r = client.get("/api/repair/findings")
        assert r.status_code == 200
        body = r.json()
        assert "mac_imports" in body
        assert "missing_samples" in body
```

**Step 2: Run, expect FAIL**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_repair_facade.py packages/web/tests/test_routes_repair.py -v`

**Step 3: Implement facade**

```python
# packages/core/audio_core/repair.py
from __future__ import annotations

import sqlite3
from dataclasses import dataclass

from audio_core.macpath import MacImportFinding, find_mac_imports
from audio_core.relink import MissingSampleFinding, find_missing_samples


@dataclass(frozen=True)
class RepairFindings:
    mac_imports: list[MacImportFinding]
    missing_samples: list[MissingSampleFinding]


def get_repair_findings(conn: sqlite3.Connection) -> RepairFindings:
    return RepairFindings(
        mac_imports=find_mac_imports(conn),
        missing_samples=find_missing_samples(conn),
    )
```

**Step 4: Implement route**

```python
# packages/web/audio_web/routes_repair.py
from __future__ import annotations

from audio_core.config import db_path
from audio_core.db.connection import open_db
from audio_core.repair import get_repair_findings
from fastapi import APIRouter

router = APIRouter(prefix="/api/repair", tags=["repair"])


@router.get("/findings")
def findings() -> dict:
    conn = open_db(db_path())
    f = get_repair_findings(conn)
    return {
        "mac_imports": [
            {
                "project_id": x.project_id,
                "path": x.path,
                "name": x.name,
                "parent_dir": x.parent_dir,
                "mac_paths_count": x.mac_paths_count,
                "project_info_missing": x.project_info_missing,
            }
            for x in f.mac_imports
        ],
        "missing_samples": [
            {
                "project_id": m.project_id,
                "project_path": m.project_path,
                "project_name": m.project_name,
                "missing_path": m.missing_path,
                "auto_match": (
                    {
                        "path": m.auto_match.path,
                        "filename": m.auto_match.filename,
                        "size_bytes": m.auto_match.size_bytes,
                    }
                    if m.auto_match
                    else None
                ),
                "candidates": [
                    {"path": c.path, "filename": c.filename, "size_bytes": c.size_bytes}
                    for c in m.candidates
                ],
            }
            for m in f.missing_samples
        ],
    }
```

In `packages/web/audio_web/app.py`, register: `from audio_web.routes_repair import router as repair_router` and `app.include_router(repair_router)`.

**Step 5: Run, expect PASS**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_repair_facade.py packages/web/tests/test_routes_repair.py -v`

**Step 6: Commit**

```bash
git add packages/core/audio_core/repair.py packages/web/audio_web/routes_repair.py packages/web/audio_web/app.py packages/core/tests/test_repair_facade.py packages/web/tests/test_routes_repair.py
git commit -m "feat(web): GET /api/repair/findings combines mac + missing-sample"
```

---

## Task 14: Findings summary includes missing samples

**Files:**
- Modify: `packages/core/audio_core/detectors.py`
- Test: `packages/core/tests/test_detectors_missing_samples.py`

**Step 1: Write the failing test**

```python
# packages/core/tests/test_detectors_missing_samples.py
from audio_core.db.connection import open_db
from audio_core.detectors import findings_summary


def test_summary_has_missing_samples_count(tmp_path):
    conn = open_db(tmp_path / "c.db")
    s = findings_summary(conn)
    assert "missing_samples" in s
    assert s["missing_samples"] == 0
```

**Step 2: Run, expect FAIL**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/test_detectors_missing_samples.py -v`

**Step 3: Implement**

In `packages/core/audio_core/detectors.py`:

```python
from audio_core.relink import find_missing_samples


def findings_summary(conn: sqlite3.Connection) -> dict[str, int]:
    return {
        "macpath": len(find_mac_imports(conn)),
        "duplicates": len(find_duplicates(conn)),
        "missing_samples": len(find_missing_samples(conn)),
    }
```

**Step 4: Run, expect PASS**

Run: `uv run --project Z:/User/audio pytest packages/core/tests/ -k "detector or finding" -v`

**Step 5: Commit**

```bash
git add packages/core/audio_core/detectors.py packages/core/tests/test_detectors_missing_samples.py
git commit -m "feat(core/detectors): include missing_samples in findings_summary"
```

---

## Task 15: CLI `audio relink-missing-samples`

**Files:**
- Modify: `packages/cli/audio_cli/main.py`
- Test: `packages/cli/tests/test_cli_relink_missing_samples.py`

**Step 0: Read the existing dedup/macpath CLI**

Run: `Read packages/cli/audio_cli/main.py` and `Read packages/cli/tests/test_cli_repair_mac_paths.py`. Mirror their structure exactly: `--json`, `--propose`, factor out `_write_proposal` if not already shared.

**Step 1: Write the failing tests**

```python
# packages/cli/tests/test_cli_relink_missing_samples.py
import json as _json
import shutil
from pathlib import Path

from audio_cli.main import app
from audio_core.db.connection import open_db
from audio_core.samples import upsert_sample
from audio_core.scanner.scan import scan_one
from typer.testing import CliRunner


def _seed(tmp_path):
    fixtures = Path(__file__).parents[2] / "core" / "tests" / "fixtures"
    proj = tmp_path / "Projects" / "p Project"
    proj.mkdir(parents=True)
    shutil.copy(fixtures / "missing_sample_tiny.als", proj / "p.als")
    cand = tmp_path / "lib" / "relink_test_kick.wav"
    cand.parent.mkdir(parents=True)
    cand.write_bytes(b"x")
    conn = open_db(tmp_path / "data" / "catalog.db")
    pid = scan_one(conn, proj / "p.als")
    upsert_sample(conn, cand)
    return pid


def test_no_findings(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    open_db(tmp_path / "data" / "catalog.db")
    res = CliRunner().invoke(app, ["relink-missing-samples"])
    assert res.exit_code == 0
    assert "No missing samples" in res.stdout


def test_lists_findings(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed(tmp_path)
    res = CliRunner().invoke(app, ["relink-missing-samples"])
    assert res.exit_code == 0
    assert str(pid) in res.stdout


def test_json(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed(tmp_path)
    res = CliRunner().invoke(app, ["relink-missing-samples", "--json"])
    assert res.exit_code == 0
    payload = _json.loads(res.stdout)
    assert isinstance(payload, list)


def test_propose_writes_only_auto_matches(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed(tmp_path)
    res = CliRunner().invoke(app, ["relink-missing-samples", "--propose"])
    assert res.exit_code == 0
    files = list((tmp_path / "data" / "proposals").glob("*.json"))
    assert len(files) == 1
    payload = _json.loads(files[0].read_text(encoding="utf-8"))
    actions = payload["actions"]
    assert all(a["type"] == "RelinkMissingSamples" for a in actions)
    assert {a["args"]["project_id"] for a in actions} == {pid}
```

**Step 2: Run, expect FAIL**

Run: `uv run --project Z:/User/audio pytest packages/cli/tests/test_cli_relink_missing_samples.py -v`

**Step 3: Add the command**

In `packages/cli/audio_cli/main.py`:

```python
from audio_core.relink import build_relink_proposal, find_missing_samples


@app.command("relink-missing-samples")
def relink_missing_samples(
    json_out: bool = typer.Option(False, "--json"),
    propose: bool = typer.Option(False, "--propose"),
) -> None:
    """List projects with missing samples; --propose drafts a batch of
    auto-match relinks (filename → exactly one indexed candidate)."""
    conn = open_db(db_path())
    findings = find_missing_samples(conn)

    if propose:
        actions = build_relink_proposal(findings, picks={})
        if not actions:
            con.print("No auto-matchable missing samples.")
            raise typer.Exit(code=0)
        _write_proposal(actions, rationale=f"relink-missing-samples: {len(actions)} project(s)")
        return

    if json_out:
        con.print_json(
            data=[
                {
                    "project_id": f.project_id,
                    "missing_path": f.missing_path,
                    "auto_match": f.auto_match.path if f.auto_match else None,
                    "candidate_count": len(f.candidates),
                }
                for f in findings
            ]
        )
        return

    if not findings:
        con.print("No missing samples found.")
        return
    for f in findings:
        tail = f"auto={f.auto_match.path}" if f.auto_match else f"candidates={len(f.candidates)}"
        con.print(f"  id={f.project_id}  {f.missing_path}  [{tail}]")
```

**Step 4: Run, expect PASS**

Run: `uv run --project Z:/User/audio pytest packages/cli/tests/test_cli_relink_missing_samples.py -v`

**Step 5: Commit**

```bash
git add packages/cli/audio_cli/main.py packages/cli/tests/test_cli_relink_missing_samples.py
git commit -m "feat(cli): audio relink-missing-samples lists & proposes auto-relinks"
```

---

## Task 16: MCP `find_missing_samples` tool

**Files:**
- Modify: `packages/mcp/audio_mcp/main.py`
- Modify: `packages/mcp/tests/test_mcp_tools.py`
- Modify: `docs/mcp-setup.md`

**Step 0: Read the existing macpath/dedup MCP tool wiring**

Run: `Grep "find_mac_imports" packages/mcp/audio_mcp/main.py` to see the conventions; mirror them.

**Step 1: Append the failing test**

```python
def test_find_missing_samples_tool(tmp_path, monkeypatch):
    import shutil
    from pathlib import Path
    from audio_core.db.connection import open_db
    from audio_core.samples import upsert_sample
    from audio_core.scanner.scan import scan_one

    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    fixtures = Path(__file__).parents[2] / "core" / "tests" / "fixtures"
    proj = tmp_path / "Projects" / "p Project"
    proj.mkdir(parents=True)
    shutil.copy(fixtures / "missing_sample_tiny.als", proj / "p.als")
    pid = scan_one(conn, proj / "p.als")
    cand = tmp_path / "lib" / "relink_test_kick.wav"
    cand.parent.mkdir(parents=True)
    cand.write_bytes(b"x")
    upsert_sample(conn, cand)

    server = build_server()

    async def go():
        async with Client(server) as c:
            res = await c.call_tool("find_missing_samples", {})
            data = res.data
            assert any(item["project_id"] == pid for item in data)

    asyncio.run(go())
```

**Step 2: Run, expect FAIL**

Run: `uv run --project Z:/User/audio pytest packages/mcp/tests/test_mcp_tools.py -k missing_samples -v`

**Step 3: Add the tool**

In `packages/mcp/audio_mcp/main.py`:

```python
from audio_core.relink import find_missing_samples as _find_missing_samples


@mcp.tool
def find_missing_samples(limit: int = 200) -> list[dict]:
    """Projects with one or more samples that don't exist on disk. Each finding
    includes either an `auto_match` (filename matches exactly one indexed
    candidate) or a list of `candidates` for the user to pick from. Pair with
    `propose_batch` and `RelinkMissingSamples` actions to fix.
    """
    conn = open_db(db_path())
    findings = _find_missing_samples(conn)[:limit]
    return [
        {
            "project_id": f.project_id,
            "project_path": f.project_path,
            "project_name": f.project_name,
            "missing_path": f.missing_path,
            "auto_match": f.auto_match.path if f.auto_match else None,
            "candidates": [c.path for c in f.candidates],
        }
        for f in findings
    ]
```

In `docs/mcp-setup.md`, add to the tool list:

```markdown
- `find_missing_samples(limit)` — list missing-sample findings with optional auto-match candidates; pair with `propose_batch` + `RelinkMissingSamples` to fix.
```

**Step 4: Run, expect PASS**

Run: `uv run --project Z:/User/audio pytest packages/mcp/tests/test_mcp_tools.py -v`

**Step 5: Commit**

```bash
git add packages/mcp/audio_mcp/main.py packages/mcp/tests/test_mcp_tools.py docs/mcp-setup.md
git commit -m "feat(mcp): find_missing_samples tool"
```

---

## Task 17: Frontend types + API client + MSW handlers

**Files:**
- Modify: `web/src/lib/types.ts`
- Modify: `web/src/lib/api.ts`
- Modify: `web/src/mocks/handlers.ts`
- Test: `web/src/lib/api.repair.test.ts`

**Step 0: Read the existing types/api/handlers**

Run: `Read web/src/lib/types.ts web/src/lib/api.ts` to see the shape conventions and TanStack Query key patterns.

**Step 1: Write the failing test**

```ts
// web/src/lib/api.repair.test.ts
import { describe, expect, it } from "vitest";
import { fetchRepairFindings } from "./api";
import { server } from "../mocks/server";
import { http, HttpResponse } from "msw";

describe("fetchRepairFindings", () => {
  it("returns mac + missing-sample findings", async () => {
    server.use(
      http.get("/api/repair/findings", () =>
        HttpResponse.json({
          mac_imports: [
            { project_id: 1, path: "/p.als", name: "p", parent_dir: "/", mac_paths_count: 3, project_info_missing: false },
          ],
          missing_samples: [
            { project_id: 1, project_path: "/p.als", project_name: "p", missing_path: "k.wav", auto_match: { path: "/lib/k.wav", filename: "k.wav", size_bytes: 1 }, candidates: [] },
          ],
        }),
      ),
    );
    const data = await fetchRepairFindings();
    expect(data.macImports).toHaveLength(1);
    expect(data.missingSamples[0].autoMatch?.path).toBe("/lib/k.wav");
  });
});
```

**Step 2: Run, expect FAIL**

Run: `cd web && npm test -- api.repair`

**Step 3: Implement types + client**

Add to `web/src/lib/types.ts`:

```ts
export interface MacImportFinding {
  projectId: number;
  path: string;
  name: string;
  parentDir: string;
  macPathsCount: number;
  projectInfoMissing: boolean;
}

export interface SampleCandidate {
  path: string;
  filename: string;
  sizeBytes: number;
}

export interface MissingSampleFinding {
  projectId: number;
  projectPath: string;
  projectName: string;
  missingPath: string;
  autoMatch: SampleCandidate | null;
  candidates: SampleCandidate[];
}

export interface RepairFindings {
  macImports: MacImportFinding[];
  missingSamples: MissingSampleFinding[];
}
```

Add to `web/src/lib/api.ts`:

```ts
export async function fetchRepairFindings(): Promise<RepairFindings> {
  const r = await fetch("/api/repair/findings");
  if (!r.ok) throw new Error(`/api/repair/findings ${r.status}`);
  const j = await r.json();
  return {
    macImports: j.mac_imports.map((m: any) => ({
      projectId: m.project_id,
      path: m.path,
      name: m.name,
      parentDir: m.parent_dir,
      macPathsCount: m.mac_paths_count,
      projectInfoMissing: m.project_info_missing,
    })),
    missingSamples: j.missing_samples.map((s: any) => ({
      projectId: s.project_id,
      projectPath: s.project_path,
      projectName: s.project_name,
      missingPath: s.missing_path,
      autoMatch: s.auto_match
        ? { path: s.auto_match.path, filename: s.auto_match.filename, sizeBytes: s.auto_match.size_bytes }
        : null,
      candidates: (s.candidates || []).map((c: any) => ({
        path: c.path,
        filename: c.filename,
        sizeBytes: c.size_bytes,
      })),
    })),
  };
}
```

Also add a default MSW handler in `web/src/mocks/handlers.ts` returning empty findings, so other tests don't 404 on it.

**Step 4: Run, expect PASS**

Run: `cd web && npm test -- api.repair`

**Step 5: Commit**

```bash
git add web/src/lib/types.ts web/src/lib/api.ts web/src/mocks/handlers.ts web/src/lib/api.repair.test.ts
git commit -m "feat(web): types + client for /api/repair/findings"
```

---

## Task 18: `<RepairPanel>` on project detail

**Files:**
- Create: `web/src/components/data/RepairPanel.tsx`
- Create: `web/src/components/data/RepairPanel.test.tsx`
- Modify: `web/src/routes/notebook.tsx` (or wherever the project-detail view renders today)
- Modify: `web/src/dev/registry.tsx`

**Step 0: Read the existing project detail surface**

Run: `Read web/src/routes/notebook.tsx web/src/components/data/MarginStickyNote.tsx web/src/components/data/SongStrip.tsx`. The new component must match those primitives: same paper background, ruled margin, font scales, hover patterns. Per `feedback_layer_dont_redesign.md`: layer onto the stationery, do NOT introduce new aesthetics.

**Step 1: Write the failing test**

```tsx
// web/src/components/data/RepairPanel.test.tsx
import { render, screen, fireEvent } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { RepairPanel } from "./RepairPanel";

const propsClean = { macImport: null, missingSamples: [], onPropose: vi.fn() };
const propsBroken = {
  macImport: { projectId: 1, macPathsCount: 12, projectInfoMissing: true },
  missingSamples: [
    { missingPath: "k.wav", autoMatch: { path: "/lib/k.wav", filename: "k.wav", sizeBytes: 1 }, candidates: [] },
    { missingPath: "s.wav", autoMatch: null, candidates: [
      { path: "/lib/s1.wav", filename: "s.wav", sizeBytes: 2 },
      { path: "/lib/s2.wav", filename: "s.wav", sizeBytes: 3 },
    ]},
  ],
  onPropose: vi.fn(),
};

describe("RepairPanel", () => {
  it("renders nothing when project is clean", () => {
    const { container } = render(<RepairPanel {...propsClean} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("shows mac-paths chip and missing-sample list", () => {
    render(<RepairPanel {...propsBroken} />);
    expect(screen.getByText(/12 mac paths/i)).toBeInTheDocument();
    expect(screen.getByText("k.wav")).toBeInTheDocument();
    expect(screen.getByText("s.wav")).toBeInTheDocument();
  });

  it("propose-button calls onPropose with auto-matches plus picks", () => {
    render(<RepairPanel {...propsBroken} />);
    // Pick the second candidate for s.wav
    fireEvent.click(screen.getByRole("button", { name: /pick/i }));
    fireEvent.click(screen.getByRole("button", { name: "/lib/s2.wav" }));
    fireEvent.click(screen.getByRole("button", { name: /propose repair/i }));
    expect(propsBroken.onPropose).toHaveBeenCalledWith({
      macImport: true,
      relinks: { "k.wav": "/lib/k.wav", "s.wav": "/lib/s2.wav" },
    });
  });
});
```

**Step 2: Run, expect FAIL**

Run: `cd web && npm test -- RepairPanel`

**Step 3: Implement**

Create `web/src/components/data/RepairPanel.tsx`. Reuse `MarginStickyNote` as the outer surface, render rows in `SongStrip`-styled markup. Keep the JSX small; everything else lives in `propose-translate.ts`-style helpers if needed. Sketch:

```tsx
import { useState } from "react";
import { MarginStickyNote } from "./MarginStickyNote";

export interface RepairPanelProps {
  macImport: { projectId: number; macPathsCount: number; projectInfoMissing: boolean } | null;
  missingSamples: {
    missingPath: string;
    autoMatch: { path: string; filename: string; sizeBytes: number } | null;
    candidates: { path: string; filename: string; sizeBytes: number }[];
  }[];
  onPropose: (selection: { macImport: boolean; relinks: Record<string, string> }) => void;
}

export function RepairPanel({ macImport, missingSamples, onPropose }: RepairPanelProps) {
  const hasIssues = macImport != null || missingSamples.length > 0;
  if (!hasIssues) return null;

  const [picks, setPicks] = useState<Record<string, string>>({});
  const [openPicker, setOpenPicker] = useState<string | null>(null);

  const resolvedRelinks: Record<string, string> = {};
  for (const m of missingSamples) {
    const chosen = picks[m.missingPath] ?? m.autoMatch?.path;
    if (chosen) resolvedRelinks[m.missingPath] = chosen;
  }

  return (
    <MarginStickyNote tone="warning" title="Needs attention">
      {macImport && (
        <div className="repair-row">
          <span className="repair-chip">{macImport.macPathsCount} mac paths</span>
          {macImport.projectInfoMissing && <span className="repair-chip">no Project Info</span>}
        </div>
      )}
      {missingSamples.map((m) => (
        <div key={m.missingPath} className="repair-row">
          <span className="repair-filename">{m.missingPath.split("/").pop()}</span>
          {m.autoMatch && !picks[m.missingPath] ? (
            <span className="repair-auto">✓ {m.autoMatch.path}</span>
          ) : picks[m.missingPath] ? (
            <span className="repair-auto">✓ {picks[m.missingPath]}</span>
          ) : m.candidates.length === 0 ? (
            <span className="repair-no-match">no match found</span>
          ) : (
            <button onClick={() => setOpenPicker(m.missingPath)}>Pick candidate</button>
          )}
          {openPicker === m.missingPath && (
            <ul className="repair-candidates">
              {m.candidates.map((c) => (
                <li key={c.path}>
                  <button
                    onClick={() => {
                      setPicks({ ...picks, [m.missingPath]: c.path });
                      setOpenPicker(null);
                    }}
                  >
                    {c.path}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      ))}
      <button
        className="repair-propose"
        onClick={() => onPropose({ macImport: macImport != null, relinks: resolvedRelinks })}
      >
        Propose repair
      </button>
    </MarginStickyNote>
  );
}
```

Style with the existing stationery tokens. **No new colors, no new fonts.** Cross-check `MarginStickyNote.tsx` for the `tone` prop API.

**Step 4: Wire into project detail**

In the project-detail route (`web/src/routes/notebook.tsx` or wherever a single project is rendered — verify with grep), at the top of the render, fetch repair findings filtered to this project_id and render `<RepairPanel>` above existing content. Use existing TanStack Query patterns. The `onPropose` callback POSTs to `/api/proposals` and navigates to `/proposals/:id`.

**Step 5: Run, expect PASS**

Run: `cd web && npm test -- RepairPanel`
Run: `cd web && npm test` (full suite, watch for regressions)

**Step 6: Browser-verify**

Run: `cd web && npm run dev` (with backend on 7878). Navigate to a broken project's detail page. Confirm the panel renders, missing-sample auto-matches show check marks, candidate picker opens, propose button creates a proposal and lands on the proposal page.

**Step 7: Add to `_dev` registry**

In `web/src/dev/registry.tsx`, register `RepairPanel` with `clean`, `mac-only`, `missing-only`, `both` variants in light + dark + reduced-motion.

**Step 8: Commit**

```bash
git add web/src/components/data/RepairPanel.tsx web/src/components/data/RepairPanel.test.tsx web/src/routes/notebook.tsx web/src/dev/registry.tsx
git commit -m "feat(web): RepairPanel inline on project detail"
```

---

## Task 19: `/repair` route — bulk view

**Files:**
- Create: `web/src/routes/repair.tsx`
- Create: `web/src/routes/repair.test.tsx`
- Modify: `web/src/app/router.tsx` (add the route)
- Modify: `web/src/dev/registry.tsx`

**Step 0: Verify the `<Shelf>` primitive**

Run: `Glob web/src/components/**/Shelf*.tsx` and `Read` whatever's there. If there's no `Shelf` component, use the same surface pattern the home view uses for grouped sections. Don't introduce new primitives.

**Step 1: Write the failing test**

```tsx
// web/src/routes/repair.test.tsx
import { render, screen, fireEvent, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RepairRoute } from "./repair";
import { server } from "../mocks/server";
import { http, HttpResponse } from "msw";

function renderWithClient(ui: React.ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>);
}

describe("/repair route", () => {
  it("renders both shelves with counts", async () => {
    server.use(
      http.get("/api/repair/findings", () =>
        HttpResponse.json({
          mac_imports: [
            { project_id: 1, path: "/a.als", name: "a", parent_dir: "/", mac_paths_count: 3, project_info_missing: false },
          ],
          missing_samples: [
            { project_id: 2, project_path: "/b.als", project_name: "b", missing_path: "k.wav",
              auto_match: { path: "/lib/k.wav", filename: "k.wav", size_bytes: 1 }, candidates: [] },
          ],
        }),
      ),
    );
    renderWithClient(<RepairRoute />);
    expect(await screen.findByText(/Mac imports.*1/i)).toBeInTheDocument();
    expect(screen.getByText(/Missing samples.*1/i)).toBeInTheDocument();
  });

  it("disables rows that need review", async () => {
    server.use(
      http.get("/api/repair/findings", () =>
        HttpResponse.json({
          mac_imports: [],
          missing_samples: [
            { project_id: 5, project_path: "/c.als", project_name: "c", missing_path: "x.wav",
              auto_match: null, candidates: [
                { path: "/a/x.wav", filename: "x.wav", size_bytes: 1 },
                { path: "/b/x.wav", filename: "x.wav", size_bytes: 2 },
              ] },
          ],
        }),
      ),
    );
    renderWithClient(<RepairRoute />);
    const row = await screen.findByTestId("missing-row-5");
    const cb = within(row).getByRole("checkbox");
    expect(cb).toBeDisabled();
    expect(within(row).getByText(/needs review/i)).toBeInTheDocument();
  });
});
```

**Step 2: Run, expect FAIL**

Run: `cd web && npm test -- repair`

**Step 3: Implement**

Create `web/src/routes/repair.tsx` with:
- TanStack Query loader: `useQuery(["repair","findings"], fetchRepairFindings)`.
- Two grouped sections (use `<Shelf>` if present, else the existing grouped-section pattern).
- Each row a `<SongStrip>` with a leading checkbox in the ruled margin. For Mac rows the checkbox is always selectable; for missing-sample rows the checkbox is disabled when the project has any missing sample without `auto_match` and no pick yet — show a small "needs review" tag.
- Footer: a small banner with "Propose batch (N selected)" — the button is disabled when N=0. Click → POST `/api/proposals` with mixed actions, then navigate to `/proposals/:id`.

Wire the route in `web/src/app/router.tsx`.

**Step 4: Run, expect PASS**

Run: `cd web && npm test -- repair`

**Step 5: Browser-verify**

Run: `cd web && npm run dev`. Navigate to `/repair`. Confirm both shelves render, selecting rows updates the footer count, propose creates a proposal and redirects.

**Step 6: Commit**

```bash
git add web/src/routes/repair.tsx web/src/routes/repair.test.tsx web/src/app/router.tsx web/src/dev/registry.tsx
git commit -m "feat(web): /repair route with bulk Mac + Missing-sample shelves"
```

---

## Task 20: Full test sweep

**Step 1: Run every package's tests**

Run: `uv run --project Z:/User/audio pytest packages/core packages/cli packages/mcp packages/web -q`
Expected: all pass.

Run: `cd web && npm test --run`
Expected: all pass.

If a regression surfaced, fix it before continuing.

**Step 2: Run lint/typecheck**

Run: `uv run --project Z:/User/audio ruff check packages` (if ruff is configured — check `pyproject.toml`)
Run: `cd web && npm run typecheck` (or whatever the tsc command is per `package.json`)

**Step 3: Commit any incidental fixes** (only if needed).

---

## Task 21: E2E smoke against the real catalog

Manual verification only.

**Step 1: Re-scan to populate `samples` table**

Run the bundled app (`cd web && npm run dev` + the FastAPI sidecar from the existing dev script, or whatever the project's dev launch is). On boot, the indexer's `FullSampleScan` runs once. Watch the indexer-status pill in the UI; wait for "idle".

**Step 2: Open a known-broken project**

Navigate to a project flagged with missing samples. Confirm `<RepairPanel>` renders at the top of the detail view with auto-matches and/or "Pick candidate" buttons.

**Step 3: Propose + approve a per-project repair**

Click "Propose repair" on a single project that only has auto-matches. Confirm a proposal is created and the proposal-review page renders. Approve it. Open the resulting `.als` in Live and confirm samples load (no missing-files dialog).

**Step 4: Bulk repair**

Navigate to `/repair`. Select 2–3 Mac-import rows + 2–3 fully-auto-matchable missing-sample rows. Click "Propose batch". Approve from the proposal page. Open one repaired project per fix kind in Live; confirm.

**Step 5: Undo a smoke batch**

Run: `uv run --project Z:/User/audio audio undo <batch_id>` (or the equivalent UI undo). Verify each touched `.als` matches its `.als.bak` byte-for-byte and the `project_samples` rows revert.

**Step 6: Done.**

---

## Notes

- **Why filename-only matching in v1 (vs the design's filename+size):** missing samples by definition aren't on disk, so the size we'd want to match against doesn't exist in `project_samples` (we never got to stat it). Adding `size_bytes` in Task 1 is forward-compat: once a project is rescanned with samples present, future relinks of *those* paths can use stricter size-aware matching. v1 ships with filename single-candidate, which preserves the spirit of "conservative" (auto-match only when unambiguous).
- **Why the action clears `<RelativePath>`/`<HasRelativePath>`/`<RelativePathType>`:** Live recomputes them on save anyway, and a stale relative-path resurrects the old missing-sample lookup. Same logic the macpath repair uses for `<OriginalFileRef>`.
- **Why the watcher tests use a real filesystem and a small sleep:** existing watcher tests do the same; mocking watchdog produced false positives. Keep the debounce small (`debounce_s=0.1`) for tests so they don't take seconds.
- **Why no MCP tool for the combined `/api/repair/findings`:** MCP is action-oriented, the AI calls `find_mac_imports` + `find_missing_samples` separately and decides what to propose. The combined endpoint exists for the UI's convenience.
- **Backup retention:** `.als.bak` accumulates in project folders; same as macpath repair. A future `audio cleanup-backups --older-than 30d` is the right home for sweeping them — out of scope here.
- **`build_relink_proposal` vs the action's `.relinks`:** the helper turns `MissingSampleFinding[]` + UI picks into proposal action dicts. The action itself takes a list of `Relink(old, new)` once materialized in `_materialize`. Keep the boundary clean.
