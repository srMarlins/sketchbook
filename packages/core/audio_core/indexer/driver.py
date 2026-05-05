from __future__ import annotations

from pathlib import Path

from audio_core.indexer.events import EventBus
from audio_core.indexer.jobs import FullScan
from audio_core.indexer.queue import JobQueue


def boot(*, db_path: Path, root: Path, bus: EventBus, queue: JobQueue) -> None:
    """Submit the startup FullScan onto the queue. Caller is responsible for
    starting the queue first and shutting it down on app exit. Future tasks
    (B4, D3) extend this to also enqueue backfills and start the watcher."""
    queue.submit(FullScan(db_path=db_path, root=root, bus=bus))
