from __future__ import annotations

import sqlite3
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path

from audio_core.parser.als import parse_als


@dataclass(frozen=True)
class BackfillSpec:
    """A NULL-filler for a column (or set of columns) added to `projects` after
    rows already existed. `null_check_sql` selects rows that still need work
    and MUST select `id` first (other columns are passed as a row dict to
    `fill_one`). `fill_one(conn, row_dict)` does the work and the UPDATE."""

    name: str
    null_check_sql: str
    fill_one: Callable[[sqlite3.Connection, Mapping[str, object]], None]


def _fill_macpath(conn: sqlite3.Connection, row: Mapping[str, object]) -> None:
    md = parse_als(row["path"])  # type: ignore[arg-type]
    parent_dir = row["parent_dir"]  # type: ignore[index]
    pi = 1 if (Path(str(parent_dir)) / "Ableton Project Info").is_dir() else 0
    conn.execute(
        "UPDATE projects SET mac_paths_count=?, has_project_info=? WHERE id=?",
        (md.mac_paths_count, pi, row["id"]),
    )


BACKFILL_SPECS: list[BackfillSpec] = [
    BackfillSpec(
        name="macpath",
        null_check_sql=(
            "SELECT id, path, parent_dir FROM projects "
            "WHERE mac_paths_count IS NULL OR has_project_info IS NULL"
        ),
        fill_one=_fill_macpath,
    ),
]


def needs_backfill(conn: sqlite3.Connection) -> list[str]:
    """Return the names of specs whose null_check_sql currently matches >0 rows."""
    out: list[str] = []
    for spec in BACKFILL_SPECS:
        n = conn.execute(f"SELECT COUNT(*) FROM ({spec.null_check_sql})").fetchone()[0]
        if n > 0:
            out.append(spec.name)
    return out
