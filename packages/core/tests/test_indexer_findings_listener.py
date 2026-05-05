import asyncio

import pytest

from audio_core.db.connection import open_db
from audio_core.indexer.driver import start_findings_listener
from audio_core.indexer.events import EventBus


async def _next_findings(observer: asyncio.Queue, *, timeout: float = 1.0) -> dict:
    """Pull events off the observer queue until we see findings_changed, or
    timeout. Ignore the trigger events the test publishes itself."""
    async def _scan() -> dict:
        while True:
            ev = await observer.get()
            if ev.get("kind") == "findings_changed":
                return ev

    return await asyncio.wait_for(_scan(), timeout=timeout)


@pytest.mark.asyncio
async def test_scan_finished_publishes_findings_changed(tmp_path):
    db = tmp_path / "c.db"
    open_db(db).close()
    bus = EventBus()
    observer = bus.subscribe()  # subscribe first so we don't miss the publish

    task = asyncio.create_task(start_findings_listener(db_path=db, bus=bus))
    try:
        await asyncio.sleep(0)  # let listener subscribe before we publish
        bus.publish({"kind": "scan_finished", "new": 0})
        ev = await _next_findings(observer)
        assert ev["kind"] == "findings_changed"
        assert ev["macpath"] == 0
        assert ev["duplicates"] == 0
    finally:
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)


@pytest.mark.asyncio
async def test_backfill_finished_publishes_findings_changed(tmp_path):
    db = tmp_path / "c.db"
    open_db(db).close()
    bus = EventBus()
    observer = bus.subscribe()

    task = asyncio.create_task(start_findings_listener(db_path=db, bus=bus))
    try:
        await asyncio.sleep(0)
        bus.publish({"kind": "backfill_finished", "name": "macpath"})
        ev = await _next_findings(observer)
        assert ev["kind"] == "findings_changed"
        assert "macpath" in ev
        assert "duplicates" in ev
    finally:
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)


@pytest.mark.asyncio
async def test_other_events_do_not_trigger_findings_changed(tmp_path):
    db = tmp_path / "c.db"
    open_db(db).close()
    bus = EventBus()
    observer = bus.subscribe()

    task = asyncio.create_task(start_findings_listener(db_path=db, bus=bus))
    try:
        await asyncio.sleep(0)
        bus.publish({"kind": "scan_started", "discovered": 0, "to_parse": 0})
        bus.publish({"kind": "scan_progress", "done": 1, "total": 1})
        bus.publish({"kind": "backfill_started", "name": "macpath", "total": 0})
        with pytest.raises(asyncio.TimeoutError):
            await _next_findings(observer, timeout=0.2)
    finally:
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
