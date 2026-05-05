from __future__ import annotations

import sys
import threading
import time
from pathlib import Path

from watchdog.events import FileSystemEvent, FileSystemEventHandler
from watchdog.observers import Observer

from audio_core.indexer.events import EventBus
from audio_core.indexer.jobs import IncrementalSampleScan, IncrementalScan
from audio_core.indexer.queue import JobQueue
from audio_core.scanner.walker import SAMPLE_EXTENSIONS

_SAMPLE_EXT_LOWER = tuple(SAMPLE_EXTENSIONS)

DEBOUNCE_S = 2.0
POLL_INTERVAL_S = 0.1
NETWORK_REDISCOVERY_S = 300.0


def _drive_supports_events(root: Path) -> bool:
    """True for local drives, False for SMB/AFP/network shares. Detection
    failure assumes local — watchdog will simply do nothing useful on a
    network share, but the indexer still works via full scans."""
    if sys.platform == "win32":
        import ctypes

        DRIVE_REMOTE = 4
        try:
            kernel32 = ctypes.windll.kernel32
            anchor = root.resolve()
            drive = str(anchor.drive) + "\\"
            t = kernel32.GetDriveTypeW(ctypes.c_wchar_p(drive))
            return t != DRIVE_REMOTE
        except Exception:
            return True
    if sys.platform == "darwin":
        try:
            import subprocess

            out = subprocess.check_output(["mount"], text=True)
            anchor = str(root.resolve())
            for line in out.splitlines():
                if " on " not in line:
                    continue
                mount_point = line.split(" on ", 1)[1].split(" ", 1)[0]
                if anchor.startswith(mount_point):
                    return not any(
                        t in line for t in (" smbfs", " afpfs", " nfs", " webdav")
                    )
            return True
        except Exception:
            return True
    return True


class _Handler(FileSystemEventHandler):
    def __init__(self, owner: "FsWatcher") -> None:
        self._owner = owner

    def _maybe(self, path: str) -> None:
        low = path.lower()
        if low.endswith(".als"):
            self._owner._touch(path)
        elif low.endswith(_SAMPLE_EXT_LOWER):
            self._owner._touch_sample(path)

    def on_modified(self, event: FileSystemEvent) -> None:
        if not event.is_directory:
            self._maybe(event.src_path)

    def on_created(self, event: FileSystemEvent) -> None:
        if not event.is_directory:
            self._maybe(event.src_path)

    def on_deleted(self, event: FileSystemEvent) -> None:
        if not event.is_directory:
            self._maybe(event.src_path)

    def on_moved(self, event: FileSystemEvent) -> None:
        if not event.is_directory:
            self._maybe(event.src_path)
            dest = getattr(event, "dest_path", None)
            if dest:
                self._maybe(dest)


class FsWatcher:
    """watchdog wrapper. Coalesces events per-path with a 2s debounce, then
    enqueues an IncrementalScan job. Detects non-local drives and falls back
    to polling discovery."""

    def __init__(
        self,
        *,
        root: Path,
        queue: JobQueue,
        bus: EventBus,
        db_path: Path,
        debounce_s: float = DEBOUNCE_S,
        poll_s: float = POLL_INTERVAL_S,
        network_rediscovery_s: float = NETWORK_REDISCOVERY_S,
        drive_check=_drive_supports_events,
        sample_roots: list[Path] | None = None,
    ) -> None:
        self._root = root
        self._queue = queue
        self._bus = bus
        self._db_path = db_path
        self._debounce_s = debounce_s
        self._poll_s = poll_s
        self._network_rediscovery_s = network_rediscovery_s
        self._drive_check = drive_check
        self._sample_roots = list(sample_roots or [])
        self._observer: Observer | None = None
        self._pending: dict[str, float] = {}
        self._pending_samples: dict[str, float] = {}
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._flusher: threading.Thread | None = None
        self._poller: threading.Thread | None = None
        self._started = False

    def start(self) -> None:
        if self._started:
            return
        self._started = True
        self._stop.clear()
        if self._drive_check(self._root):
            self._observer = Observer()
            self._observer.schedule(_Handler(self), str(self._root), recursive=True)
            for extra in self._sample_roots:
                # don't double-schedule a path already covered by self._root
                try:
                    Path(extra).resolve().relative_to(self._root.resolve())
                    continue
                except ValueError:
                    pass
                self._observer.schedule(_Handler(self), str(extra), recursive=True)
            self._observer.start()
            self._flusher = threading.Thread(
                target=self._flush_loop, daemon=True, name="fs-watcher-flusher"
            )
            self._flusher.start()
            self._bus.publish({"kind": "watcher_status", "mode": "watching"})
        else:
            self._bus.publish(
                {"kind": "watcher_status", "mode": "polling", "reason": "non-local drive"}
            )
            self._poller = threading.Thread(
                target=self._poll_loop, daemon=True, name="fs-watcher-poller"
            )
            self._poller.start()

    def stop(self) -> None:
        if not self._started:
            return
        self._started = False
        self._stop.set()
        if self._observer is not None:
            try:
                self._observer.stop()
                self._observer.join(timeout=5.0)
            except Exception:
                pass
            self._observer = None
        if self._flusher is not None:
            self._flusher.join(timeout=5.0)
            self._flusher = None
        if self._poller is not None:
            self._poller.join(timeout=5.0)
            self._poller = None
        self._bus.publish({"kind": "watcher_status", "mode": "off"})

    def _touch(self, path: str) -> None:
        with self._lock:
            self._pending[path] = time.monotonic() + self._debounce_s

    def _touch_sample(self, path: str) -> None:
        with self._lock:
            self._pending_samples[path] = time.monotonic() + self._debounce_s

    def _flush_loop(self) -> None:
        while not self._stop.wait(self._poll_s):
            now = time.monotonic()
            ready: list[str] = []
            ready_samples: list[str] = []
            with self._lock:
                for path, deadline in list(self._pending.items()):
                    if deadline <= now:
                        ready.append(path)
                        del self._pending[path]
                for path, deadline in list(self._pending_samples.items()):
                    if deadline <= now:
                        ready_samples.append(path)
                        del self._pending_samples[path]
            if ready:
                self._queue.submit(
                    IncrementalScan(
                        db_path=self._db_path,
                        paths=[Path(p) for p in ready],
                        bus=self._bus,
                    )
                )
            if ready_samples:
                self._queue.submit(
                    IncrementalSampleScan(
                        db_path=self._db_path,
                        paths=[Path(p) for p in ready_samples],
                        bus=self._bus,
                    )
                )

    def _poll_loop(self) -> None:
        from audio_core.indexer.discovery import discover

        baseline: dict[str, float] = {d.path: d.mtime for d in discover(self._root)}
        while not self._stop.wait(self._network_rediscovery_s):
            current = {d.path: d.mtime for d in discover(self._root)}
            changed = [p for p, m in current.items() if baseline.get(p) != m]
            removed = [p for p in baseline if p not in current]
            paths = [*changed, *removed]
            if paths:
                self._queue.submit(
                    IncrementalScan(
                        db_path=self._db_path,
                        paths=[Path(p) for p in paths],
                        bus=self._bus,
                    )
                )
            baseline = current
