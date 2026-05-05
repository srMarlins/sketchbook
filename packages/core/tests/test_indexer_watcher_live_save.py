"""Integration-style regression test: the watcher's debounce must absorb the
rapid burst of writes Live emits when saving a project (~5 writes in quick
succession).

This is intentionally framed differently from the generic D2 coalescing test:
the burst here mimics Live's actual save pattern (5+ rapid writes with real
content changes per write), and timing margins are looser to act as a
stress-style regression rather than a minimal coalescing assertion.
"""
from __future__ import annotations

import threading
import time

import pytest

from audio_core.indexer.events import EventBus
from audio_core.indexer.queue import JobQueue


class _Stub:
    """Replacement for IncrementalScan inside the watcher module."""

    _calls: list[list[str]] = []
    _lock = threading.Lock()

    def __init__(self, *, db_path, paths, bus):
        self.db_path = db_path
        self.paths = list(paths)
        self.bus = bus
        with type(self)._lock:
            type(self)._calls.append(sorted(str(p) for p in paths))

    def __call__(self) -> None:
        return None

    @classmethod
    def reset(cls) -> None:
        with cls._lock:
            cls._calls = []

    @classmethod
    def calls(cls) -> list[list[str]]:
        with cls._lock:
            return list(cls._calls)


def _wait_until(predicate, timeout: float = 1.5, interval: float = 0.05) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    return predicate()


@pytest.fixture
def stub_scan(monkeypatch):
    _Stub.reset()
    monkeypatch.setattr(
        "audio_core.indexer.watcher.IncrementalScan", _Stub
    )
    return _Stub


@pytest.fixture
def started_queue():
    q = JobQueue()
    q.start()
    yield q
    q.shutdown(wait=True)


def _silent_bus() -> EventBus:
    bus = EventBus()
    bus.publish = lambda _e: None  # type: ignore[assignment]
    return bus


def test_debounce_absorbs_rapid_live_save_burst(
    tmp_path, stub_scan, started_queue
):
    """Live writes the .als ~5 times in quick succession when saving a
    project. The watcher's debounce must coalesce these into a single
    IncrementalScan submission.
    """
    from audio_core.indexer.watcher import FsWatcher

    root = tmp_path / "Projects"
    project = root / "Live Set Project"
    project.mkdir(parents=True)
    als = project / "live_set.als"
    als.write_bytes(b"initial")

    w = FsWatcher(
        root=root,
        queue=started_queue,
        bus=_silent_bus(),
        db_path=tmp_path / "catalog.db",
        debounce_s=0.4,
        poll_s=0.05,
        drive_check=lambda _: True,
    )
    w.start()
    try:
        # Live's save pattern: ~5 rapid writes within the debounce window.
        # Spacing 40ms keeps the whole burst (~200ms) well under 0.4s debounce.
        for i in range(5):
            als.write_bytes(b"live-save-data-" + str(i).encode())
            time.sleep(0.04)

        # Condition-poll until the debounce flushes.
        start = time.monotonic()
        while time.monotonic() - start < 1.5 and not stub_scan.calls():
            time.sleep(0.05)

        # Small extra grace to catch any stragglers that should NOT exist.
        time.sleep(0.2)

        calls = stub_scan.calls()
        assert len(calls) == 1, (
            f"expected the 5-write Live burst to coalesce into 1 batch, "
            f"got {len(calls)}: {calls}"
        )
        assert len(calls[0]) == 1, (
            f"expected a single path in the batch, got {calls[0]}"
        )
        assert calls[0][0] == str(als)
    finally:
        w.stop()


def test_debounce_absorbs_rapid_burst_across_two_projects(
    tmp_path, stub_scan, started_queue
):
    """Saving two projects in quick succession (each emitting Live's ~5-write
    burst) should still result in a single IncrementalScan batch with both
    paths, not 10 individual scans.
    """
    from audio_core.indexer.watcher import FsWatcher

    root = tmp_path / "Projects"
    pa = root / "Alpha Project"
    pb = root / "Beta Project"
    pa.mkdir(parents=True)
    pb.mkdir(parents=True)
    a = pa / "alpha.als"
    b = pb / "beta.als"
    a.write_bytes(b"a-init")
    b.write_bytes(b"b-init")

    w = FsWatcher(
        root=root,
        queue=started_queue,
        bus=_silent_bus(),
        db_path=tmp_path / "catalog.db",
        debounce_s=0.4,
        poll_s=0.05,
        drive_check=lambda _: True,
    )
    w.start()
    try:
        # Two interleaved Live-style bursts within the same debounce window.
        for i in range(5):
            a.write_bytes(b"alpha-save-" + str(i).encode())
            b.write_bytes(b"beta-save-" + str(i).encode())
            time.sleep(0.03)

        start = time.monotonic()
        while time.monotonic() - start < 1.5 and not stub_scan.calls():
            time.sleep(0.05)

        time.sleep(0.2)

        calls = stub_scan.calls()
        assert len(calls) == 1, (
            f"expected interleaved double-burst to coalesce into 1 batch, "
            f"got {len(calls)}: {calls}"
        )
        assert calls[0] == sorted([str(a), str(b)]), (
            f"expected both .als paths in the single batch, got {calls[0]}"
        )
    finally:
        w.stop()
