import asyncio
import shutil
import sqlite3
from pathlib import Path

import pytest

from audio_core.db.connection import open_db
from audio_core.indexer.events import EventBus
from audio_core.indexer.jobs import IncrementalScan
from audio_core.scanner.scan import scan_one


FIXTURES = Path(__file__).parent / "fixtures"


def _seed_als(tmp_path: Path, project_name: str = "x Project", als_name: str = "x.als") -> Path:
    project_dir = tmp_path / "Projects" / project_name
    project_dir.mkdir(parents=True, exist_ok=True)
    als = project_dir / als_name
    shutil.copy(FIXTURES / "tiny.als", als)
    return als


def _drain(sub: asyncio.Queue) -> list[dict]:
    out: list[dict] = []
    while not sub.empty():
        out.append(sub.get_nowait())
    return out


@pytest.mark.asyncio
async def test_incremental_scan_updates_existing_row(tmp_path):
    als = _seed_als(tmp_path)
    db = tmp_path / "data" / "catalog.db"
    open_db(db).close()

    conn = sqlite3.connect(db)
    pid = scan_one(conn, als)
    conn.commit()
    stored_path = conn.execute(
        "SELECT path FROM projects WHERE id=?", (pid,)
    ).fetchone()[0]
    before_mtime = conn.execute(
        "SELECT last_modified FROM projects WHERE id=?", (pid,)
    ).fetchone()[0]
    conn.close()

    # Bump mtime so the update has a measurable effect.
    new_time = before_mtime + 100
    import os
    os.utime(stored_path, (new_time, new_time))

    bus = EventBus()
    sub = bus.subscribe()
    IncrementalScan(db_path=db, paths=[Path(stored_path)], bus=bus)()
    await asyncio.sleep(0)

    events = _drain(sub)
    assert len(events) == 1
    e = events[0]
    assert e["kind"] == "scan_row"
    assert e["status"] == "updated"
    assert e["project_id"] == pid
    assert e["path"] == stored_path

    conn = sqlite3.connect(db)
    after_mtime = conn.execute(
        "SELECT last_modified FROM projects WHERE id=?", (pid,)
    ).fetchone()[0]
    conn.close()
    assert after_mtime == pytest.approx(new_time, abs=1.0)


@pytest.mark.asyncio
async def test_incremental_scan_inserts_new_row(tmp_path):
    als = _seed_als(tmp_path)
    db = tmp_path / "data" / "catalog.db"
    open_db(db).close()

    bus = EventBus()
    sub = bus.subscribe()
    IncrementalScan(db_path=db, paths=[als], bus=bus)()
    await asyncio.sleep(0)

    events = _drain(sub)
    assert len(events) == 1
    e = events[0]
    assert e["kind"] == "scan_row"
    assert e["status"] == "updated"
    assert "project_id" in e

    conn = sqlite3.connect(db)
    n = conn.execute("SELECT COUNT(*) FROM projects").fetchone()[0]
    conn.close()
    assert n == 1


@pytest.mark.asyncio
async def test_incremental_scan_marks_missing(tmp_path):
    als = _seed_als(tmp_path)
    db = tmp_path / "data" / "catalog.db"
    open_db(db).close()

    conn = sqlite3.connect(db)
    pid = scan_one(conn, als)
    conn.commit()
    stored_path = conn.execute(
        "SELECT path FROM projects WHERE id=?", (pid,)
    ).fetchone()[0]
    conn.close()

    Path(stored_path).unlink()

    bus = EventBus()
    sub = bus.subscribe()
    IncrementalScan(db_path=db, paths=[Path(stored_path)], bus=bus)()
    await asyncio.sleep(0)

    events = _drain(sub)
    assert len(events) == 1
    e = events[0]
    assert e["kind"] == "scan_row"
    assert e["status"] == "missing"
    assert e["path"] == stored_path
    assert "project_id" not in e

    conn = sqlite3.connect(db)
    is_missing = conn.execute(
        "SELECT is_missing FROM projects WHERE id=?", (pid,)
    ).fetchone()[0]
    conn.close()
    assert is_missing == 1


@pytest.mark.asyncio
async def test_incremental_scan_failure_isolation(tmp_path):
    bad_dir = tmp_path / "Projects" / "bad Project"
    bad_dir.mkdir(parents=True)
    bad = bad_dir / "broken.als"
    bad.write_bytes(b"not-a-zip")

    good = _seed_als(tmp_path, project_name="good Project", als_name="g.als")

    db = tmp_path / "data" / "catalog.db"
    open_db(db).close()

    bus = EventBus()
    sub = bus.subscribe()
    IncrementalScan(db_path=db, paths=[bad, good], bus=bus)()
    await asyncio.sleep(0)

    events = _drain(sub)
    assert len(events) == 2
    assert events[0]["status"] == "failed"
    assert "error" in events[0]
    assert events[1]["status"] == "updated"
    assert "project_id" in events[1]

    conn = sqlite3.connect(db)
    rows = conn.execute("SELECT path FROM projects").fetchall()
    conn.close()
    paths = [r[0] for r in rows]
    assert str(good.resolve()) in paths
    assert str(bad.resolve()) not in paths


@pytest.mark.asyncio
async def test_incremental_scan_event_order_matches_paths(tmp_path):
    a = _seed_als(tmp_path, project_name="a Project", als_name="a.als")
    b = _seed_als(tmp_path, project_name="b Project", als_name="b.als")
    c = _seed_als(tmp_path, project_name="c Project", als_name="c.als")

    db = tmp_path / "data" / "catalog.db"
    open_db(db).close()

    bus = EventBus()
    sub = bus.subscribe()
    IncrementalScan(db_path=db, paths=[a, b, c], bus=bus)()
    await asyncio.sleep(0)

    events = _drain(sub)
    paths_seen = [e["path"] for e in events]
    assert paths_seen == [str(a), str(b), str(c)]
