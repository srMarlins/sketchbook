import asyncio

import pytest

from audio_core.db.connection import open_db
from audio_core.indexer.events import EventBus
from audio_core.indexer.jobs import FullSampleScan, IncrementalSampleScan


def _drain(q: asyncio.Queue) -> list[dict]:
    out: list[dict] = []
    while not q.empty():
        out.append(q.get_nowait())
    return out


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


@pytest.mark.asyncio
async def test_full_sample_scan_emits_events(tmp_path):
    db = tmp_path / "c.db"
    open_db(db).close()
    root = tmp_path / "samples"
    root.mkdir()
    (root / "a.wav").write_bytes(b"x")
    bus = EventBus()
    sub = bus.subscribe()
    FullSampleScan(db_path=db, roots=[root], bus=bus)()
    await asyncio.sleep(0)
    events = _drain(sub)
    kinds = [e["kind"] for e in events]
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
