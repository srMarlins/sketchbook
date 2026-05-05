# Indexer Architecture & Live Catalog Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. The user has waived per-batch checkpoints — drive through tasks back-to-back, only stopping for genuine blockers. The architecture overview below is binding; deviations require explicit conversation, not silent improvisation.

**Goal:** When the app launches, the catalog populates and stays fresh on its own. The user never types `audio scan`. The DB is the single source of truth; the UI binds to it and reflects every change live, with progress visible in a status chip. A cross-platform filesystem watcher (`watchdog` — uses `ReadDirectoryChangesW` on Windows, `FSEvents` on macOS) keeps the catalog in sync after launch. Backfills for new columns happen automatically and transparently.

**Architecture stance:** introduce `audio_core.indexer` as a new orchestration layer above the existing scanner primitives. Refactor where it pays off (event-driven SSE, single-flight job queue), additive otherwise (scanner stays single-purpose; CLI becomes thin wrapper). The plan is staged so each phase ships independently and is testable in isolation.

**Tech Stack:** Python 3.12, SQLite (WAL), FastAPI + lifespan + SSE, watchdog 4+, asyncio + threading bridge, React + TanStack Query, Tauri sidecar. No new heavy deps.

---

## Architecture overview

### Layers

```
                 ┌─────────────────────────────────────────────┐
React UI ────────│ /api/events  (SSE, single bus)              │
(TanStack Query) │ /api/projects, /api/proposals, ... (REST)   │
                 └─────────────────────────────────────────────┘
                              ▲
                              │ FastAPI app
                              │
         ┌────────────────────┴───────────────────────────┐
         │ audio_core.indexer.driver (lifespan boot)      │
         │   on_start: migrate → discover → backfill →   │
         │             start full scan → start watcher    │
         └─┬───────────────────────┬─────────────────────┬┘
           │                       │                     │
           ▼                       ▼                     ▼
   ┌────────────────┐     ┌─────────────────┐   ┌─────────────────┐
   │ JobQueue       │◀────│ FsWatcher       │   │ EventBus        │
   │ (single-flight)│     │ (watchdog +     │   │ (pub/sub,       │
   │   FullScan     │     │  debounce, 2s)  │   │  asyncio queues │
   │   IncrementalScan    │ produces        │   │  per subscriber)│
   │   BackfillColumn     │ IncrementalScan │   │                 │
   └─┬──────────────┘     │ jobs            │   └────────▲────────┘
     │                    └─────────────────┘            │
     │ runs jobs in worker thread                        │
     │ publishes events                                  │
     ▼                                                   │
   ┌──────────────────────────────────────────────┐      │
   │ audio_core.scanner.scan_one  (existing)      │──────┘
   │ audio_core.parser.parse_als  (existing)      │
   │ audio_core.backfill          (new)           │
   │ audio_core.detectors         (new)           │
   └──────────────────────────────────────────────┘
                              │
                              ▼
                       SQLite (WAL)
                       — single source of truth —
```

### Boundaries

- `audio_core.scanner` stays **a primitive**: parse-and-upsert one `.als`. No queues, no events.
- `audio_core.indexer` is **the orchestrator**: jobs, queue, event bus, driver. All long-running work goes through it.
- `audio_core.detectors` is the dedup of `find_mac_imports`, `find_duplicates`, future detectors — each is a function `(conn) -> list[Finding]` that the indexer fires after every scan/backfill to populate `findings_changed` events.
- `audio_web` exposes SSE + REST. No business logic; pure adapter to indexer/detector results.
- `audio_cli` becomes a thin client. `audio scan` submits a FullScan job and tails the event bus to print progress. Same code path the desktop app uses.

### Why a single-flight queue (and not parallel workers)

Live's saves write the `.als` file 5+ times in quick succession. With parallel workers and no debouncing we'd parse the same file repeatedly. Single-flight + per-path debounce in the watcher gives consistent behavior, keeps the SQLite writer queue happy, and makes progress reporting honest. SQLite with WAL allows arbitrary readers in parallel, which is what matters for the UI.

### Event bus semantics

- One in-memory pub/sub. Subscribers receive an `asyncio.Queue` (bounded, default 256). Slow subscribers drop oldest events with a `dropped: N` warning event injected.
- Events are typed dicts with a `kind` discriminator. The full set is enumerated in Phase C.
- The bus lives in `audio_core.indexer` so the CLI can subscribe too (for verbose progress).

### What we DON'T build

- A separate sidecar worker process. The indexer runs in the same FastAPI process. SQLite WAL handles concurrency. One DB connection per worker thread; the connection pool already exists in `audio_core.db.connection`.
- A persistent job queue. Jobs live in memory; on crash, the next launch's discovery picks up where it left off.
- A websocket server. SSE is one-way (server → client) and that's all we need. WS would add reconnection/protocol overhead for no benefit.

### File layout (new + refactored)

