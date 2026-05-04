from __future__ import annotations

import sqlite3
from pathlib import Path

from audio_core.db.projects import upsert_project
from audio_core.parser import parse_als
from audio_core.scanner.hashing import hash_file


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
