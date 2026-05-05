from __future__ import annotations

import sqlite3
from pathlib import Path

SCHEMA_PATH = Path(__file__).parent / "schema.sql"


def open_db(path: str | Path) -> sqlite3.Connection:
    """Open a SQLite connection.

    Schema bootstrap + migrations are skipped on connections to a database
    that already has the `projects` table. Cold-start (fresh DB file) pays
    the bootstrap cost once; every subsequent connection just sets PRAGMAs
    and returns. This was previously paid on every FastAPI request (~10-30ms
    of executescript + migration checks for nothing).
    """
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(p)
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")
    conn.execute("PRAGMA busy_timeout = 5000")
    if not _is_initialized(conn):
        conn.executescript(SCHEMA_PATH.read_text(encoding="utf-8"))
        _apply_migrations(conn)
        conn.commit()
    else:
        # Existing DB — still need to run idempotent migrations cheaply, in case
        # the schema added columns since the file was first created.
        _apply_migrations(conn)
        conn.commit()
    return conn


def _is_initialized(conn: sqlite3.Connection) -> bool:
    row = conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='projects'"
    ).fetchone()
    return row is not None


def _apply_migrations(conn: sqlite3.Connection) -> None:
    """Idempotent ALTER TABLE migrations for older DBs that pre-date columns
    added to schema.sql. SQLite has no ADD COLUMN IF NOT EXISTS, so we check
    PRAGMA table_info first.
    """
    cols = {r[1] for r in conn.execute("PRAGMA table_info(projects)")}
    if not cols:
        return  # projects table doesn't exist yet (only true mid-init)
    if "effort_score" not in cols:
        conn.execute("ALTER TABLE projects ADD COLUMN effort_score INTEGER")
    if "effort_breakdown" not in cols:
        conn.execute("ALTER TABLE projects ADD COLUMN effort_breakdown TEXT")
    if "parse_status" not in cols:
        conn.execute("ALTER TABLE projects ADD COLUMN parse_status TEXT")
    if "parse_error" not in cols:
        conn.execute("ALTER TABLE projects ADD COLUMN parse_error TEXT")
    if "mac_paths_count" not in cols:
        conn.execute("ALTER TABLE projects ADD COLUMN mac_paths_count INTEGER")
    if "has_project_info" not in cols:
        conn.execute("ALTER TABLE projects ADD COLUMN has_project_info INTEGER")
    if "file_size_bytes" not in cols:
        conn.execute("ALTER TABLE projects ADD COLUMN file_size_bytes INTEGER")
    if "is_missing" not in cols:
        conn.execute("ALTER TABLE projects ADD COLUMN is_missing INTEGER NOT NULL DEFAULT 0")
    if "last_seen" not in cols:
        conn.execute("ALTER TABLE projects ADD COLUMN last_seen REAL")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_projects_effort_score ON projects(effort_score)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_projects_color_tag ON projects(color_tag)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_projects_parse_status ON projects(parse_status)")
    conn.execute(
        "CREATE TABLE IF NOT EXISTS indexer_state ("
        "id INTEGER PRIMARY KEY CHECK (id = 1), "
        "job_kind TEXT, job_path TEXT, total INTEGER, done INTEGER, started_at REAL, pid INTEGER)"
    )
    conn.executescript(
        "CREATE TABLE IF NOT EXISTS samples ("
        "id INTEGER PRIMARY KEY,"
        "path TEXT NOT NULL UNIQUE,"
        "filename TEXT NOT NULL,"
        "size_bytes INTEGER NOT NULL,"
        "mtime REAL NOT NULL,"
        "parent_dir TEXT NOT NULL"
        ");"
        "CREATE INDEX IF NOT EXISTS idx_samples_filename_size ON samples(filename, size_bytes);"
        "CREATE INDEX IF NOT EXISTS idx_samples_parent ON samples(parent_dir);"
    )
    ps_cols = {r[1] for r in conn.execute("PRAGMA table_info(project_samples)").fetchall()}
    if ps_cols and "size_bytes" not in ps_cols:
        conn.execute("ALTER TABLE project_samples ADD COLUMN size_bytes INTEGER")