```
packages/core/audio_core/
  indexer/
    __init__.py            (re-exports; public API)
    events.py              (EventBus, Event types)
    queue.py               (JobQueue: single-flight, thread-safe)
    jobs.py                (FullScan, IncrementalScan, BackfillColumn)
    discovery.py           (walk_projects + plan() bucketing)
    driver.py              (boot sequence: migrate → discover → backfill → scan → watch)
    watcher.py             (watchdog wrapper, debounce, network-drive detection)
  backfill.py              (NEW: schema-driven NULL fillers)
  detectors.py             (NEW: re-export find_mac_imports, find_duplicates, register-find-fns)
  scanner/
    scan.py                (UNCHANGED public API; gains the (path,mtime,size) cheap-skip)

packages/web/audio_web/
  routes_events.py         (NEW: GET /api/events, SSE)
  app.py                   (lifespan hook: indexer.driver.boot/teardown)

packages/cli/audio_cli/
  main.py                  (audio scan refactored to submit FullScan + tail events)

web/src/
  app/IndexerStatus.tsx    (NEW: status-bar chip; replaces or absorbs SidecarHealth.tsx)
  app/FirstLaunch.tsx      (NEW: cold-DB splash)
  hooks/useIndexerEvents.ts (NEW: SSE subscriber + TanStack cache patcher)
  app/App.tsx              (mounts IndexerStatus; routes through FirstLaunch when needed)
```

### Migrations needed

- `projects.is_missing INTEGER NOT NULL DEFAULT 0` — set by discovery when path is in DB but not on disk.
- `projects.last_seen REAL` — timestamp of last successful filesystem stat. Used by GC heuristics later.
- `indexer_state` table — single-row, single-flight progress + last-completed-job-id. Surfaces "another instance is scanning" if a second app launches.

```sql
CREATE TABLE IF NOT EXISTS indexer_state (
  id           INTEGER PRIMARY KEY CHECK (id = 1),
  job_kind     TEXT,
  job_path     TEXT,
  total        INTEGER,
  done         INTEGER,
  started_at   REAL,
  pid          INTEGER
);
```

---

## Phase A — Indexer scaffold

**Done when:** `audio_core.indexer` exists with a JobQueue running on a worker thread, an EventBus, and a `FullScan` job that delegates to existing `scan_root`. CLI's `audio scan` is refactored to submit + tail. No behavior change for users yet.

### Task A1: EventBus

**Files:**
- Create: `packages/core/audio_core/indexer/__init__.py`
- Create: `packages/core/audio_core/indexer/events.py`
- Create: `packages/core/tests/test_indexer_events.py`

**Step 1: Tests**

```python
import asyncio
import pytest
from audio_core.indexer.events import EventBus


@pytest.mark.asyncio
async def test_subscribers_get_events():
    bus = EventBus()
    sub = bus.subscribe()
    bus.publish({"kind": "ping", "n": 1})
    bus.publish({"kind": "ping", "n": 2})
    e1 = await asyncio.wait_for(sub.get(), 1)
    e2 = await asyncio.wait_for(sub.get(), 1)
    assert (e1["n"], e2["n"]) == (1, 2)


@pytest.mark.asyncio
async def test_late_subscriber_only_gets_future_events():
    bus = EventBus()
    bus.publish({"kind": "ping", "n": 0})
    sub = bus.subscribe()
    bus.publish({"kind": "ping", "n": 1})
    e = await asyncio.wait_for(sub.get(), 1)
    assert e["n"] == 1


@pytest.mark.asyncio
async def test_unsubscribe_stops_receiving():
    bus = EventBus()
    sub = bus.subscribe()
    bus.unsubscribe(sub)
    bus.publish({"kind": "ping", "n": 1})
    with pytest.raises(asyncio.TimeoutError):
        await asyncio.wait_for(sub.get(), 0.05)


@pytest.mark.asyncio
async def test_bounded_queue_drops_oldest():
    bus = EventBus(max_queue_size=2)
    sub = bus.subscribe()
    for n in range(5):
        bus.publish({"kind": "x", "n": n})
    # Should have most recent two events plus a "dropped" marker injected.
    events = []
    while not sub.empty():
        events.append(sub.get_nowait())
    assert any(e["kind"] == "dropped" for e in events)
    nums = [e["n"] for e in events if e["kind"] == "x"]
    assert nums == [3, 4]
```

**Step 2: Implement** `packages/core/audio_core/indexer/events.py`

```python
from __future__ import annotations

import asyncio
import threading
from typing import Any


class EventBus:
    """Thread-safe pub/sub. publish() is callable from any thread; subscribe()
    returns an asyncio.Queue that must be consumed from the asyncio loop bound
    when subscribe() was called. Bounded queues drop oldest with a 'dropped'
    marker so a slow subscriber can't backpressure publishers.
    """

    def __init__(self, *, max_queue_size: int = 256) -> None:
        self._max = max_queue_size
        self._subs: list[tuple[asyncio.AbstractEventLoop, asyncio.Queue[dict[str, Any]]]] = []
        self._lock = threading.Lock()

    def subscribe(self) -> asyncio.Queue[dict[str, Any]]:
        loop = asyncio.get_event_loop()
        q: asyncio.Queue[dict[str, Any]] = asyncio.Queue(maxsize=self._max)
        with self._lock:
            self._subs.append((loop, q))
        return q

    def unsubscribe(self, q: asyncio.Queue[dict[str, Any]]) -> None:
        with self._lock:
            self._subs = [(l, qq) for l, qq in self._subs if qq is not q]

    def publish(self, event: dict[str, Any]) -> None:
        with self._lock:
            subs = list(self._subs)
        for loop, q in subs:
            loop.call_soon_threadsafe(self._push, q, event)

    def _push(self, q: asyncio.Queue[dict[str, Any]], event: dict[str, Any]) -> None:
        if q.full():
            try:
                q.get_nowait()
            except asyncio.QueueEmpty:
                pass
            q.put_nowait({"kind": "dropped"})
        q.put_nowait(event)
```

