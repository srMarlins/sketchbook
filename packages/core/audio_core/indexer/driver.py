from __future__ import annotations

import asyncio
from pathlib import Path

from audio_core.backfill import needs_backfill
from audio_core.db.connection import open_db
from audio_core.detectors import findings_summary
from audio_core.indexer.events import EventBus
from audio_core.indexer.jobs import BackfillColumn, FullSampleScan, FullScan
from audio_core.indexer.queue import JobQueue

_FINDINGS_TRIGGERS = frozenset({"scan_finished", "backfill_finished"})


def boot(
    *,
    db_path: Path,
    root: Path,
    bus: EventBus,
    queue: JobQueue,
    sample_roots: list[Path] | None = None,
) -> None:
    """Submit any pending column backfills first, then the startup FullScan,
    then a FullSampleScan over the project root plus any extra sample_roots.
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
    all_sample_roots = [root, *(sample_roots or [])]
    queue.submit(FullSampleScan(db_path=db_path, roots=all_sample_roots, bus=bus))


async def start_findings_listener(*, db_path: Path, bus: EventBus) -> None:
    """Subscribe to the bus and republish ``findings_changed`` after every
    ``scan_finished``/``backfill_finished``. Must run on the asyncio loop the
    bus subscribers are bound to. Caller cancels the task to stop it.

    The listener intentionally re-runs the detectors on every finish event —
    cheap on our catalog size and keeps the contract dead simple. If a
    detector raises, the task dies loud; that is the caller's problem.
    """
    queue = bus.subscribe()
    try:
        while True:
            ev = await queue.get()
            if ev.get("kind") not in _FINDINGS_TRIGGERS:
                continue
            conn = open_db(db_path)
            try:
                summary = findings_summary(conn)
            finally:
                conn.close()
            bus.publish({"kind": "findings_changed", **summary})
    finally:
        bus.unsubscribe(queue)
