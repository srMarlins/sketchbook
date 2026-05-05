from __future__ import annotations

import sqlite3
from pathlib import Path

SCHEMA_PATH = Path(__file__).parent / "schema.sql"


def open_db(path: str | Path) -> sqlite3.Connection:
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(p)
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")
    conn.executescript(SCHEMA_PATH.read_text(encoding="utf-8"))
    _apply_migrations(conn)
    conn.commit()
    return conn


def _apply_migrations(conn: sqlite3.Connection) -> None:
    """Idempotent ALTER TABLE migrations for older DBs that pre-date columns
    added to schema.sql. SQLite has no ADD COLUMN IF NOT EXISTS, so we check
    PRAGMA table_info first.
    """
    cols = {r[1] for r in conn.execute("PRAGMA table_info(projects)")}
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
    conn.execute("CREATE INDEX IF NOT EXISTS idx_projects_effort_score ON projects(effort_score)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_projects_color_tag ON projects(color_tag)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_projects_parse_status ON projects(parse_status)")
