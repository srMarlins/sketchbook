from __future__ import annotations

import json
import sqlite3
import time
from pathlib import Path
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
    mac_paths_count: int | None = None,
    has_project_info: int | None = None,
) -> int:
    now = time.time()
    score, breakdown = compute_effort(meta, file_size_bytes=file_size_bytes)
    breakdown_json = json.dumps(breakdown, sort_keys=True)
    cur = conn.execute(
        """
        INSERT INTO projects (path, name, parent_dir, tempo, time_sig_num, time_sig_den,
            track_count, audio_tracks, midi_tracks, return_tracks, length_seconds, live_version,
            last_modified, last_scanned, file_hash, effort_score, effort_breakdown,
            parse_status, parse_error, mac_paths_count, has_project_info, file_size_bytes)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ok', NULL, ?, ?, ?)
        ON CONFLICT(path) DO UPDATE SET
            name=excluded.name, parent_dir=excluded.parent_dir,
            tempo=excluded.tempo, time_sig_num=excluded.time_sig_num,
            time_sig_den=excluded.time_sig_den, track_count=excluded.track_count,
            audio_tracks=excluded.audio_tracks, midi_tracks=excluded.midi_tracks,
            return_tracks=excluded.return_tracks, length_seconds=excluded.length_seconds,
            live_version=excluded.live_version, last_modified=excluded.last_modified,
            last_scanned=excluded.last_scanned, file_hash=excluded.file_hash,
            effort_score=excluded.effort_score, effort_breakdown=excluded.effort_breakdown,
            parse_status='ok', parse_error=NULL,
            mac_paths_count=excluded.mac_paths_count,
            has_project_info=excluded.has_project_info,
            file_size_bytes=excluded.file_size_bytes
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
            mac_paths_count,
            has_project_info,
            file_size_bytes,
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
        "INSERT INTO project_samples (project_id, sample_path, is_missing) VALUES (?, ?, ?)",
        [
            (pid, s.path, 0 if _sample_exists(s.path, parent_dir) else 1)
            for s in meta.samples
        ],
    )
    _refresh_fts(conn, pid)
    conn.commit()
    return pid


def _sample_exists(sample_path: str, parent_dir: str) -> bool:
    """Test whether a parsed sample path actually exists on disk.

    Sample paths inside .als files come in two flavors: absolute (Live's
    'Collected and Saved' or external samples) or relative to the project
    folder (older Live versions, or samples in the project's own Samples
    subdir). For relative paths, resolve against the .als parent directory
    before checking.
    """
    try:
        p = Path(sample_path)
        if not p.is_absolute():
            p = Path(parent_dir) / p
        return p.exists()
    except (OSError, ValueError):
        # Malformed path (e.g. invalid Windows characters) → treat as missing.
        return False


def upsert_failed_parse(
    conn: sqlite3.Connection,
    *,
    path: str,
    name: str,
    parent_dir: str,
    file_hash: str | None,
    last_modified: float,
    error: str,
) -> int:
    """Persist a stub row for a .als that could not be parsed.

    The catalog should still know the project exists (so the user can see it
    flagged broken in the UI) — it just has no metadata. All extracted columns
    are NULL/0; parse_status='failed' and parse_error captures the exception.

    If the same path later parses successfully, upsert_project will overwrite
    parse_status back to 'ok' and clear parse_error.
    """
    now = time.time()
    cur = conn.execute(
        """
        INSERT INTO projects (path, name, parent_dir, tempo, time_sig_num, time_sig_den,
            track_count, audio_tracks, midi_tracks, return_tracks, length_seconds, live_version,
            last_modified, last_scanned, file_hash, effort_score, effort_breakdown,
            parse_status, parse_error)
        VALUES (?, ?, ?, NULL, NULL, NULL, 0, 0, 0, 0, NULL, NULL, ?, ?, ?, NULL, NULL,
                'failed', ?)
        ON CONFLICT(path) DO UPDATE SET
            name=excluded.name, parent_dir=excluded.parent_dir,
            last_modified=excluded.last_modified, last_scanned=excluded.last_scanned,
            file_hash=excluded.file_hash,
            parse_status='failed', parse_error=excluded.parse_error
        RETURNING id
        """,
        (path, name, parent_dir, last_modified, now, file_hash, error),
    )
    pid = cur.fetchone()[0]
    # Failed parses contribute no plugins/samples; clear any prior rows so a
    # project that previously parsed and now fails doesn't keep stale data.
    conn.execute("DELETE FROM project_plugins WHERE project_id=?", (pid,))
    conn.execute("DELETE FROM project_samples WHERE project_id=?", (pid,))
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
    broken: bool | None = None,
    needs_attention: bool | None = None,
    order_by: Literal["mtime", "name", "effort"] = "mtime",
    order_dir: Literal["asc", "desc"] = "desc",
    limit: int = 200,
) -> list[dict]:
    conn.row_factory = sqlite3.Row
    where: list[str] = []
    params: list = []
    # missing_sample_count is computed inline so callers don't need a second
    # query to render "broken" indicators in the UI.
    select_cols = (
        "p.*, ("
        "SELECT COUNT(*) FROM project_samples ps "
        "WHERE ps.project_id = p.id AND ps.is_missing = 1"
        ") AS missing_sample_count"
    )
    if query and query.strip():
        base = f"SELECT {select_cols} FROM projects p JOIN projects_fts f ON f.rowid = p.id"
        where.append("projects_fts MATCH ?")
        params.append(_safe_fts_query(query))
    else:
        base = f"SELECT {select_cols} FROM projects p"
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
    if broken is not None:
        # "Broken" = parse failed OR at least one missing sample. Subquery is
        # repeated rather than joined to the SELECT alias because SQLite can't
        # reference a SELECT-list alias inside WHERE.
        broken_predicate = (
            "(p.parse_status = 'failed' OR EXISTS ("
            "SELECT 1 FROM project_samples ps "
            "WHERE ps.project_id = p.id AND ps.is_missing = 1))"
        )
        if broken:
            where.append(broken_predicate)
        else:
            where.append(f"NOT {broken_predicate}")
    if needs_attention is not None:
        # "Needs attention" = has unresolved Mac paths, missing Project Info
        # sidecar, or the .als file has disappeared. COALESCE keeps NULL
        # legacy columns from leaking into the OR (NULL > 0 is NULL, which is
        # falsy in WHERE — we want explicit zero defaults instead).
        needs_attention_predicate = (
            "(COALESCE(p.mac_paths_count, 0) > 0 "
            "OR COALESCE(p.has_project_info, 1) = 0 "
            "OR COALESCE(p.is_missing, 0) = 1)"
        )
        if needs_attention:
            where.append(needs_attention_predicate)
        else:
            where.append(f"NOT {needs_attention_predicate}")
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
