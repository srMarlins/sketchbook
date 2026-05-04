from __future__ import annotations

import json
import sqlite3
import time
from typing import Literal

from audio_core.parser.model import ProjectMetadata
from audio_core.scoring import compute_effort


def upsert_project(
    conn: sqlite3.Connection,
    *,
    path: str,
    name: str,
    parent_dir: str,
    file_hash: str | None,
    last_modified: float,
    meta: ProjectMetadata,
    file_size_bytes: int = 0,
) -> int:
    now = time.time()
    score, breakdown = compute_effort(meta, file_size_bytes=file_size_bytes)
    breakdown_json = json.dumps(breakdown, sort_keys=True)
    cur = conn.execute(
        """
        INSERT INTO projects (path, name, parent_dir, tempo, time_sig_num, time_sig_den,
            track_count, audio_tracks, midi_tracks, return_tracks, length_seconds, live_version,
            last_modified, last_scanned, file_hash, effort_score, effort_breakdown)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(path) DO UPDATE SET
            name=excluded.name, parent_dir=excluded.parent_dir,
            tempo=excluded.tempo, time_sig_num=excluded.time_sig_num,
            time_sig_den=excluded.time_sig_den, track_count=excluded.track_count,
            audio_tracks=excluded.audio_tracks, midi_tracks=excluded.midi_tracks,
            return_tracks=excluded.return_tracks, length_seconds=excluded.length_seconds,
            live_version=excluded.live_version, last_modified=excluded.last_modified,
            last_scanned=excluded.last_scanned, file_hash=excluded.file_hash,
            effort_score=excluded.effort_score, effort_breakdown=excluded.effort_breakdown
        RETURNING id
        """,
        (
            path,
            name,
            parent_dir,
            meta.tempo,
            meta.time_sig_numerator,
            meta.time_sig_denominator,
            meta.track_count,
            meta.audio_track_count,
            meta.midi_track_count,
            meta.return_track_count,
            meta.length_seconds,
            meta.live_version,
            last_modified,
            now,
            file_hash,
            score,
            breakdown_json,
        ),
    )
    pid = cur.fetchone()[0]
    conn.execute("DELETE FROM project_plugins WHERE project_id=?", (pid,))
    conn.execute("DELETE FROM project_samples WHERE project_id=?", (pid,))
    conn.executemany(
        "INSERT INTO project_plugins (project_id, plugin_name, plugin_type, track_name) "
        "VALUES (?, ?, ?, ?)",
        [(pid, p.name, p.plugin_type, p.track_name) for p in meta.plugins],
    )
    conn.executemany(
        "INSERT INTO project_samples (project_id, sample_path) VALUES (?, ?)",
        [(pid, s.path) for s in meta.samples],
    )
    _refresh_fts(conn, pid)
    conn.commit()
    return pid


def _refresh_fts(conn: sqlite3.Connection, pid: int) -> None:
    row = conn.execute(
        "SELECT name, parent_dir, COALESCE(notes, '') FROM projects WHERE id=?", (pid,)
    ).fetchone()
    if not row:
        return
    plugin_names = " ".join(
        r[0]
        for r in conn.execute("SELECT plugin_name FROM project_plugins WHERE project_id=?", (pid,))
    )
    sample_filenames = " ".join(
        r[0].rsplit("/", 1)[-1].rsplit("\\", 1)[-1]
        for r in conn.execute("SELECT sample_path FROM project_samples WHERE project_id=?", (pid,))
    )
    # External-content FTS: delete-then-insert for the rowid since FTS5 doesn't honor
    # ON CONFLICT for virtual tables.
    conn.execute("DELETE FROM projects_fts WHERE rowid=?", (pid,))
    conn.execute(
        "INSERT INTO projects_fts (rowid, name, parent_dir, plugin_names, sample_filenames, notes) "
        "VALUES (?, ?, ?, ?, ?, ?)",
        (pid, row[0], row[1], plugin_names, sample_filenames, row[2]),
    )


def get_project_by_path(conn: sqlite3.Connection, path: str) -> dict | None:
    conn.row_factory = sqlite3.Row
    row = conn.execute("SELECT * FROM projects WHERE path = ?", (path,)).fetchone()
    return dict(row) if row else None


def _safe_fts_query(query: str) -> str:
    """Wrap each whitespace-separated token in double quotes so FTS5 treats them as
    literal phrases (avoids parsing pitfalls like ``Pro-Q`` being read as a column op).
    Multiple tokens are AND-ed by FTS5 default."""
    tokens = [t for t in query.split() if t]
    return " ".join('"' + t.replace('"', '""') + '"' for t in tokens)


_ORDER_COLS = {
    "mtime": "p.last_modified",
    "name": "p.name",
    "effort": "p.effort_score",
}


def search_projects(
    conn: sqlite3.Connection,
    *,
    query: str | None = None,
    tempo_min: float | None = None,
    tempo_max: float | None = None,
    archived: bool | None = False,
    min_effort: int | None = None,
    max_effort: int | None = None,
    order_by: Literal["mtime", "name", "effort"] = "mtime",
    order_dir: Literal["asc", "desc"] = "desc",
    limit: int = 200,
) -> list[dict]:
    conn.row_factory = sqlite3.Row
    where: list[str] = []
    params: list = []
    if query and query.strip():
        base = "SELECT p.* FROM projects p JOIN projects_fts f ON f.rowid = p.id"
        where.append("projects_fts MATCH ?")
        params.append(_safe_fts_query(query))
    else:
        base = "SELECT p.* FROM projects p"
    if tempo_min is not None:
        where.append("p.tempo >= ?")
        params.append(tempo_min)
    if tempo_max is not None:
        where.append("p.tempo <= ?")
        params.append(tempo_max)
    if archived is not None:
        where.append("p.is_archived = ?")
        params.append(1 if archived else 0)
    if min_effort is not None:
        where.append("p.effort_score >= ?")
        params.append(min_effort)
    if max_effort is not None:
        where.append("p.effort_score <= ?")
        params.append(max_effort)
    sql = base + ((" WHERE " + " AND ".join(where)) if where else "")
    col = _ORDER_COLS.get(order_by, _ORDER_COLS["mtime"])
    direction = "ASC" if order_dir.lower() == "asc" else "DESC"
    sql += f" ORDER BY {col} {direction} LIMIT ?"
    params.append(limit)
    rows = [dict(r) for r in conn.execute(sql, params).fetchall()]
    if not rows:
        return rows
    # Batch-fetch tags for all returned project ids in one query.
    pids = [r["id"] for r in rows]
    placeholders = ",".join("?" for _ in pids)
    tag_rows = conn.execute(
        f"SELECT pt.project_id, t.name FROM project_tags pt "
        f"JOIN tags t ON t.id = pt.tag_id WHERE pt.project_id IN ({placeholders}) "
        f"ORDER BY t.name",
        pids,
    ).fetchall()
    tags_by_pid: dict[int, list[str]] = {pid: [] for pid in pids}
    for tr in tag_rows:
        tags_by_pid[tr["project_id"]].append(tr["name"])
    for r in rows:
        r["tags"] = tags_by_pid.get(r["id"], [])
    return rows
