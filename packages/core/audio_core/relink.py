from __future__ import annotations

import sqlite3
from collections import defaultdict
from dataclasses import dataclass, field

from audio_core.samples import find_by_filename


@dataclass(frozen=True)
class SampleCandidate:
    path: str
    filename: str
    size_bytes: int
    mtime: float


@dataclass(frozen=True)
class MissingSampleFinding:
    project_id: int
    project_path: str
    project_name: str
    missing_path: str
    candidates: list[SampleCandidate] = field(default_factory=list)
    auto_match: SampleCandidate | None = None


def _basename(path: str) -> str:
    return path.rsplit("/", 1)[-1].rsplit("\\", 1)[-1]


def find_missing_samples(
    conn: sqlite3.Connection,
    *,
    project_id: int | None = None,
) -> list[MissingSampleFinding]:
    """One finding per (project, missing sample). Auto-match is set when the
    `samples` index has exactly one row whose filename matches the missing
    basename. Archived projects are excluded.

    Pass `project_id` to scope to a single project — the bulk variant on a
    catalog with 100k+ missing samples otherwise returns a payload too large
    for the UI to render.
    """
    conn.row_factory = sqlite3.Row
    sql = (
        "SELECT ps.project_id, ps.sample_path, p.path AS project_path, p.name AS project_name "
        "FROM project_samples ps "
        "JOIN projects p ON p.id = ps.project_id "
        "WHERE ps.is_missing = 1 "
        "  AND COALESCE(p.is_archived, 0) = 0"
    )
    params: list = []
    if project_id is not None:
        sql += " AND ps.project_id = ?"
        params.append(project_id)
    sql += " ORDER BY ps.project_id, ps.sample_path"
    rows = conn.execute(sql, params).fetchall()
    findings: list[MissingSampleFinding] = []
    for r in rows:
        fname = _basename(r["sample_path"])
        sample_rows = find_by_filename(conn, fname)
        candidates = [
            SampleCandidate(
                path=s.path,
                filename=s.filename,
                size_bytes=s.size_bytes,
                mtime=s.mtime,
            )
            for s in sample_rows
        ]
        auto = candidates[0] if len(candidates) == 1 else None
        findings.append(
            MissingSampleFinding(
                project_id=r["project_id"],
                project_path=r["project_path"],
                project_name=r["project_name"],
                missing_path=r["sample_path"],
                candidates=candidates,
                auto_match=auto,
            )
        )
    return findings


def build_relink_proposal(
    findings: list[MissingSampleFinding],
    picks: dict[str, str],
) -> list[dict]:
    """Group findings by project_id. Each project becomes one
    RelinkMissingSamples action with a list of (old, new) relinks. A finding
    is included if it has either an auto_match or an explicit pick keyed by
    its missing_path. Findings without resolution are silently skipped — the
    UI is responsible for not selecting them."""
    by_proj: dict[int, list[tuple[str, str]]] = defaultdict(list)
    for f in findings:
        new_path: str | None = None
        if f.missing_path in picks:
            new_path = picks[f.missing_path]
        elif f.auto_match is not None:
            new_path = f.auto_match.path
        if new_path:
            by_proj[f.project_id].append((f.missing_path, new_path))
    return [
        {
            "type": "RelinkMissingSamples",
            "args": {
                "project_id": pid,
                "relinks": [{"old": old, "new": new} for old, new in items],
            },
        }
        for pid, items in by_proj.items()
    ]
