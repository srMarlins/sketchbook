from __future__ import annotations

import sqlite3
from dataclasses import dataclass


@dataclass(frozen=True)
class MacImportFinding:
    project_id: int
    path: str
    name: str
    parent_dir: str
    mac_paths_count: int
    project_info_missing: bool


def find_mac_imports(conn: sqlite3.Connection) -> list[MacImportFinding]:
    """Projects that look Mac-saved-on-Windows: at least one Mac-prefix Path in
    the .als, OR no `Ableton Project Info/` folder. Archived rows excluded."""
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
        """
        SELECT id, path, name, parent_dir,
               COALESCE(mac_paths_count, 0)  AS mp,
               COALESCE(has_project_info, 0) AS pi
        FROM projects
        WHERE COALESCE(is_archived, 0) = 0
          AND (COALESCE(mac_paths_count, 0) > 0
               OR COALESCE(has_project_info, 0) = 0)
        ORDER BY mp DESC, id ASC
        """
    ).fetchall()
    return [
        MacImportFinding(
            project_id=r["id"],
            path=r["path"],
            name=r["name"],
            parent_dir=r["parent_dir"],
            mac_paths_count=int(r["mp"]),
            project_info_missing=(int(r["pi"]) == 0),
        )
        for r in rows
    ]
