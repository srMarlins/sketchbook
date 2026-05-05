from __future__ import annotations

import threading
import time

import pytest

from audio_core.indexer.events import EventBus
from audio_core.indexer.queue import JobQueue


class _StubSampleScan:
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


def _bus_recorder() -> tuple[EventBus, list[dict]]:
    bus = EventBus()
    captured: list[dict] = []
    lock = threading.Lock()

    def _publish(event):
        with lock:
            captured.append(event)

    bus.publish = _publish  # type: ignore[assignment]
    return bus, captured


@pytest.fixture
def stub_sample_scan(monkeypatch):
    _StubSampleScan.reset()
    monkeypatch.setattr(
        "audio_core.indexer.watcher.IncrementalSampleScan", _StubSampleScan
    )
    return _StubSampleScan


@pytest.fixture
def started_queue():
    q = JobQueue()
    q.start()
    yield q
    q.shutdown(wait=True)


def test_audio_file_create_enqueues_incremental_sample(
    tmp_path, stub_sample_scan, started_queue
):
    from audio_core.indexer.watcher import FsWatcher

    root = tmp_path / "Projects"
    root.mkdir(parents=True)
    sample_root = tmp_path / "Library"
    sample_root.mkdir(parents=True)

    bus, _ = _bus_recorder()
    w = FsWatcher(
        root=root,
        queue=started_queue,
        bus=bus,
        db_path=tmp_path / "catalog.db",
        debounce_s=0.15,
        poll_s=0.05,
        drive_check=lambda _: True,
        sample_roots=[sample_root],
    )
    w.start()
    try:
        (sample_root / "k.wav").write_bytes(b"x")
        assert _wait_until(lambda: len(stub_sample_scan.calls()) >= 1, timeout=3.0)
        time.sleep(0.25)
        calls = stub_sample_scan.calls()
        assert len(calls) >= 1
        assert any(str(sample_root / "k.wav") in batch for batch in calls)
    finally:
        w.stop()
