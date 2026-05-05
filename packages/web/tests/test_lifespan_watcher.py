"""Tests for the FastAPI lifespan wiring of FsWatcher.

Lifespan must:
  * After driver.boot(), construct an FsWatcher and call .start().
  * Stash it on app.state.fs_watcher so callers (and tests) can inspect it.
  * On lifespan exit, call watcher.stop() BEFORE queue.shutdown(), since the
    watcher's flusher submits jobs onto the queue.

Like test_lifespan_boot.py, we monkeypatch driver.boot to subscribe the bus
*before* the watcher publishes its 'watching' / 'polling' status event, so
the test is deterministic across the watcher startup race.
"""

from __future__ import annotations

import asyncio

from audio_core.indexer import driver
from audio_core.indexer.watcher import FsWatcher
from audio_web.app import create_app
from fastapi.testclient import TestClient


def _setup_root(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    (tmp_path / "Projects").mkdir()
    (tmp_path / "data").mkdir()


def test_lifespan_exposes_fs_watcher(tmp_path, monkeypatch):
    _setup_root(tmp_path, monkeypatch)

    with TestClient(create_app()) as client:
        watcher = client.app.state.fs_watcher
        assert isinstance(watcher, FsWatcher)
        # _started flips True inside .start(); confirms lifespan called start().
        assert watcher._started is True


async def test_lifespan_publishes_watcher_status_on_start(tmp_path, monkeypatch):
    _setup_root(tmp_path, monkeypatch)

    captured: dict = {}
    real_boot = driver.boot

    def spy_boot(*, db_path, root, bus, queue, sample_roots=None):
        # Subscribe BEFORE the lifespan calls watcher.start(), which is what
        # publishes the 'watching' / 'polling' event. boot() runs first, so
        # subscribing inside the spy guarantees we see the watcher event.
        captured["event_queue"] = bus.subscribe()
        real_boot(db_path=db_path, root=root, bus=bus, queue=queue, sample_roots=sample_roots)

    monkeypatch.setattr("audio_web.app.driver.boot", spy_boot)

    with TestClient(create_app()):
        q = captured["event_queue"]
        deadline = asyncio.get_event_loop().time() + 1.0
        seen_modes: list[str] = []
        while True:
            remaining = deadline - asyncio.get_event_loop().time()
            if remaining <= 0:
                raise AssertionError(
                    f"watcher_status start event not seen within 1s; "
                    f"saw modes={seen_modes}"
                )
            ev = await asyncio.wait_for(q.get(), timeout=remaining)
            if ev.get("kind") == "watcher_status":
                mode = ev.get("mode")
                seen_modes.append(mode)
                # On a local drive we get 'watching'; on SMB/AFP/network we
                # get 'polling'. Either is correct — both prove start() ran.
                if mode in ("watching", "polling"):
                    break


async def test_lifespan_publishes_watcher_status_off_on_exit(tmp_path, monkeypatch):
    _setup_root(tmp_path, monkeypatch)

    captured: dict = {"events": []}
    real_boot = driver.boot

    def spy_boot(*, db_path, root, bus, queue, sample_roots=None):
        q = bus.subscribe()
        captured["event_queue"] = q
        captured["bus"] = bus
        real_boot(db_path=db_path, root=root, bus=bus, queue=queue, sample_roots=sample_roots)

    monkeypatch.setattr("audio_web.app.driver.boot", spy_boot)

    # Drain events on a background task so the bus's call_soon_threadsafe
    # delivery during shutdown actually lands in our list before the loop
    # tears down.
    drained: list[dict] = []
    drainer_task: asyncio.Task | None = None

    async def drain(q: asyncio.Queue) -> None:
        try:
            while True:
                ev = await q.get()
                drained.append(ev)
        except asyncio.CancelledError:
            # Final non-blocking sweep.
            while not q.empty():
                drained.append(q.get_nowait())
            raise

    with TestClient(create_app()):
        q = captured["event_queue"]
        drainer_task = asyncio.create_task(drain(q))
        # Yield enough for the spy + start events to flow through.
        await asyncio.sleep(0.1)

    # Lifespan exit ran. Give the drainer one tick to absorb the final
    # 'off' event published synchronously from watcher.stop().
    await asyncio.sleep(0.05)
    assert drainer_task is not None
    drainer_task.cancel()
    try:
        await drainer_task
    except asyncio.CancelledError:
        pass

    off_events = [
        ev
        for ev in drained
        if ev.get("kind") == "watcher_status" and ev.get("mode") == "off"
    ]
    assert off_events, (
        f"expected watcher_status mode=off after lifespan exit; "
        f"saw {[ev for ev in drained if ev.get('kind') == 'watcher_status']}"
    )


def test_lifespan_clean_shutdown_stops_watcher_and_queue(tmp_path, monkeypatch):
    _setup_root(tmp_path, monkeypatch)

    # No exception means the inner try/finally ordering held: watcher.stop()
    # ran before queue.shutdown(), and neither raised.
    with TestClient(create_app()) as client:
        watcher = client.app.state.fs_watcher
        queue = client.app.state.job_queue
        assert watcher._started is True
        assert queue._thread is not None and queue._thread.is_alive()

    # Both resources released cleanly:
    #   - watcher: observer torn down, thread joined.
    #   - queue: worker thread joined.
    assert watcher._observer is None
    assert watcher._started is False
    assert queue._thread is None
