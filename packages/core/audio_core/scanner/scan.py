from __future__ import annotations

import os
import sqlite3
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path
from typing import Any

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


def _process_one(als: Path) -> tuple[str, Path, Any]:
    """Worker payload: stat + parse + hash. No DB access. Both lxml's iterparse
    on a gzip stream and BLAKE3 release the GIL, so these run truly in parallel
    across threads. Returns one of:

    - ("ok",   als, (stat, file_hash, meta))
    - ("fail", als, (stat_or_None, file_hash_or_None, exception))
    """
    try:
        stat = als.stat()
    except OSError as e:
        return ("fail", als, (None, None, e))
    try:
        meta = parse_als(als)
    except Exception as parse_err:
        try:
            fh = hash_file(als)
        except Exception:
            fh = None
        return ("fail", als, (stat, fh, parse_err))
    try:
        fh = hash_file(als)
    except Exception as hash_err:
        return ("fail", als, (stat, None, hash_err))
    return ("ok", als, (stat, fh, meta))


def _default_workers() -> int:
    # I/O + GIL-releasing CPU work: more threads than cores helps.
    # Cap at 8 — beyond that disk contention dominates on a single spindle.
    return min((os.cpu_count() or 4), 8)


def scan_root(
    conn: sqlite3.Connection,
    root: str | Path,
    on_progress: Callable[[Path, str], None] | None = None,
    *,
    max_workers: int | None = None,
) -> ScanStats:
    """Walk every project under `root` and upsert into the catalog.

    Strategy:
    1. Walk + skip-by-(size, mtime). Files whose (size, mtime) match the stored
       row are skipped without reading bytes — the fast path for warm rescans.
    2. The remaining files are hashed + parsed in a `ThreadPoolExecutor`
       (`max_workers` threads, default min(cpu_count, 8)). lxml.iterparse on a
       gzip stream and BLAKE3 both release the GIL, so wall-clock scales
       roughly linearly with worker count up to disk saturation.
    3. The main thread is the sole DB writer (SQLite serializes writers
       anyway; this avoids per-thread connection juggling).
    """
    stats = ScanStats()

    # Pass 1 — walk + cheap skip
    todo: list[Path] = []
    for als in walk_projects(root):
        als = als.resolve()
        existing = conn.execute(
            "SELECT file_hash, last_modified, file_size_bytes FROM projects WHERE path = ?",
            (str(als),),
        ).fetchone()
        try:
            stat = als.stat()
        except OSError:
            todo.append(als)
            continue
        if (
            existing
            and existing[1] is not None
            and existing[2] is not None
            and existing[2] == stat.st_size
            and abs((existing[1] or 0) - stat.st_mtime) < 1.0
        ):
            stats.skipped += 1
            if on_progress:
                on_progress(als, "skipped")
            continue
        todo.append(als)

    if not todo:
        return stats

    workers = max_workers if max_workers is not None else _default_workers()

    if workers <= 1 or len(todo) == 1:
        for als in todo:
            _commit_result(conn, _process_one(als), stats, on_progress)
        return stats

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(_process_one, als): als for als in todo}
        for fut in as_completed(futures):
            try:
                result = fut.result()
            except Exception as e:
                als = futures[fut]
                result = ("fail", als, (None, None, e))
            _commit_result(conn, result, stats, on_progress)
    return stats


def _commit_result(
    conn: sqlite3.Connection,
    result: tuple[str, Path, Any],
    stats: ScanStats,
    on_progress: Callable[[Path, str], None] | None,
) -> None:
    kind, als, payload = result
    if kind == "ok":
        stat, fh, meta = payload
        existing = conn.execute(
            "SELECT file_hash FROM projects WHERE path = ?", (str(als),)
        ).fetchone()
        if existing and existing[0] == fh:
            conn.execute(
                "UPDATE projects SET last_modified=?, file_size_bytes=? WHERE path=?",
                (stat.st_mtime, stat.st_size, str(als)),
            )
            conn.commit()
            stats.skipped += 1
            if on_progress:
                on_progress(als, "skipped")
            return
        try:
            has_pi = 1 if (als.parent / "Ableton Project Info").is_dir() else 0
            upsert_project(
                conn,
                path=str(als),
                name=als.stem,
                parent_dir=str(als.parent),
                file_hash=fh,
                last_modified=stat.st_mtime,
                meta=meta,
                file_size_bytes=stat.st_size,
                mac_paths_count=meta.mac_paths_count,
                has_project_info=has_pi,
            )
            stats.scanned += 1
            if on_progress:
                on_progress(als, "scanned")
        except Exception as exc:
            _record_failure(conn, als, stat, fh, exc, stats, on_progress)
        return

    # kind == "fail"
    stat, fh, err = payload
    _record_failure(conn, als, stat, fh, err, stats, on_progress)


def _record_failure(
    conn: sqlite3.Connection,
    als: Path,
    stat: Any,
    fh: str | None,
    err: BaseException,
    stats: ScanStats,
    on_progress: Callable[[Path, str], None] | None,
) -> None:
    last_modified = stat.st_mtime if stat is not None else 0.0
    try:
        upsert_failed_parse(
            conn,
            path=str(als),
            name=als.stem,
            parent_dir=str(als.parent),
            file_hash=fh,
            last_modified=last_modified,
            error=f"{type(err).__name__}: {err}"[:1024],
        )
    except Exception:
        # Even the failure stub couldn't insert — DB is wedged. Swallow so
        # one bad row doesn't abort the whole scan.
        pass
    stats.failed += 1
    if on_progress:
        on_progress(als, "failed")
