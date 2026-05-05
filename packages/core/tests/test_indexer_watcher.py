from __future__ import annotations

import threading
import time
from pathlib import Path

import pytest

from audio_core.indexer.events import EventBus
from audio_core.indexer.queue import JobQueue


class _StubScan:
    """Replacement for IncrementalScan inside the watcher module so tests do
    not need real .als parsing — we only verify the watcher's coalescing and
    dispatch logic."""

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


def _wait_until(predicate, timeout: float = 3.0, interval: float = 0.02) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    return predicate()


@pytest.fixture
def stub_scan(monkeypatch):
    _StubScan.reset()
    monkeypatch.setattr(
        "audio_core.indexer.watcher.IncrementalScan", _StubScan
    )
    return _StubScan


@pytest.fixture
def started_queue():
    q = JobQueue()
    q.start()
    yield q
    q.shutdown(wait=True)


def _bus_recorder() -> tuple[EventBus, list[dict]]:
    """Capture published events without needing an asyncio loop."""

    bus = EventBus()
    captured: list[dict] = []
    lock = threading.Lock()

    def _publish(event):
        with lock:
            captured.append(event)

    bus.publish = _publish  # type: ignore[assignment]
    return bus, captured


def test_debounce_coalesces_rapid_writes(tmp_path, stub_scan, started_queue):
    from audio_core.indexer.watcher import FsWatcher

    root = tmp_path / "Projects"
    project = root / "x Project"
    project.mkdir(parents=True)
    als = project / "x.als"
    als.write_bytes(b"placeholder")

    bus, _ = _bus_recorder()

    w = FsWatcher(
        root=root,
        queue=started_queue,
        bus=bus,
        db_path=tmp_path / "catalog.db",
        debounce_s=0.2,
        poll_s=0.05,
        drive_check=lambda _: True,
    )
    w.start()
    try:
        for i in range(5):
            als.write_bytes(b"v" + str(i).encode())
            time.sleep(0.02)
        assert _wait_until(lambda: len(stub_scan.calls()) >= 1, timeout=3.0)
        time.sleep(0.3)
        calls = stub_scan.calls()
        assert len(calls) == 1, f"expected 1 batch, got {calls}"
        assert calls[0] == [str(als)]
    finally:
        w.stop()


def test_multiple_paths_in_one_batch(tmp_path, stub_scan, started_queue):
    from audio_core.indexer.watcher import FsWatcher

    root = tmp_path / "Projects"
    pa = root / "a Project"
    pb = root / "b Project"
    pa.mkdir(parents=True)
    pb.mkdir(parents=True)
    a = pa / "a.als"
    b = pb / "b.als"
    a.write_bytes(b"a")
    b.write_bytes(b"b")

    bus, _ = _bus_recorder()
    w = FsWatcher(
        root=root,
        queue=started_queue,
        bus=bus,
        db_path=tmp_path / "catalog.db",
        debounce_s=0.2,
        poll_s=0.05,
        drive_check=lambda _: True,
    )
    w.start()
    try:
        a.write_bytes(b"a2")
        b.write_bytes(b"b2")
        assert _wait_until(lambda: len(stub_scan.calls()) >= 1, timeout=3.0)
        time.sleep(0.25)
        calls = stub_scan.calls()
        assert len(calls) == 1, f"expected 1 batch, got {calls}"
        assert calls[0] == sorted([str(a), str(b)])
    finally:
        w.stop()


def test_polling_fallback_emits_status(tmp_path, stub_scan, started_queue):
    from audio_core.indexer.watcher import FsWatcher

    root = tmp_path / "Projects"
    root.mkdir(parents=True)

    bus, captured = _bus_recorder()
    w = FsWatcher(
        root=root,
        queue=started_queue,
        bus=bus,
        db_path=tmp_path / "catalog.db",
        debounce_s=0.2,
        poll_s=0.05,
        network_rediscovery_s=60.0,
        drive_check=lambda _: False,
    )
    w.start()
    try:
        kinds_modes = [(e.get("kind"), e.get("mode")) for e in captured]
        assert ("watcher_status", "polling") in kinds_modes
    finally:
        w.stop()
    kinds_modes = [(e.get("kind"), e.get("mode")) for e in captured]
    assert ("watcher_status", "off") in kinds_modes


def test_non_als_files_ignored(tmp_path, stub_scan, started_queue):
    from audio_core.indexer.watcher import FsWatcher

    root = tmp_path / "Projects"
    project = root / "x Project"
    project.mkdir(parents=True)
    txt = project / "notes.txt"
    txt.write_bytes(b"hello")

    bus, _ = _bus_recorder()
    w = FsWatcher(
        root=root,
        queue=started_queue,
        bus=bus,
        db_path=tmp_path / "catalog.db",
        debounce_s=0.15,
        poll_s=0.05,
        drive_check=lambda _: True,
    )
    w.start()
    try:
        for i in range(5):
            txt.write_bytes(b"v" + str(i).encode())
            time.sleep(0.02)
        time.sleep(0.5)
        assert stub_scan.calls() == []
    finally:
        w.stop()


def test_stop_is_idempotent(tmp_path, stub_scan, started_queue):
    from audio_core.indexer.watcher import FsWatcher

    root = tmp_path / "Projects"
    root.mkdir(parents=True)

    bus, _ = _bus_recorder()
    w = FsWatcher(
        root=root,
        queue=started_queue,
        bus=bus,
        db_path=tmp_path / "catalog.db",
        debounce_s=0.1,
        poll_s=0.05,
        drive_check=lambda _: True,
    )
    w.start()
    w.stop()
    w.stop()


def test_local_drive_emits_watching_status(tmp_path, stub_scan, started_queue):
    from audio_core.indexer.watcher import FsWatcher

    root = tmp_path / "Projects"
    root.mkdir(parents=True)

    bus, captured = _bus_recorder()
    w = FsWatcher(
        root=root,
        queue=started_queue,
        bus=bus,
        db_path=tmp_path / "catalog.db",
        debounce_s=0.1,
        poll_s=0.05,
        drive_check=lambda _: True,
    )
    w.start()
    try:
        kinds_modes = [(e.get("kind"), e.get("mode")) for e in captured]
        assert ("watcher_status", "watching") in kinds_modes
    finally:
        w.stop()
    kinds_modes = [(e.get("kind"), e.get("mode")) for e in captured]
    assert ("watcher_status", "off") in kinds_modes


def test_drive_check_returns_bool_for_local_path(tmp_path):
    from audio_core.indexer.watcher import _drive_supports_events

    result = _drive_supports_events(tmp_path)
    assert isinstance(result, bool)
