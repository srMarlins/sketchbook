from __future__ import annotations

import sqlite3
from dataclasses import dataclass


@dataclass(frozen=True)
class DupGroup:
    file_hash: str
    keeper: dict
    losers: list[dict]


def find_duplicates(conn: sqlite3.Connection) -> list[DupGroup]:
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
        """
        SELECT * FROM projects
        WHERE file_hash IS NOT NULL
          AND file_hash IN (
            SELECT file_hash FROM projects
            WHERE file_hash IS NOT NULL
            GROUP BY file_hash HAVING COUNT(*) > 1
          )
        """
    ).fetchall()
    if not rows:
        return []
    by_hash: dict[str, list[dict]] = {}
    for r in rows:
        by_hash.setdefault(r["file_hash"], []).append(dict(r))
    groups: list[DupGroup] = []
    for h, members in by_hash.items():
        members.sort(key=_keeper_key)
        groups.append(DupGroup(file_hash=h, keeper=members[0], losers=members[1:]))
    return groups


def _keeper_key(row: dict) -> tuple:
    # Sort ascending; first row wins. Live before archived; newest mtime wins;
    # then shortest path; then lexicographic path for full determinism.
    return (
        1 if row.get("is_archived") else 0,
        -(row.get("last_modified") or 0.0),
        len(row["path"]),
        row["path"],
    )


def build_archive_proposal(groups: list[DupGroup]) -> list[dict]:
    """Convert dup groups into ArchiveProject proposal actions for each loser.
    Groups whose keeper is already archived (i.e. all members archived) contribute
    zero actions — there's nothing to clean up."""
    out: list[dict] = []
    for g in groups:
        if g.keeper.get("is_archived"):
            continue
        for loser in g.losers:
            if loser.get("is_archived"):
                continue
            out.append({"type": "ArchiveProject", "args": {"project_id": loser["id"]}})
    return out
