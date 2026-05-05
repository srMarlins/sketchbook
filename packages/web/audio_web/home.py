"""Home-page shelf composition.

A "shelf" is a server-side curated row of projects answering one discovery
question (e.g. 'forgotten gems'). Adding a shelf is one entry here; the MCP
and the React frontend stay in sync because the filter logic lives server-side.

See `docs/plans/2026-05-04-home-shelves-design.md`.

## Color-tag mapping

Ableton's palette is a 0-based integer index. The `SetColorTag` action validates
0-13. The canonical mapping from the user's library-conventions doc gives names
to colors but does not pin integer indices. We choose the following best-guess
mapping (matches Ableton's first row of swatches in the default palette):

  red=1, orange=2, yellow=3, green=14 ... but only 0-13 are accepted by the
  current SetColorTag validator. We instead use a contiguous 1..6 mapping that
  fits the validator and is easy to change if Ableton's palette is verified
  later. If the user's actual scan data shows different ints, update this map
  — the SQL filters here are the only place the mapping is consumed.
"""

from __future__ import annotations

import sqlite3
import time
from typing import Any
from urllib.parse import urlencode

from audio_web.schemas import Shelf

# Best-guess Ableton palette indices for the named colors. The validator
# accepts 0-13. If real-world data disagrees, change this map; the SQL below
# uses the values, not the names, so it's the single point of truth.
COLOR_NAMES: dict[str, int] = {
    "red": 1,
    "orange": 2,
    "yellow": 3,
    "green": 4,
    "blue": 5,
    "purple": 6,
}

_DAY = 86400.0

# Per design doc: see_all_query strings give /projects a query string the
# frontend can append. They mirror the SQL where-clause as best as REST can.


def _rows_to_dicts(cursor) -> list[dict[str, Any]]:
    return [dict(r) for r in cursor.fetchall()]


def _shelf_currently_working(conn: sqlite3.Connection, *, now: float) -> Shelf:
    cutoff = now - 14 * _DAY
    blue = COLOR_NAMES["blue"]
    rows = _rows_to_dicts(
        conn.execute(
            """
            SELECT * FROM projects
            WHERE is_archived = 0
              AND (color_tag = ? OR last_modified >= ?)
            ORDER BY last_modified DESC
            LIMIT 12
            """,
            (blue, cutoff),
        )
    )
    return Shelf(
        id="currently-working",
        title="Currently working on",
        description="Blue-tagged or touched in the last 14 days.",
        see_all_query=urlencode({"order_by": "mtime", "order_dir": "desc"}),
        projects=rows,
    )


def _shelf_forgotten_gems(conn: sqlite3.Connection, *, now: float) -> Shelf:
    cutoff = now - 180 * _DAY
    excluded = (COLOR_NAMES["green"], COLOR_NAMES["red"])
    rows = _rows_to_dicts(
        conn.execute(
            """
            SELECT * FROM projects
            WHERE is_archived = 0
              AND effort_score >= 80
              AND last_modified < ?
              AND (color_tag IS NULL OR color_tag NOT IN (?, ?))
            ORDER BY effort_score DESC
            LIMIT 150
            """,
            (cutoff, *excluded),
        )
    )
    return Shelf(
        id="forgotten-gems",
        title="Forgotten gems",
        description="High-effort projects you haven't touched in 6+ months.",
        see_all_query=urlencode(
            {"min_effort": 80, "order_by": "effort", "order_dir": "desc"}
        ),
        projects=rows,
    )


def _shelf_almost_done(conn: sqlite3.Connection) -> Shelf:
    rows = _rows_to_dicts(
        conn.execute(
            """
            SELECT * FROM projects
            WHERE is_archived = 0
              AND color_tag IN (?, ?)
            ORDER BY effort_score DESC NULLS LAST
            LIMIT 15
            """,
            (COLOR_NAMES["orange"], COLOR_NAMES["yellow"]),
        )
    )
    return Shelf(
        id="almost-done",
        title="Almost done",
        description="Orange or yellow — small gaps to close, or just needs mix/master.",
        see_all_query=urlencode({"order_by": "effort", "order_dir": "desc"}),
        projects=rows,
    )


