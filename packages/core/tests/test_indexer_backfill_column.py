import asyncio
import shutil
from pathlib import Path

import pytest

from audio_core.backfill import BACKFILL_SPECS, BackfillSpec, needs_backfill
from audio_core.db.connection import open_db
from audio_core.db.projects import upsert_project
from audio_core.indexer.events import EventBus
from audio_core.indexer.jobs import BackfillColumn
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
async def test_backfill_column_fills_nulls_and_emits_events(tmp_path):
    fixtures = Path(__file__).parent / "fixtures"
    db = tmp_path / "catalog.db"
    conn = open_db(db)
    for i in range(3):
        proj = tmp_path / f"p{i} Project"
        proj.mkdir()
        (proj / "Ableton Project Info").mkdir()
        shutil.copy(fixtures / "mac_imported_tiny.als", proj / "x.als")
        _seed_legacy_row(conn, path=str(proj / "x.als"), parent_dir=str(proj))
    conn.close()

    bus = EventBus()
    sub = bus.subscribe()
    BackfillColumn(db_path=db, spec_name="macpath", bus=bus)()
    await asyncio.sleep(0)

    events = _drain(sub)
    kinds = [e["kind"] for e in events]
    assert kinds[0] == "backfill_started"
    assert kinds[-1] == "backfill_finished"
    assert "backfill_progress" in kinds

    started = events[0]
    finished = events[-1]
    assert started["name"] == "macpath"
    assert started["total"] == 3
    assert finished["done"] == 3
    assert finished["failed"] == 0

    conn = open_db(db)
    assert needs_backfill(conn) == []
    n_null = conn.execute(
        "SELECT COUNT(*) FROM projects WHERE mac_paths_count IS NULL OR has_project_info IS NULL"
    ).fetchone()[0]
    assert n_null == 0


@pytest.mark.asyncio
async def test_backfill_column_isolates_row_failures(tmp_path, monkeypatch):
    fixtures = Path(__file__).parent / "fixtures"
    db = tmp_path / "catalog.db"
    conn = open_db(db)
    good = tmp_path / "good Project"
    good.mkdir()
    shutil.copy(fixtures / "tiny.als", good / "x.als")
    _seed_legacy_row(conn, path=str(good / "x.als"), parent_dir=str(good))
    bad_path = str(tmp_path / "missing Project" / "y.als")
    bad_id = _seed_legacy_row(conn, path=bad_path, parent_dir=str(tmp_path / "missing Project"))
    conn.close()

    bus = EventBus()
    sub = bus.subscribe()
    BackfillColumn(db_path=db, spec_name="macpath", bus=bus)()
    await asyncio.sleep(0)

    events = _drain(sub)
    failures = [e for e in events if e["kind"] == "backfill_row_failed"]
    finished = next(e for e in events if e["kind"] == "backfill_finished")
    assert len(failures) == 1
    assert failures[0]["path"] == bad_path
    assert "error" in failures[0]
    assert finished["done"] == 2
    assert finished["failed"] == 1

    conn = open_db(db)
    n_null = conn.execute(
        "SELECT COUNT(*) FROM projects WHERE mac_paths_count IS NULL OR has_project_info IS NULL"
    ).fetchone()[0]
    assert n_null == 1
    bad_row = conn.execute(
        "SELECT mac_paths_count FROM projects WHERE id=?", (bad_id,)
    ).fetchone()
    assert bad_row[0] is None


@pytest.mark.asyncio
async def test_backfill_column_progress_cadence_every_25(tmp_path, monkeypatch):
    db = tmp_path / "catalog.db"
    conn = open_db(db)
    for i in range(30):
        proj = tmp_path / f"p{i:02d} Project"
        proj.mkdir()
        (proj / "x.als").write_bytes(b"")
        _seed_legacy_row(conn, path=str(proj / "x.als"), parent_dir=str(proj))
    conn.close()

    # Replace the macpath spec with a no-op fill_one so we don't depend on
    # parser behavior here — the cadence is what's under test.
    fake_spec = BackfillSpec(
        name="macpath",
        null_check_sql=(
            "SELECT id, path, parent_dir FROM projects "
            "WHERE mac_paths_count IS NULL OR has_project_info IS NULL"
        ),
        fill_one=lambda c, r: c.execute(
            "UPDATE projects SET mac_paths_count=0, has_project_info=0 WHERE id=?",
            (r["id"],),
        ),
    )
    monkeypatch.setattr(
        "audio_core.backfill.BACKFILL_SPECS", [fake_spec],
    )

    bus = EventBus()
    sub = bus.subscribe()
    BackfillColumn(db_path=db, spec_name="macpath", bus=bus)()
    await asyncio.sleep(0)

    events = _drain(sub)
    progress = [e for e in events if e["kind"] == "backfill_progress"]
    dones = [e["done"] for e in progress]
    assert dones == [25, 30]
    assert all(e["total"] == 30 for e in progress)
