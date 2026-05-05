from __future__ import annotations

import os
import sqlite3
import sys
from pathlib import Path
from typing import Annotated, Literal

from audio_core.config import db_path, projects_root
from audio_core.db.connection import open_db
from audio_core.db.projects import search_projects
from audio_core.safety.paths import ensure_within
from fastapi import APIRouter, HTTPException, Query

router = APIRouter(prefix="/api/projects", tags=["projects"])


@router.get("")
def list_projects(
    query: Annotated[str | None, Query()] = None,
    tempo_min: Annotated[float | None, Query()] = None,
    tempo_max: Annotated[float | None, Query()] = None,
    archived: Annotated[bool | None, Query()] = False,
    min_effort: Annotated[int | None, Query(ge=0, le=100)] = None,
    max_effort: Annotated[int | None, Query(ge=0, le=100)] = None,
    broken: Annotated[bool | None, Query()] = None,
    order_by: Annotated[Literal["mtime", "name", "effort"], Query()] = "mtime",
    order_dir: Annotated[Literal["asc", "desc"], Query()] = "desc",
    limit: Annotated[int, Query(ge=1, le=1000)] = 200,
) -> list[dict]:
    conn = open_db(db_path())
    return search_projects(
        conn,
        query=query,
        tempo_min=tempo_min,
        tempo_max=tempo_max,
        archived=archived,
        min_effort=min_effort,
        max_effort=max_effort,
        broken=broken,
        order_by=order_by,
        order_dir=order_dir,
        limit=limit,
    )


@router.get("/{project_id}")
def project_detail(project_id: int) -> dict:
    conn = open_db(db_path())
    conn.row_factory = sqlite3.Row
    row = conn.execute("SELECT * FROM projects WHERE id=?", (project_id,)).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail=f"no project id={project_id}")
    proj = dict(row)
    # Derived count, mirrors what search_projects() exposes on listings.
    proj["missing_sample_count"] = conn.execute(
        "SELECT COUNT(*) FROM project_samples WHERE project_id=? AND is_missing=1",
        (project_id,),
    ).fetchone()[0]
    proj["plugins"] = [
        dict(r)
        for r in conn.execute(
            "SELECT plugin_name, plugin_type, track_name FROM project_plugins "
            "WHERE project_id=? ORDER BY plugin_type, plugin_name",
            (project_id,),
        )
    ]
    proj["samples"] = [
        dict(r)
        for r in conn.execute(
            "SELECT sample_path, sample_hash, is_missing FROM project_samples "
            "WHERE project_id=? ORDER BY sample_path",
            (project_id,),
        )
    ]
    proj["tags"] = [
        r[0]
        for r in conn.execute(
            "SELECT t.name FROM tags t JOIN project_tags pt ON pt.tag_id=t.id "
            "WHERE pt.project_id=? ORDER BY t.name",
            (project_id,),
        )
    ]
    return proj


@router.post("/{project_id}/open")
def open_project(project_id: int) -> dict:
    """Launch the .als in its registered handler (Ableton on Windows)."""
    conn = open_db(db_path())
    row = conn.execute("SELECT path FROM projects WHERE id=?", (project_id,)).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail=f"no project id={project_id}")
    target = Path(row[0])
    try:
        ensure_within(target, projects_root())
    except PermissionError as e:
        raise HTTPException(status_code=403, detail=str(e)) from e
    if not target.exists():
        raise HTTPException(status_code=404, detail=f"file missing: {target}")
    if sys.platform == "win32":
        os.startfile(str(target))  # type: ignore[attr-defined]
    elif sys.platform == "darwin":
        import subprocess

        subprocess.Popen(["open", str(target)])
    else:
        import subprocess

        subprocess.Popen(["xdg-open", str(target)])
    return {"opened": str(target)}