def _shelf_has_potential(conn: sqlite3.Connection) -> Shelf:
    rows = _rows_to_dicts(
        conn.execute(
            """
            SELECT * FROM projects
            WHERE is_archived = 0
              AND color_tag = ?
            ORDER BY last_modified DESC
            LIMIT 15
            """,
            (COLOR_NAMES["purple"],),
        )
    )
    return Shelf(
        id="has-potential",
        title="Has potential",
        description="Purple-tagged — stalled but worth revisiting.",
        see_all_query=urlencode({"order_by": "mtime", "order_dir": "desc"}),
        projects=rows,
    )


def _shelf_recent_activity(conn: sqlite3.Connection) -> Shelf:
    """Most recently touched projects, regardless of color/effort. Powers the
    'recent activity' strip on the home page below the notebook grid. We
    over-fetch (30) so the UI can group by parent_dir and still render 8-12
    distinct projects."""
    rows = _rows_to_dicts(
        conn.execute(
            """
            SELECT * FROM projects
            WHERE is_archived = 0
            ORDER BY last_modified DESC
            LIMIT 30
            """
        )
    )
    return Shelf(
        id="recent-activity",
        title="Recent activity",
        description="Latest edits across the library.",
        see_all_query=urlencode({"order_by": "mtime", "order_dir": "desc"}),
        projects=rows,
    )


def _shelf_gems_sample(conn: sqlite3.Connection, *, now: float) -> Shelf:
    """Random sample of high-effort old projects — same filter as forgotten-gems
    but ORDER BY RANDOM() so each refresh surfaces a different rotation. We
    over-fetch (30) so UI grouping still leaves ~8-12 distinct projects."""
    cutoff = now - 180 * _DAY
    excluded = (COLOR_NAMES["green"], COLOR_NAMES["red"])
    rows = _rows_to_dicts(
        conn.execute(
            """
            SELECT * FROM projects
            WHERE is_archived = 0
              AND effort_score >= 80
              AND last_modified < ?
              AND (color_tag IS NULL OR color_tag NOT IN (?, ?))
            ORDER BY RANDOM()
            LIMIT 30
            """,
            (cutoff, *excluded),
        )
    )
    return Shelf(
        id="gems-sample",
        title="Forgotten gems — random pick",
        description="A fresh rotation each refresh.",
        see_all_query=urlencode(
            {"min_effort": 80, "order_by": "effort", "order_dir": "desc"}
        ),
        projects=rows,
    )


def _shelf_untriaged(conn: sqlite3.Connection) -> Shelf:
    rows = _rows_to_dicts(
        conn.execute(
            """
            SELECT * FROM projects
            WHERE is_archived = 0
              AND color_tag IS NULL
            ORDER BY effort_score DESC NULLS LAST
            LIMIT 12
            """
        )
    )
    return Shelf(
        id="untriaged",
        title="Untriaged",
        description="No color yet — triage these before they're forgotten.",
        see_all_query=urlencode({"order_by": "effort", "order_dir": "desc"}),
        projects=rows,
    )


def compute_shelves(conn: sqlite3.Connection) -> list[Shelf]:
    """Build all home-page shelves in a single pass.

    Order matters — most relevant first. A zero-result shelf still appears
    (with an empty `projects` list) so the home layout stays predictable.
    """
    conn.row_factory = sqlite3.Row
    now = time.time()
    return [
        _shelf_currently_working(conn, now=now),
        _shelf_forgotten_gems(conn, now=now),
        _shelf_almost_done(conn),
        _shelf_has_potential(conn),
        _shelf_untriaged(conn),
        _shelf_recent_activity(conn),
        _shelf_gems_sample(conn, now=now),
    ]
