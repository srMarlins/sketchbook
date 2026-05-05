from __future__ import annotations

import sqlite3
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

from audio_core.db.projects import upsert_failed_parse, upsert_project
from audio_core.parser import parse_als
from audio_core.scanner.hashing import hash_file
from audio_core.scanner.walker import walk_projects


def scan_one(conn: sqlite3.Connection, als_path: str | Path) -> int:
    p = Path(als_path).resolve()  # canonicalize so relative + absolute paths dedupe
    meta = parse_als(p)
    stat = p.stat()
    has_pi = 1 if (p.parent / "Ableton Project Info").is_dir() else 0
    return upsert_project(
        conn,
        path=str(p),
        name=p.stem,
        parent_dir=str(p.parent),
        file_hash=hash_file(p),
        last_modified=stat.st_mtime,
        meta=meta,
        file_size_bytes=stat.st_size,
        mac_paths_count=meta.mac_paths_count,
        has_project_info=has_pi,
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
            als = als.resolve()  # match scan_one's canonicalization for hash-skip lookup
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
        except Exception as exc:
            # Persist a stub row so a corrupt/un-parseable project still
            # appears in the catalog (flagged broken) instead of vanishing.
            try:
                stat = als.stat()
                last_modified = stat.st_mtime
            except OSError:
                last_modified = 0.0
            try:
                fh = hash_file(als)
            except Exception:
                fh = None
            try:
                upsert_failed_parse(
                    conn,
                    path=str(als),
                    name=als.stem,
                    parent_dir=str(als.parent),
                    file_hash=fh,
                    last_modified=last_modified,
                    error=f"{type(exc).__name__}: {exc}"[:1024],
                )
            except Exception:
                # If even the stub insert fails, swallow — the failure count
                # below still reflects the underlying parse error.
                pass
            stats.failed += 1
            if on_progress:
                on_progress(als, "failed")
    return stats
