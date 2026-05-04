from __future__ import annotations

import sqlite3
from dataclasses import dataclass


@dataclass
class SetColorTag:
    """Set a project's color tag (Ableton palette index 0..13, or None to clear).

    DB-only mutation. Reversed by recording the prior color in the journal entry.
    """

    project_id: int
    color: int | None

    def validate(self, conn: sqlite3.Connection) -> None:
        if self.color is not None and not (0 <= self.color <= 13):
            raise ValueError(f"color must be 0..13 or None, got {self.color}")
        if conn.execute("SELECT 1 FROM projects WHERE id=?", (self.project_id,)).fetchone() is None:
            raise LookupError(f"no project id={self.project_id}")

    def execute(self, conn: sqlite3.Connection) -> dict:
        prev = conn.execute(
            "SELECT color_tag FROM projects WHERE id=?", (self.project_id,)
        ).fetchone()[0]
        conn.execute("UPDATE projects SET color_tag=? WHERE id=?", (self.color, self.project_id))
        conn.commit()
        return {
            "type": "SetColorTag",
            "project_id": self.project_id,
            "before": prev,
            "after": self.color,
        }
