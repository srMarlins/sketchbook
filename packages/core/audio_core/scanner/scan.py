from __future__ import annotations

import sqlite3
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

from audio_core.db.projects import upsert_project
from audio_core.parser import parse_als
from audio_core.scanner.hashing import hash_file
from audio_core.scanner.walker import walk_projects


def scan_one(conn: sqlite3.Connection, als_path: str | Path) -> int:
    p = Path(als_path)
    meta = parse_als(p)
    return upsert_project(
        conn,
        path=str(p),
        name=p.stem,
        parent_dir=str(p.parent),
        file_hash=hash_file(p),
        last_modified=p.stat().st_mtime,
        meta=meta,
    )


@dataclass
class ScanStats:
    scanned: int = 0
    skipped: int = 0
    failed: int = 0


def scan_root(
    conn: sqlite3.Connection,
    root: str | Path,
    on_progress: Callable[[Path, str], None] | None = None,
) -> ScanStats:
    stats = ScanStats()
    for als in walk_projects(root):
        try:
            existing = conn.execute(
                "SELECT file_hash FROM projects WHERE path = ?", (str(als),)
            ).fetchone()
            current_hash = hash_file(als)
            if existing and existing[0] == current_hash:
                stats.skipped += 1
                if on_progress:
                    on_progress(als, "skipped")
                continue
            scan_one(conn, als)
            stats.scanned += 1
            if on_progress:
                on_progress(als, "scanned")
        except Exception:
            stats.failed += 1
            if on_progress:
                on_progress(als, "failed")
    return stats
