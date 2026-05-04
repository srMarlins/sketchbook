from __future__ import annotations

import sqlite3
from dataclasses import dataclass, field


def _current_tags(conn: sqlite3.Connection, project_id: int) -> list[str]:
    conn.row_factory = sqlite3.Row
    return [
        r["name"]
        for r in conn.execute(
            "SELECT t.name FROM tags t "
            "JOIN project_tags pt ON pt.tag_id=t.id "
            "WHERE pt.project_id=? "
            "ORDER BY t.name",
            (project_id,),
        )
    ]


def _set_tags(conn: sqlite3.Connection, project_id: int, tags: list[str]) -> None:
    conn.execute("DELETE FROM project_tags WHERE project_id=?", (project_id,))
    for name in tags:
        conn.execute("INSERT OR IGNORE INTO tags (name) VALUES (?)", (name,))
        tag_id = conn.execute("SELECT id FROM tags WHERE name=?", (name,)).fetchone()[0]
        conn.execute(
            "INSERT OR IGNORE INTO project_tags (project_id, tag_id) VALUES (?, ?)",
            (project_id, tag_id),
        )


@dataclass
class SetTags:
    """Replace a project's tag set. DB-only; reverses to the prior tag set."""

    project_id: int
    tags: list[str] = field(default_factory=list)

    def validate(self, conn: sqlite3.Connection) -> None:
        if conn.execute("SELECT 1 FROM projects WHERE id=?", (self.project_id,)).fetchone() is None:
            raise LookupError(f"no project id={self.project_id}")
        for t in self.tags:
            if not t or not isinstance(t, str):
                raise ValueError(f"tag must be a non-empty string, got {t!r}")

    def execute(self, conn: sqlite3.Connection) -> dict:
        before = _current_tags(conn, self.project_id)
        _set_tags(conn, self.project_id, self.tags)
        conn.commit()
        return {
            "type": "SetTags",
            "project_id": self.project_id,
            "before": before,
            "after": list(self.tags),
        }
