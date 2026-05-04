from __future__ import annotations

import json
import sqlite3
import uuid
from datetime import UTC, datetime
from typing import Any

from audio_core.config import db_path, proposals_dir
from audio_core.db.connection import open_db
from audio_core.db.projects import search_projects as _search_projects
from fastmcp import FastMCP


def build_server() -> FastMCP:
    mcp = FastMCP(
        "audio",
        instructions=(
            "Audio: an Ableton Live project catalog. Use `search` and `get_project` to explore "
            "the user's library; use `propose_batch` to suggest renames/moves/tags/colors/archives "
            "for the user to approve. You CANNOT execute writes directly — only propose them."
        ),
    )

    @mcp.tool
    def search(
        query: str | None = None,
        tempo_min: float | None = None,
        tempo_max: float | None = None,
        archived: bool | None = False,
        limit: int = 50,
    ) -> list[dict]:
        """Search the project catalog by FTS query (name/plugin/sample) and/or tempo range.

        Returns a list of project rows with id, name, path, tempo, time signature, track counts,
        live_version, last_modified, and archived flag.
        """
        conn = open_db(db_path())
        return _search_projects(
            conn,
            query=query,
            tempo_min=tempo_min,
            tempo_max=tempo_max,
            archived=archived,
            limit=limit,
        )

    @mcp.tool
    def get_project(project_id: int) -> dict:
        """Get full details for a project: metadata, plugins, samples, and tags."""
        conn = open_db(db_path())
        conn.row_factory = sqlite3.Row
        row = conn.execute("SELECT * FROM projects WHERE id=?", (project_id,)).fetchone()
        if row is None:
            raise LookupError(f"no project id={project_id}")
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

    @mcp.tool
    def propose_batch(actions: list[dict[str, Any]], rationale: str | None = None) -> dict:
        """Submit a proposed batch of actions for the user to approve. Does NOT execute.

        Each action is `{type, args}` where type is one of: RenameProject, MoveProject,
        ArchiveProject, SetColorTag, SetTags. Args follow the same schema as the web API:
        - RenameProject: {project_id, new_dir_name}
        - MoveProject:   {project_id, new_parent}
        - ArchiveProject:{project_id}
        - SetColorTag:   {project_id, color}
        - SetTags:       {project_id, tags}

        Returns {proposal_id} so the user can approve via the web UI or CLI.
        """
        d = proposals_dir()
        d.mkdir(parents=True, exist_ok=True)
        pid = f"{datetime.now(UTC).strftime('%Y-%m-%dT%H-%M-%S')}_{uuid.uuid4().hex[:8]}"
        payload = {
            "proposal_id": pid,
            "actor": "claude",
            "actions": actions,
            "rationale": rationale,
        }
        (d / f"{pid}.json").write_text(json.dumps(payload, indent=2), encoding="utf-8")
        return {"proposal_id": pid}

    return mcp


def run() -> None:
    build_server().run()
