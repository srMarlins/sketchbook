from __future__ import annotations

import logging
import queue as _queue
import threading
from collections.abc import Callable
from typing import Any

log = logging.getLogger(__name__)


class JobQueue:
    """Single-flight FIFO queue. Jobs are zero-arg callables run on one worker
    thread, so SQLite writes serialize naturally and progress reporting is
    honest. Exceptions are logged; the worker keeps going.
    """

    _SENTINEL: Any = object()

    def __init__(self) -> None:
        self._q: _queue.Queue = _queue.Queue()
        self._thread: threading.Thread | None = None
        self._lock = threading.Lock()

    def start(self) -> None:
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return
            self._thread = threading.Thread(
                target=self._run, name="indexer-queue", daemon=True
            )
            self._thread.start()

    def submit(self, fn: Callable[[], None]) -> None:
        self._q.put(fn)

    def shutdown(self, *, wait: bool = True) -> None:
        with self._lock:
            t = self._thread
            if t is None:
                return
            self._q.put(self._SENTINEL)
            self._thread = None
        if wait:
            t.join()

    def _run(self) -> None:
        while True:
            fn = self._q.get()
            if fn is self._SENTINEL:
                return
            try:
                fn()
            except Exception:
                log.exception("indexer job raised")
