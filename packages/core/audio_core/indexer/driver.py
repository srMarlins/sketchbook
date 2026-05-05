from __future__ import annotations

from pathlib import Path

from audio_core.backfill import needs_backfill
from audio_core.db.connection import open_db
from audio_core.indexer.events import EventBus
from audio_core.indexer.jobs import BackfillColumn, FullScan
from audio_core.indexer.queue import JobQueue


def boot(*, db_path: Path, root: Path, bus: EventBus, queue: JobQueue) -> None:
    """Submit any pending column backfills first, then the startup FullScan.
    Order matters — the queue is single-flight FIFO, so cheap parse-only work
    on a stale catalog runs before re-hashing. Caller starts/stops the queue."""
    conn = open_db(db_path)
    try:
        pending = needs_backfill(conn)
    finally:
        conn.close()
    for name in pending:
        queue.submit(BackfillColumn(db_path=db_path, spec_name=name, bus=bus))
    queue.submit(FullScan(db_path=db_path, root=root, bus=bus))
