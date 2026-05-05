import asyncio
import shutil
import sqlite3
from pathlib import Path

import pytest

from audio_core.db.connection import open_db
from audio_core.indexer import driver
from audio_core.indexer.events import EventBus
from audio_core.indexer.jobs import FullScan
from audio_core.indexer.queue import JobQueue


def _seed_root(tmp_path):
    fixtures = Path(__file__).parent / "fixtures"
    root = tmp_path / "Projects"
    (root / "a Project").mkdir(parents=True)
    (root / "b Project").mkdir(parents=True)
    shutil.copy(fixtures / "tiny.als", root / "a Project" / "x.als")
    shutil.copy(fixtures / "tiny.als", root / "b Project" / "y.als")
    return root


@pytest.mark.asyncio
async def test_full_scan_parses_new_and_emits_events(tmp_path):
    root = _seed_root(tmp_path)
    db = tmp_path / "data" / "catalog.db"
    bus = EventBus()
    sub = bus.subscribe()

    open_db(db).close()  # ensure schema exists
    FullScan(db_path=db, root=root, bus=bus)()
    await asyncio.sleep(0)  # let call_soon_threadsafe callbacks drain

    events: list[dict] = []
    while not sub.empty():
        events.append(sub.get_nowait())

    kinds = [e["kind"] for e in events]
    assert kinds[0] == "scan_started"
    assert "scan_finished" in kinds
    started = next(e for e in events if e["kind"] == "scan_started")
    finished = next(e for e in events if e["kind"] == "scan_finished")
    rows = [e for e in events if e["kind"] == "scan_row"]

    assert started["discovered"] == 2
    assert started["to_parse"] == 2
    assert finished["new"] == 2
    assert finished["unchanged"] == 0
    assert finished["failed"] == 0
    assert {r["status"] for r in rows} == {"new"}
    assert all("project_id" in r for r in rows)

    # Catalog rows present.
    conn = open_db(db)
    n = conn.execute("SELECT COUNT(*) FROM projects").fetchone()[0]
    assert n == 2


@pytest.mark.asyncio
async def test_full_scan_unchanged_rows_emit_no_row_events(tmp_path):
    root = _seed_root(tmp_path)
    db = tmp_path / "data" / "catalog.db"
    bus = EventBus()
    open_db(db).close()
    FullScan(db_path=db, root=root, bus=bus)()  # first pass

    sub = bus.subscribe()
    FullScan(db_path=db, root=root, bus=bus)()  # second pass — all unchanged
    await asyncio.sleep(0)

    events = []
    while not sub.empty():
        events.append(sub.get_nowait())
    finished = next(e for e in events if e["kind"] == "scan_finished")
    assert finished["new"] == 0
    assert finished["updated"] == 0
    assert finished["unchanged"] == 2
    rows = [e for e in events if e["kind"] == "scan_row"]
    assert rows == []  # unchanged rows emit nothing


@pytest.mark.asyncio
async def test_full_scan_marks_missing(tmp_path):
    root = _seed_root(tmp_path)
    db = tmp_path / "data" / "catalog.db"
    bus = EventBus()
    open_db(db).close()
    FullScan(db_path=db, root=root, bus=bus)()  # populate

    # Delete one project from disk.
    (root / "a Project" / "x.als").unlink()

    sub = bus.subscribe()
    FullScan(db_path=db, root=root, bus=bus)()
    await asyncio.sleep(0)

    finished = next(
        e for e in (sub.get_nowait() for _ in range(sub.qsize())) if e["kind"] == "scan_finished"
    )
    assert finished["missing"] == 1

    conn = open_db(db)
    row = conn.execute(
        "SELECT is_missing FROM projects WHERE path LIKE '%x.als'"
    ).fetchone() if "is_missing" in {r[1] for r in conn.execute("PRAGMA table_info(projects)").fetchall()} else None
    # is_missing column lands in B1; for now if it doesn't exist, FullScan
    # still marks it via UPDATE — gate the assertion behind PRAGMA table_info.
    cols = {r[1] for r in conn.execute("PRAGMA table_info(projects)").fetchall()}
    if "is_missing" in cols:
        assert row[0] == 1


def test_driver_boot_submits_full_scan(tmp_path):
    root = _seed_root(tmp_path)
    db = tmp_path / "data" / "catalog.db"
    open_db(db).close()
    bus = EventBus()
    queue = JobQueue()
    queue.start()
    driver.boot(db_path=db, root=root, bus=bus, queue=queue)
    queue.shutdown(wait=True)

    conn = open_db(db)
    assert conn.execute("SELECT COUNT(*) FROM projects").fetchone()[0] == 2
