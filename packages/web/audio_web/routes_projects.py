from __future__ import annotations

import sqlite3
from typing import Annotated

from audio_core.config import db_path
from audio_core.db.connection import open_db
from audio_core.db.projects import search_projects
from fastapi import APIRouter, HTTPException, Query

router = APIRouter(prefix="/api/projects", tags=["projects"])


@router.get("")
def list_projects(
    query: Annotated[str | None, Query()] = None,
    tempo_min: Annotated[float | None, Query()] = None,
    tempo_max: Annotated[float | None, Query()] = None,
    archived: Annotated[bool | None, Query()] = False,
    limit: Annotated[int, Query(ge=1, le=1000)] = 200,
) -> list[dict]:
    conn = open_db(db_path())
    return search_projects(
        conn,
        query=query,
        tempo_min=tempo_min,
        tempo_max=tempo_max,
        archived=archived,
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
