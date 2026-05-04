"""POST /api/projects/{id}/open — launch the .als in Ableton via Windows shell.

Localhost-only: the server is bound to localhost (existing FastAPI default in
`audio-web`), so process launching is acceptable. The .als path must lie inside
the configured projects root; otherwise we refuse with 403.
"""

from __future__ import annotations

import sqlite3
import subprocess

from audio_core.config import db_path, projects_root
from audio_core.db.connection import open_db
from audio_core.safety.paths import ensure_within
from fastapi import APIRouter, HTTPException

router = APIRouter(prefix="/api/projects", tags=["projects"])


@router.post("/{project_id}/open")
def open_project(project_id: int) -> dict:
    conn = open_db(db_path())
    conn.row_factory = sqlite3.Row
    row = conn.execute(
        "SELECT path FROM projects WHERE id=?", (project_id,)
    ).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail=f"no project id={project_id}")
    path = row["path"]
    try:
        ensure_within(path, projects_root())
    except PermissionError as e:
        raise HTTPException(status_code=403, detail=str(e)) from e
    # Windows: `start "" "<path>"` invokes the registered handler for .als
    # (Ableton Live). Use shell=False with cmd /c to avoid quoting pitfalls.
    subprocess.run(["cmd", "/c", "start", "", path], shell=False, check=False)
    return {"ok": True}
