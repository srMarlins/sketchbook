from __future__ import annotations

import sqlite3
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class SampleRow:
    id: int
    path: str
    filename: str
    size_bytes: int
    mtime: float
    parent_dir: str


def upsert_sample(conn: sqlite3.Connection, path: str | Path) -> None:
    """Index one audio file. Idempotent. Stat failure → skip silently (the
    file vanished mid-walk; next pass picks it up or it stays absent)."""
    p = Path(path).resolve()
    try:
        st = p.stat()
    except OSError:
        return
    conn.execute(
        "INSERT INTO samples (path, filename, size_bytes, mtime, parent_dir) "
        "VALUES (?, ?, ?, ?, ?) "
        "ON CONFLICT(path) DO UPDATE SET "
        "filename=excluded.filename, size_bytes=excluded.size_bytes, "
        "mtime=excluded.mtime, parent_dir=excluded.parent_dir",
        (str(p), p.name, st.st_size, st.st_mtime, str(p.parent)),
    )
    conn.commit()


def delete_sample(conn: sqlite3.Connection, path: str | Path) -> None:
    p = Path(path).resolve()
    conn.execute("DELETE FROM samples WHERE path=?", (str(p),))
    conn.commit()


def find_by_filename(conn: sqlite3.Connection, filename: str) -> list[SampleRow]:
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
        "SELECT id, path, filename, size_bytes, mtime, parent_dir "
        "FROM samples WHERE filename = ? ORDER BY mtime DESC, path ASC",
        (filename,),
    ).fetchall()
    return [SampleRow(**dict(r)) for r in rows]
