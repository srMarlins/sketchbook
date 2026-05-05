import asyncio
import shutil
from pathlib import Path

import pytest

from audio_core.db.connection import open_db
from audio_core.db.projects import upsert_project
from audio_core.indexer import driver
from audio_core.indexer.events import EventBus
from audio_core.indexer.queue import JobQueue
from audio_core.parser.model import ProjectMetadata


def _seed_legacy_row(conn, *, path, parent_dir):
    pid = upsert_project(
        conn,
        path=path,
        name=Path(path).stem,
        parent_dir=parent_dir,
        file_hash="h",
        last_modified=0.0,
        meta=ProjectMetadata(),
    )
    conn.execute(
        "UPDATE projects SET mac_paths_count=NULL, has_project_info=NULL WHERE id=?",
        (pid,),
    )
    conn.commit()
    return pid


def _drain(sub):
    out = []
    while not sub.empty():
        out.append(sub.get_nowait())
    return out


@pytest.mark.asyncio
async def test_boot_runs_backfills_before_full_scan(tmp_path):
    fixtures = Path(__file__).parent / "fixtures"
    root = tmp_path / "Projects"
    root.mkdir()
    # A legacy row in the DB that needs the macpath backfill.
    legacy = root / "legacy Project"
    legacy.mkdir()
    (legacy / "Ableton Project Info").mkdir()
    shutil.copy(fixtures / "mac_imported_tiny.als", legacy / "x.als")
    # A new project on disk that the FullScan will discover.
    fresh = root / "fresh Project"
    fresh.mkdir()
    shutil.copy(fixtures / "tiny.als", fresh / "y.als")

    db = tmp_path / "data" / "catalog.db"
    conn = open_db(db)
    _seed_legacy_row(conn, path=str(legacy / "x.als"), parent_dir=str(legacy))
    conn.close()

    bus = EventBus()
    sub = bus.subscribe()
    queue = JobQueue()
    queue.start()
    try:
        driver.boot(db_path=db, root=root, bus=bus, queue=queue)
    finally:
        queue.shutdown(wait=True)
    await asyncio.sleep(0)

    events = _drain(sub)
    kinds = [e["kind"] for e in events]
    assert "backfill_started" in kinds
    assert "backfill_finished" in kinds
    assert "scan_started" in kinds
    assert "scan_finished" in kinds
    assert kinds.index("backfill_started") < kinds.index("scan_started")
    assert kinds.index("backfill_finished") < kinds.index("scan_started")


@pytest.mark.asyncio
async def test_boot_skips_backfill_when_not_needed(tmp_path):
    fixtures = Path(__file__).parent / "fixtures"
    root = tmp_path / "Projects"
    (root / "a Project").mkdir(parents=True)
    shutil.copy(fixtures / "tiny.als", root / "a Project" / "x.als")

    db = tmp_path / "data" / "catalog.db"
    open_db(db).close()  # fresh empty DB — no rows, no NULLs

    bus = EventBus()
    sub = bus.subscribe()
    queue = JobQueue()
    queue.start()
    try:
        driver.boot(db_path=db, root=root, bus=bus, queue=queue)
    finally:
        queue.shutdown(wait=True)
    await asyncio.sleep(0)

    events = _drain(sub)
    kinds = [e["kind"] for e in events]
    assert "backfill_started" not in kinds
    assert "scan_started" in kinds
    assert "scan_finished" in kinds
