"""Tests for the FastAPI lifespan that boots the indexer.

Lifespan must:
  * Construct an EventBus + JobQueue and stash them on app.state.
  * Start the queue and call driver.boot(...) so a startup FullScan is enqueued.
  * Shut the queue down cleanly on app teardown.

The plan's acceptance test is "scan_started arrives within 1s for a tmp
library". Subscribing AFTER the lifespan has called boot() is racy: the
worker thread may have already published scan_started by the time the test
calls bus.subscribe(). We monkeypatch driver.boot to subscribe the moment
boot is invoked (before submitting jobs) so the test is deterministic.
"""

from __future__ import annotations

import asyncio

from audio_core.indexer import driver
from audio_web.app import create_app
from fastapi.testclient import TestClient


def test_lifespan_exposes_bus_and_queue(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    (tmp_path / "Projects").mkdir()
    (tmp_path / "data").mkdir()

    with TestClient(create_app()) as client:
        assert client.app.state.event_bus is not None
        assert client.app.state.job_queue is not None


def test_lifespan_shuts_down_queue(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    (tmp_path / "Projects").mkdir()
    (tmp_path / "data").mkdir()

    with TestClient(create_app()) as client:
        queue = client.app.state.job_queue
        # Worker thread is alive while the lifespan is active.
        assert queue._thread is not None and queue._thread.is_alive()

    # After the with block, lifespan shutdown ran and the worker thread joined.
    assert queue._thread is None


async def test_scan_started_event_within_one_second(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    (tmp_path / "Projects").mkdir()
    (tmp_path / "data").mkdir()

    captured: dict = {}
    real_boot = driver.boot

    def spy_boot(*, db_path, root, bus, queue, sample_roots=None):
        # Subscribe BEFORE the real boot enqueues the FullScan, so the
        # worker thread can't publish scan_started before we are listening.
        captured["event_queue"] = bus.subscribe()
        real_boot(db_path=db_path, root=root, bus=bus, queue=queue, sample_roots=sample_roots)

    monkeypatch.setattr("audio_web.app.driver.boot", spy_boot)

    with TestClient(create_app()):
        q = captured["event_queue"]
        deadline = asyncio.get_event_loop().time() + 1.0
        seen_kinds: list[str] = []
        while True:
            remaining = deadline - asyncio.get_event_loop().time()
            if remaining <= 0:
                raise AssertionError(
                    f"scan_started not seen within 1s; saw kinds={seen_kinds}"
                )
            ev = await asyncio.wait_for(q.get(), timeout=remaining)
            seen_kinds.append(ev.get("kind", ""))
            if ev.get("kind") == "scan_started":
                break