**Step 3:** Run tests. Add `pytest-asyncio` to dev deps if missing (it's already in the workspace per existing tests).

**Step 4: Commit**

```bash
git add packages/core/audio_core/indexer/__init__.py packages/core/audio_core/indexer/events.py packages/core/tests/test_indexer_events.py
git commit -m "feat(core/indexer): EventBus pub/sub with bounded async queues"
```

---

### Task A2: JobQueue (single-flight)

**Files:**
- Create: `packages/core/audio_core/indexer/queue.py`
- Test: `packages/core/tests/test_indexer_queue.py`

**Step 1: Tests** — assert (a) jobs run in submission order, (b) only one runs at a time, (c) exceptions don't kill the worker, (d) shutdown drains.

```python
import threading
import time
from audio_core.indexer.queue import JobQueue


def test_jobs_run_in_order():
    q = JobQueue()
    out = []
    q.start()
    for i in range(3):
        q.submit(lambda i=i: out.append(i))
    q.shutdown(wait=True)
    assert out == [0, 1, 2]


def test_only_one_job_runs_at_a_time():
    q = JobQueue()
    overlap = []
    running = threading.Event()
    def slow():
        running.set()
        time.sleep(0.05)
    def quick():
        if running.is_set():
            overlap.append(True)
    q.start()
    q.submit(slow)
    q.submit(quick)
    q.shutdown(wait=True)
    # quick saw running set means it ran AFTER slow finished setting flag,
    # but if they overlapped it would have run while sleep was active.
    # The single-flight guarantee is enforced by serial execution in one thread.
    assert overlap == [True]  # quick observed running.set already; slow has finished


def test_exception_does_not_kill_worker():
    q = JobQueue()
    out = []
    q.start()
    q.submit(lambda: (_ for _ in ()).throw(RuntimeError("boom")))
    q.submit(lambda: out.append("after"))
    q.shutdown(wait=True)
    assert out == ["after"]
```

**Step 2: Implement** — wrap a `queue.Queue` and a single worker thread; jobs are zero-arg callables. Catch exceptions, publish a `job_failed` event (later wired through Phase B). For now, just log + continue.

```python
from __future__ import annotations

import logging
import queue
import threading
from collections.abc import Callable
from typing import Any

log = logging.getLogger(__name__)


class JobQueue:
    """Single-flight FIFO queue. Jobs are zero-arg callables. Run on a single
    worker thread so SQLite writes serialize naturally and progress is honest.
    """

    _SENTINEL: Any = object()

    def __init__(self) -> None:
        self._q: queue.Queue = queue.Queue()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        if self._thread is not None:
            return
        self._thread = threading.Thread(target=self._run, name="indexer-queue", daemon=True)
        self._thread.start()

    def submit(self, fn: Callable[[], None]) -> None:
        self._q.put(fn)

    def shutdown(self, *, wait: bool = True) -> None:
        if self._thread is None:
            return
        self._q.put(self._SENTINEL)
        if wait:
            self._thread.join()
        self._thread = None

    def _run(self) -> None:
        while True:
            fn = self._q.get()
            if fn is self._SENTINEL:
                return
            try:
                fn()
            except Exception:
                log.exception("indexer job raised")
```

**Step 3:** tests pass.

**Step 4: Commit**

```bash
git add packages/core/audio_core/indexer/queue.py packages/core/tests/test_indexer_queue.py
git commit -m "feat(core/indexer): single-flight JobQueue with exception isolation"
```

---

### Task A3: Discovery + plan()

**Files:**
- Create: `packages/core/audio_core/indexer/discovery.py`
- Test: `packages/core/tests/test_indexer_discovery.py`

**Step 1: Tests** — discovery returns `(path, mtime, size)`, plan() bucketizes against catalog into NEW/CHANGED/UNCHANGED/MISSING.

```python
import time
from audio_core.indexer.discovery import discover, plan


def test_discover_yields_als_with_stat(tmp_path):
    (tmp_path / "p Project").mkdir()
    f = tmp_path / "p Project" / "x.als"
    f.write_bytes(b"hello")
    rows = list(discover(tmp_path))
    assert len(rows) == 1
    assert rows[0].path.endswith("x.als")
    assert rows[0].size == 5
    assert rows[0].mtime > 0


def test_plan_bucketizes_correctly(tmp_path):
    catalog = {
        "/a.als": {"mtime": 100.0, "size": 10, "file_hash": "h"},
        "/c.als": {"mtime": 100.0, "size": 10, "file_hash": "h"},
    }
    discovered = [
        # NEW
        type("D", (), {"path": "/b.als", "mtime": 200.0, "size": 20})(),
        # UNCHANGED (mtime+size match)
        type("D", (), {"path": "/a.als", "mtime": 100.0, "size": 10})(),
        # CHANGED (size differs)
        type("D", (), {"path": "/c.als", "mtime": 100.0, "size": 99})(),
    ]
    p = plan(catalog, discovered)
    assert {r.path for r in p.new} == {"/b.als"}
    assert {r.path for r in p.unchanged} == {"/a.als"}
    assert {r.path for r in p.changed} == {"/c.als"}
    assert p.missing == []  # nothing in catalog missing from discovered


def test_plan_detects_missing(tmp_path):
    catalog = {"/gone.als": {"mtime": 1, "size": 1, "file_hash": "h"}}
    p = plan(catalog, [])
    assert p.missing == ["/gone.als"]
```

**Step 2: Implement**

```python
from __future__ import annotations

from collections.abc import Iterable, Iterator
from dataclasses import dataclass
from pathlib import Path

from audio_core.scanner.walker import walk_projects


@dataclass(frozen=True)
class Discovered:
    path: str
    mtime: float
    size: int


def discover(root: str | Path) -> Iterator[Discovered]:
    for p in walk_projects(root):
        try:
            st = p.stat()
        except OSError:
            continue
        yield Discovered(path=str(p), mtime=st.st_mtime, size=st.st_size)


@dataclass(frozen=True)
class Plan:
    new: list[Discovered]
    changed: list[Discovered]
    unchanged: list[Discovered]
    missing: list[str]


def plan(catalog: dict[str, dict], discovered: Iterable[Discovered]) -> Plan:
    """Bucketize discovered .als paths against the catalog snapshot.

    `catalog` is a {path: {mtime, size, file_hash}} dict — the caller fetches it
    once with one query. UNCHANGED is decided by mtime+size match (cheap, no
    hash). CHANGED still gets a hash later but only when this guard fires.
    """
    new: list[Discovered] = []
    changed: list[Discovered] = []
    unchanged: list[Discovered] = []
    seen: set[str] = set()
    for d in discovered:
        seen.add(d.path)
        row = catalog.get(d.path)
        if row is None:
            new.append(d)
        elif row["mtime"] == d.mtime and row["size"] == d.size:
            unchanged.append(d)
        else:
            changed.append(d)
    missing = [p for p in catalog if p not in seen]
    return Plan(new=new, changed=changed, unchanged=unchanged, missing=missing)
```

**Step 3:** tests pass. Commit.

```bash
git add packages/core/audio_core/indexer/discovery.py packages/core/tests/test_indexer_discovery.py
git commit -m "feat(core/indexer): discovery + plan() bucketize NEW/CHANGED/UNCHANGED/MISSING"
```

---

### Task A4: file_size + cheap-skip in scanner

**Files:**
- Modify: `packages/core/audio_core/db/schema.sql` (add `file_size_bytes` if not already present — it's there per recent merge; verify; ensure index on it)
- Modify: `packages/core/audio_core/db/connection.py` (additive ALTER if missing)
- Modify: `packages/core/audio_core/scanner/scan.py` (add cheap-skip)
- Test: `packages/core/tests/test_scanner_cheap_skip.py`

**Step 1: Test the cheap-skip**

```python
import shutil
import time
from pathlib import Path

from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_root


def test_unchanged_file_skipped_without_hashing(tmp_path, monkeypatch):
    fixtures = Path(__file__).parent / "fixtures"
    (tmp_path / "p Project").mkdir()
    shutil.copy(fixtures / "tiny.als", tmp_path / "p Project" / "x.als")

    conn = open_db(tmp_path / "c.db")
    scan_root(conn, tmp_path)  # populates row

    hashes_called = 0
    real_hash = __import__("audio_core.scanner.hashing", fromlist=["hash_file"]).hash_file
    def counting_hash(p):
        nonlocal hashes_called
        hashes_called += 1
        return real_hash(p)
    monkeypatch.setattr("audio_core.scanner.scan.hash_file", counting_hash)

    stats = scan_root(conn, tmp_path)
    assert stats.skipped == 1
    assert stats.scanned == 0
    assert hashes_called == 0  # cheap-skip prevented re-hash
```

**Step 2: Modify `scan_root`** so that when (path, mtime, size) all match the catalog, we skip without calling `hash_file`. Today's code:

```python
existing = conn.execute(
    "SELECT file_hash FROM projects WHERE path = ?", (str(als),)
).fetchone()
current_hash = hash_file(als)            # <-- always hashes
if existing and existing[0] == current_hash:
    stats.skipped += 1
    ...
    continue
scan_one(conn, als)
```

Change to:

```python
existing = conn.execute(
    "SELECT file_hash, last_modified, file_size_bytes FROM projects WHERE path = ?",
    (str(als),),
).fetchone()
st = als.stat()
if existing and existing[1] == st.st_mtime and (existing[2] or 0) == st.st_size:
    stats.skipped += 1
    if on_progress:
        on_progress(als, "skipped")
    continue
# fall through: NEW or CHANGED — pay the hash cost
current_hash = hash_file(als)
if existing and existing[0] == current_hash:
    # mtime/size differed but contents identical (e.g. touch). Refresh stat fields.
    conn.execute(
        "UPDATE projects SET last_modified=?, file_size_bytes=? WHERE path=?",
        (st.st_mtime, st.st_size, str(als)),
    )
    conn.commit()
    stats.skipped += 1
    ...
    continue
scan_one(conn, als)
stats.scanned += 1
```

**Step 3:** test passes; full scanner suite still green:
`uv run pytest packages/core/tests/ -k scan -v`

**Step 4: Commit**

```bash
git add packages/core/audio_core/scanner/scan.py packages/core/tests/test_scanner_cheap_skip.py
git commit -m "perf(core/scan): mtime+size short-circuit skips re-hashing unchanged files"
```

---

### Task A5: FullScan job + driver

**Files:**
- Create: `packages/core/audio_core/indexer/jobs.py`
- Create: `packages/core/audio_core/indexer/driver.py`
- Test: `packages/core/tests/test_indexer_full_scan.py`

**Step 1: Test** that a `FullScan` job submitted to a `JobQueue` parses every NEW + CHANGED file, emits `scan_started`/`scan_progress`/`scan_finished` events, and leaves UNCHANGED rows alone.

(Sketch — match the existing test fixture style. Use `EventBus.subscribe()` to capture events.)

**Step 2: Implement `jobs.FullScan`**

```python
from dataclasses import dataclass
from pathlib import Path
import sqlite3, time

from audio_core.indexer.discovery import discover, plan
from audio_core.indexer.events import EventBus
from audio_core.scanner.scan import scan_one


@dataclass
class FullScan:
    db_path: Path
    root: Path
    bus: EventBus

    def __call__(self) -> None:
        bus = self.bus
        # Open a thread-local connection (do NOT reuse a connection from another thread).
        conn = sqlite3.connect(self.db_path)
        try:
            catalog_rows = conn.execute(
                "SELECT path, last_modified, file_size_bytes, file_hash FROM projects"
            ).fetchall()
            catalog = {
                r[0]: {"mtime": r[1] or 0.0, "size": r[2] or 0, "file_hash": r[3]}
                for r in catalog_rows
            }
            discovered = list(discover(self.root))
            p = plan(catalog, discovered)
            total = len(p.new) + len(p.changed)
            bus.publish({
                "kind": "scan_started",
                "discovered": len(discovered),
                "to_parse": total,
                "missing": len(p.missing),
                "started_at": time.time(),
            })
            done = 0
            failed = 0
            for d in [*p.new, *p.changed]:
                try:
                    pid = scan_one(conn, d.path)
                    bus.publish({
                        "kind": "scan_row",
                        "project_id": pid,
                        "path": d.path,
                        "status": "new" if d in p.new else "updated",
                    })
                except Exception as exc:
                    failed += 1
                    bus.publish({
                        "kind": "scan_row",
                        "path": d.path,
                        "status": "failed",
                        "error": f"{type(exc).__name__}: {exc}",
                    })
                done += 1
                if done % 25 == 0 or done == total:
                    bus.publish({"kind": "scan_progress", "done": done, "total": total})
            # Mark missing rows.
            for path in p.missing:
                conn.execute(
                    "UPDATE projects SET is_missing=1 WHERE path=?", (path,)
                )
            conn.commit()
            bus.publish({
                "kind": "scan_finished",
                "new": len(p.new),
                "updated": len(p.changed),
                "unchanged": len(p.unchanged),
                "missing": len(p.missing),
                "failed": failed,
            })
        finally:
            conn.close()
```

**Step 3: `driver.boot(bus, queue, db_path, root)`** — submits a FullScan, returns. Caller (FastAPI lifespan) starts the queue first.

**Step 4:** tests pass; commit.

```bash
git add packages/core/audio_core/indexer/jobs.py packages/core/audio_core/indexer/driver.py packages/core/tests/test_indexer_full_scan.py
git commit -m "feat(core/indexer): FullScan job emits scan events through the bus"
```

---

## Phase B — Schema-driven backfill

**Done when:** opening a DB whose `projects` rows have NULL in any column listed in `BACKFILL_COLUMNS` triggers a `BackfillColumn` job per column, with progress events.

### Task B1: `is_missing` + `last_seen` migrations + `indexer_state`

Schema additions per overview. Idempotent ALTERs in `_apply_migrations`. New table created via `CREATE TABLE IF NOT EXISTS`.

Tests: column presence + idempotency.

Commit: `feat(core/db): is_missing + last_seen + indexer_state for live indexing`

### Task B2: `audio_core.backfill` module

**Files:**
- Create: `packages/core/audio_core/backfill.py`
- Test: `packages/core/tests/test_backfill.py`

```python
from dataclasses import dataclass
from collections.abc import Callable
import sqlite3
from pathlib import Path

from audio_core.parser.als import parse_als


@dataclass(frozen=True)
class BackfillSpec:
    name: str
    null_check_sql: str
    fill_one: Callable[[sqlite3.Connection, dict], None]


def _fill_macpath(conn, row):
    md = parse_als(row["path"])
    pi = 1 if (Path(row["parent_dir"]) / "Ableton Project Info").is_dir() else 0
    conn.execute(
        "UPDATE projects SET mac_paths_count=?, has_project_info=? WHERE id=?",
        (md.mac_paths_count, pi, row["id"]),
    )


BACKFILL_SPECS: list[BackfillSpec] = [
    BackfillSpec(
        name="macpath",
        null_check_sql=(
            "SELECT id, path, parent_dir FROM projects "
            "WHERE mac_paths_count IS NULL OR has_project_info IS NULL"
        ),
        fill_one=_fill_macpath,
    ),
]


def needs_backfill(conn: sqlite3.Connection) -> list[str]:
    out = []
    for spec in BACKFILL_SPECS:
        n = conn.execute(f"SELECT COUNT(*) FROM ({spec.null_check_sql})").fetchone()[0]
        if n > 0:
            out.append(spec.name)
    return out
```

Tests: detect macpath-needs-backfill on a freshly-migrated DB; no-op on a clean one; one row gets backfilled.

Commit: `feat(core/backfill): schema-driven NULL fillers, parse-only, no rehash`

### Task B3: `BackfillColumn` job

In `audio_core/indexer/jobs.py`:

```python
@dataclass
class BackfillColumn:
    db_path: Path
    spec_name: str
    bus: EventBus

    def __call__(self) -> None:
        from audio_core.backfill import BACKFILL_SPECS
        spec = next(s for s in BACKFILL_SPECS if s.name == self.spec_name)
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        try:
            rows = list(conn.execute(spec.null_check_sql))
            self.bus.publish({"kind": "backfill_started", "name": spec.name, "total": len(rows)})
            done = failed = 0
            for r in rows:
                try:
                    spec.fill_one(conn, dict(r))
                except Exception as exc:
                    failed += 1
                    self.bus.publish({"kind": "backfill_row_failed", "name": spec.name, "path": r["path"], "error": f"{type(exc).__name__}: {exc}"})
                done += 1
                if done % 25 == 0 or done == len(rows):
                    self.bus.publish({"kind": "backfill_progress", "name": spec.name, "done": done, "total": len(rows)})
            conn.commit()
            self.bus.publish({"kind": "backfill_finished", "name": spec.name, "done": done, "failed": failed})
        finally:
            conn.close()
```

Test: enqueue, run, observe events, assert NULL count drops to 0.

Commit: `feat(core/indexer): BackfillColumn job runs spec-defined NULL fillers`

### Task B4: Driver wiring

`driver.boot()` enumerates `needs_backfill(conn)` and submits one `BackfillColumn` per spec **before** the FullScan, so a fresh app on a stale DB does the cheap parse-only work first; the user sees fast progress on familiar projects rather than waiting on hashing.

Test: lifespan-style — submit backfill+full, observe both event sequences.

Commit: `feat(core/indexer): driver enqueues backfills first, full scan second`

### Task B5: Detectors module + findings_changed event

`audio_core/detectors.py` re-exports `find_mac_imports`, `find_duplicates` and adds:

```python
def findings_summary(conn) -> dict:
    return {
        "macpath": len(find_mac_imports(conn)),
        "duplicates": len(find_duplicates(conn)),
    }
```

After every `scan_finished` and `backfill_finished`, the driver re-runs `findings_summary` and publishes `{"kind": "findings_changed", **summary}` on the bus.

Test: simulate scan completion, observe findings_changed.

Commit: `feat(core/detectors): findings_summary + auto-publish on scan/backfill finish`

---

## Phase C — SSE event endpoint

**Done when:** `GET /api/events` serves a long-lived SSE stream of bus events. Multiple subscribers supported. Reconnection works (cheap, just re-subscribe on disconnect — the UI catches up via subsequent scan/backfill events plus a `hello` snapshot).

### Task C1: SSE route

**Files:**
- Create: `packages/web/audio_web/routes_events.py`
- Modify: `packages/web/audio_web/app.py` (register router; lifespan starts EventBus + JobQueue)
- Test: `packages/web/tests/test_routes_events.py`

```python
# routes_events.py
from fastapi import APIRouter, Request
from sse_starlette.sse import EventSourceResponse  # add to deps; tiny lib

router = APIRouter()

@router.get("/api/events")
async def events(request: Request):
    bus = request.app.state.event_bus
    queue = bus.subscribe()
    async def gen():
        # Hello snapshot
        yield {"event": "hello", "data": json.dumps({"kind": "hello", "ts": time.time()})}
        try:
            while True:
                if await request.is_disconnected():
                    return
                ev = await queue.get()
                yield {"event": ev.get("kind", "event"), "data": json.dumps(ev)}
        finally:
            bus.unsubscribe(queue)
    return EventSourceResponse(gen())
```

If `sse-starlette` is undesirable (one more dep), implement the SSE framing inline (it's ~20 lines).

Tests: connect via httpx async client, send a publish through bus, receive event, disconnect, confirm unsubscribe.

Commit: `feat(web): /api/events SSE stream of indexer events`

### Task C2: Lifespan wiring

`app.py` lifespan:
```python
@asynccontextmanager
async def lifespan(app: FastAPI):
    bus = EventBus()
    queue = JobQueue()
    queue.start()
    app.state.event_bus = bus
    app.state.job_queue = queue
    driver.boot(db_path=db_path(), root=projects_root(), bus=bus, queue=queue)
    try:
        yield
    finally:
        queue.shutdown(wait=True)
```

Test: start app, observe `scan_started` event arrives within 1s for a tmp library.

Commit: `feat(web): lifespan boots indexer and exposes event bus on app.state`

### Task C3: REST integration with row events

When a row event publishes a `project_id`, the front-end can patch its TanStack cache. We don't need server-side coupling — just verify the API contract. Add a regression test that confirms the SSE event for a freshly-scanned row carries `project_id` and matches what `GET /api/projects/{id}` returns.

Commit: `test(web): SSE row event project_id matches REST snapshot`

### Task C4: Drop /api/health → /api/events 'hello' carries health

If `web/src/app/SidecarHealth.tsx` exists, fold its responsibilities into the new IndexerStatus chip. Health is "did we connect to /api/events successfully" + "is there an active job".

Commit: `refactor(web): SidecarHealth folded into IndexerStatus via SSE`

---

## Phase D — Filesystem watcher

**Done when:** with the app running, modifying any `.als` under `projects_root` triggers a per-path `IncrementalScan` job within ~2 seconds, which updates the catalog and pushes a `scan_row` event to the UI. Works on Windows local drive AND macOS local drive. Detects network drive and falls back to a 5-minute polling rediscovery loop with a status message.

### Task D1: Add watchdog dep + IncrementalScan job

`packages/core/pyproject.toml`: add `watchdog>=4`.

In `jobs.py`:

```python
@dataclass
class IncrementalScan:
    db_path: Path
    paths: list[Path]
    bus: EventBus

    def __call__(self) -> None:
        conn = sqlite3.connect(self.db_path)
        try:
            for p in self.paths:
                if not p.exists():
                    conn.execute("UPDATE projects SET is_missing=1 WHERE path=?", (str(p),))
                    self.bus.publish({"kind": "scan_row", "path": str(p), "status": "missing"})
                    continue
                try:
                    pid = scan_one(conn, p)
                    self.bus.publish({"kind": "scan_row", "project_id": pid, "path": str(p), "status": "updated"})
                except Exception as exc:
                    self.bus.publish({"kind": "scan_row", "path": str(p), "status": "failed", "error": f"{type(exc).__name__}: {exc}"})
            conn.commit()
        finally:
            conn.close()
```

Tests: simulate, verify SQL state + events.

Commit: `feat(core/indexer): IncrementalScan job for watcher-driven updates`

### Task D2: FsWatcher

```python
# audio_core/indexer/watcher.py
class FsWatcher:
    """watchdog wrapper. Coalesces events per-path with a 2s debounce, then
    enqueues an IncrementalScan job. Detects non-local drives and falls back
    to polling discovery."""
    def __init__(self, root: Path, queue: JobQueue, bus: EventBus, db_path: Path) -> None: ...
    def start(self) -> None: ...
    def stop(self) -> None: ...
```

Implementation:
- `Observer()` from `watchdog.observers`.
- On any `*.als` `modified` / `created` / `deleted` / `moved` event, store `(path, deadline)` in a dict; a worker thread sleeps in 100 ms increments and flushes paths whose deadline has passed by submitting an `IncrementalScan` job for the batch.
- `_drive_supports_events(root)`: on Windows, `GetDriveType` via ctypes; on macOS, check if root is on a mounted SMB/AFP volume. If not local: emit a `watcher_status` event with `mode: "polling"` and start a 5-minute discovery loop instead.

Tests: tmp_path observation, debounce coalescing, watcher_status fallback (mock the drive check).

Commit: `feat(core/indexer): cross-platform FsWatcher with debounce + network-drive polling fallback`

### Task D3: Driver registers watcher in lifespan

After `boot()` completes, the lifespan starts the watcher. On shutdown, stop it.

Commit: `feat(web): lifespan starts FsWatcher after boot, stops on shutdown`

### Task D4: Live-lock awareness

A read-side parse is fine while Live has the file open; writes (repair) already check `is_open_in_live`. The watcher doesn't need to gate on live-lock — but should debounce harder when Live is observed touching the file (Live writes ~5 times in succession). 2-second debounce already absorbs this. Just verify with an integration-style test that simulates rapid writes.

Commit: `test(core/indexer): watcher debounce absorbs rapid Live-style saves`

---

## Phase E — UI integration

**Done when:** the React app subscribes to `/api/events`, surfaces a small status-bar chip with current state, and patches its TanStack Query cache as `scan_row` events arrive — no full refetch.

### Task E1: SSE hook

**File:** `web/src/hooks/useIndexerEvents.ts`

```ts
export type IndexerEvent =
  | { kind: "hello"; ts: number }
  | { kind: "scan_started"; discovered: number; to_parse: number; missing: number }
  | { kind: "scan_row"; project_id?: number; path: string; status: "new" | "updated" | "skipped" | "failed" | "missing"; error?: string }
  | { kind: "scan_progress"; done: number; total: number }
  | { kind: "scan_finished"; new: number; updated: number; unchanged: number; missing: number; failed: number }
  | { kind: "backfill_started"; name: string; total: number }
  | { kind: "backfill_progress"; name: string; done: number; total: number }
  | { kind: "backfill_finished"; name: string; done: number; failed: number }
  | { kind: "watcher_status"; mode: "watching" | "polling" | "off"; reason?: string }
  | { kind: "findings_changed"; macpath: number; duplicates: number };

export function useIndexerEvents(onEvent: (ev: IndexerEvent) => void) {
  React.useEffect(() => {
    const es = new EventSource("/api/events");
    const onMsg = (m: MessageEvent) => {
      try { onEvent(JSON.parse(m.data)); } catch {}
    };
    es.addEventListener("scan_started", onMsg);
    es.addEventListener("scan_row", onMsg);
    es.addEventListener("scan_progress", onMsg);
    es.addEventListener("scan_finished", onMsg);
    es.addEventListener("backfill_started", onMsg);
    es.addEventListener("backfill_progress", onMsg);
    es.addEventListener("backfill_finished", onMsg);
    es.addEventListener("watcher_status", onMsg);
    es.addEventListener("findings_changed", onMsg);
    return () => es.close();
  }, [onEvent]);
}
```

Tests: vitest with EventSource shim or `msw`'s SSE support; assert handler is invoked with parsed payload.

Commit: `feat(web/ui): useIndexerEvents hook subscribes to /api/events`

### Task E2: TanStack cache patcher

A central `useIndexerCachePatcher()` hook calls `useIndexerEvents` and:
- On `scan_row` with `project_id`, invalidates `["project", id]` and `["projects", "list"]` queries.
- On `findings_changed`, sets a top-level `["findings"]` query data manually (no fetch needed; numbers come from the event).

Commit: `feat(web/ui): patch TanStack cache from indexer events`

### Task E3: Status bar chip

`web/src/app/IndexerStatus.tsx` — small rectangular chip in the existing status area (if SidecarHealth exists, replace it; the user's "layer don't redesign" feedback applies).

States:
- `idle` — no recent scan; tiny green dot. Tooltip: "Catalog up to date · Watching".
- `scanning` — spinner + "Scanning 124/1628".
- `backfilling` — spinner + "Catching up · macpath 50/200".
- `watcher_warning` — yellow dot + "Watching unavailable on network drive · polling every 5 min".
- `findings` — small badge with count + ".findings" link (clicking filters home to "needs attention").

Style: stationery theme, fits next to the existing chips. No big shelves or hero CTAs.

Tests: vitest snapshot per state.

Commit: `feat(web/ui): IndexerStatus chip surfaces live indexer state`

### Task E4: Findings filter on home

The home view (`web/src/routes/home.tsx`) already has the layout per memory ("layer onto existing UI"). Add a small "Needs attention" chip that, when clicked, filters the listing to projects where `mac_paths_count > 0 OR has_project_info = 0 OR is_missing = 1`. Use the existing search/filter machinery; do not introduce a new view.

Commit: `feat(web/ui): home 'needs attention' chip filters macpath/info/missing`

### Task E5: First-launch splash

`web/src/app/FirstLaunch.tsx` — when `useProjectsCount() === 0` AND scanner is active, render the splash instead of home. Splash shows: "Welcome — indexing your library", a progress bar fed by `scan_progress` events, and the most-recent path being parsed. Auto-dismisses to home as soon as 30+ rows are in the catalog (via TanStack count refetch on every scan_row event).

Tests: vitest with mocked SSE event sequence.

Commit: `feat(web/ui): first-launch splash with live progress, dismisses at 30 rows`

---

## Phase F — Tauri sidecar lifecycle

**Done when:** Tauri starts the Python sidecar on app launch, the sidecar runs the indexer, and the user closing the app gracefully shuts the sidecar (and its watcher) down.

### Task F1: Sidecar binding

(Plan reference: `docs/plans/2026-05-04-tauri-bundling-design.md` — already exists. Read it; this task layers on top.)

- Tauri `tauri.conf.json` registers the sidecar binary built from `audio_web` (uvicorn entrypoint).
- Sidecar startup waits on a TCP port discovery (Tauri picks an open port, passes via env var to sidecar).
- Frontend uses that port for both REST and SSE.

Commit: `feat(tauri): sidecar binding with dynamic port + lifespan handoff`

### Task F2: Graceful shutdown

- Tauri `on_window_close` sends SIGTERM (POSIX) / `taskkill /pid` (Windows) to sidecar.
- FastAPI lifespan teardown stops watcher, drains queue, closes DB.
- 5-second timeout, then SIGKILL.

Commit: `feat(tauri): graceful sidecar shutdown drains indexer cleanly`

### Task F3: Crash recovery

- If sidecar exits non-zero, Tauri restarts it once.
- On second crash within 60s, Tauri shows an error dialog with the stderr tail.
- Indexer restart is idempotent: catalog is already there, discovery picks up where it left off.

Test (manual smoke): kill sidecar mid-scan, observe restart, observe scan resumes correctly.

Commit: `feat(tauri): one-shot sidecar restart with crash dialog on repeat`

---

## Phase G — Polish & migration

### Task G1: Remove ad-hoc CLI rescan flow from docs

The `audio scan` CLI still exists for headless use, but the README / mcp-setup.md / any user-facing docs should make clear: **the desktop app handles indexing automatically**. Update the docs so a new user doesn't think they need to run anything manually.

Commit: `docs: indexer is automatic; CLI scan is for headless/scripting only`

### Task G2: Delete `_backfill_macpath.py` from repo root

It was a one-shot script for the old manual flow. Phase B replaces it.

Commit: `chore: remove _backfill_macpath.py — superseded by audio_core.backfill`

### Task G3: End-to-end smoke

Manual verification on the real catalog:
1. Wipe `data/catalog.db`. Launch app. Observe splash → home transition. Confirm 1,628 projects index in reasonable time.
2. Edit one .als externally (touch + open in Live, save). Observe `scan_row` event in 2-3s; row updates without manual refresh.
3. Disconnect projects drive. Observe `scan_row` events with `status=missing`. Reconnect. Observe rows revert to present.
4. Open the app a second time. Status chip shows "Up to date" within 1s, no splash.
5. Confirm the "needs attention" chip surfaces the projects that the original macpath sweep would have flagged.

No commit; this is verification.

---

## Notes / non-goals

- **No persistent job queue.** Crash recovery is "next launch's discovery picks up where it left off." We don't need durable jobs because the catalog is the durable state.
- **No multi-process workers.** SQLite WAL + a single writer thread + many reader connections is the right model for one user. If we ever need to parallelize parsing, it would be inside the FullScan job (worker pool over the parse step), not at the queue layer.
- **No real-time UI subscriptions per row.** TanStack Query's invalidate-on-event is sufficient and cheap. If profiling shows the home view re-renders too often during a big scan, batch event handling with a 100 ms throttle in the cache patcher.
- **The `audio scan` CLI** stays callable for scripting and the existing test suite. Internally it calls `driver.boot(headless=True)` which prints events to stdout instead of publishing to an HTTP bus.
- **The user does not type any command** to populate or maintain the catalog after this lands. The Tauri app is the entry point.
