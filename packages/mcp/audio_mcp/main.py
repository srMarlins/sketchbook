from __future__ import annotations

import json
import sqlite3
import uuid
from datetime import UTC, datetime
from typing import Any, Literal

from audio_core.config import db_path, proposals_dir
from audio_core.db.connection import open_db
from audio_core.db.projects import search_projects as _search_projects
from audio_core.dedup import find_duplicates as _find_duplicates
from fastmcp import FastMCP


def build_server() -> FastMCP:
    mcp = FastMCP(
        "audio",
        instructions=(
            "Audio: an Ableton Live project catalog. Use `search`, `get_project`, and "
            "`find_duplicates` to explore the user's library; use `propose_batch` to suggest "
            "renames/moves/tags/colors/archives for the user to approve. You CANNOT execute "
            "writes directly — only propose them.\n"
            "\n"
            "## Library organization\n"
            "\n"
            "Three orthogonal axes (canonical source: `docs/library-conventions.md`):\n"
            "\n"
            "1. **Year folder** — `Projects/<YYYY>/<project-name>/`. Year = year created, "
            "locked once set. Use `MoveProject` only for the initial backfill or to fix wrong "
            "years; do not move a project across years just because it was recently edited.\n"
            "2. **Color tag** (`SetColorTag`) — lifecycle state, visible in Ableton itself:\n"
            "   - green = done\n"
            "   - yellow = needs mix/master\n"
            "   - orange = almost done\n"
            "   - blue = in progress (actively working)\n"
            "   - purple = has potential — revisit\n"
            "   - red = dead (archive candidate)\n"
            "   - gray / no color = untriaged\n"
            "3. **Free-form tags** (`SetTags`) — content/type/intent, lowercase-hyphenated:\n"
            "   - Type (pick one): full-track, sketch, melody-loop, drum-loop, bass-loop, "
            "vocal-chop, remix, collab\n"
            "   - Genre (zero or more): house, techno, ambient, lofi, hip-hop, dnb, trap, "
            "pop, experimental\n"
            "   - Faceted (zero or more, key:value): key:Cm, bpm:140, client:x\n"
            "   Do not duplicate fields the catalog already extracts (tempo, plugins, "
            "samples).\n"
            "\n"
            "## Triage workflow\n"
            "\n"
            "When asked to triage, sort, or organize projects:\n"
            "\n"
            "1. Filter with `search` (typically `archived=false`, often by year or untriaged).\n"
            "2. For each candidate, call `get_project` to read plugins, samples, track count, "
            "last-modified.\n"
            "3. Infer a likely color (state) and content tags from that evidence — e.g. a "
            "project with one drum-rack track and no melody instruments is likely "
            "`drum-loop`; many tracks + a master chain suggests `full-track` near `green`.\n"
            "4. Submit a single `propose_batch` with `SetColorTag` and `SetTags` actions and a "
            "rationale that summarizes how you decided.\n"
            "5. The user approves in the web UI; never assume changes are live.\n"
            "\n"
            "## Effort score (derived, 0-100)\n"
            "\n"
            "Every project has an `effort_score` (INTEGER 0-100) computed at scan time from "
            "track count, plugin count, unique plugins, sample count, .als file size, and the "
            "presence of a master-chain. It is **derived**, never user-set, and may shift "
            "across versions when weights are tuned. Bands (descriptive only):\n"
            "  - 0-25 sketch / single idea\n"
            "  - 25-50 meaningful work, partial arrangement\n"
            "  - 50-75 substantial investment\n"
            "  - 75-100 serious project: full arrangement, deep sound design or mixing\n"
            "\n"
            "When the user asks for 'old projects with potential', 'forgotten gems', "
            "'buried gold', or anything implying high past investment that has since gone "
            "untouched, prefer `search` with `order_by='effort'`, `order_dir='desc'`, plus a "
            "year filter or last-modified cutoff (e.g. `min_effort=60` and a recent_only=False "
            "intent — combine with the user's date framing in your rationale). The home page "
            "exposes a 'Forgotten Gems' shelf using exactly this filter (effort_score >= 60 "
            "AND last-modified > 180 days), so when the user mentions 'gems', 'buried', or "
            "'forgotten', match that framing.\n"
            "\n"
            "When batch-tagging or triaging, sort untriaged projects by `order_by='effort'` "
            "desc — wrong-tagging a high-effort project costs more than wrong-tagging a "
            "sketch, so triage the loaded ones first.\n"
            "\n"
            "## Broken projects (read-only detection)\n"
            "\n"
            "Every project carries `parse_status` ('ok' | 'failed' | null for legacy rows) and "
            "`missing_sample_count` (integer; 0 means clean). A project is considered 'broken' "
            "when `parse_status='failed'` (the .als itself is corrupt or un-parseable) OR "
            "`missing_sample_count > 0` (one or more referenced samples are not on disk).\n"
            "\n"
            "When the user asks about 'broken', 'won't open', 'missing samples', 'corrupt', or "
            "'lost samples' projects, call `search` with `broken=true`. To explicitly exclude "
            "them from a list, pass `broken=false`. Default (`broken=None`) returns everything.\n"
            "\n"
            "Detection is read-only: this MCP cannot fix broken projects (no .als writers and "
            "no sample-relinker exist yet). When the user asks to repair, explain the limitation "
            "and either suggest manual steps or wait for that feature.\n"
        ),
    )

    @mcp.tool
    def search(
        query: str | None = None,
        tempo_min: float | None = None,
        tempo_max: float | None = None,
        archived: bool | None = False,
        min_effort: int | None = None,
        max_effort: int | None = None,
        broken: bool | None = None,
        order_by: Literal["mtime", "name", "effort"] = "mtime",
        order_dir: Literal["asc", "desc"] = "desc",
        limit: int = 50,
    ) -> list[dict]:
        """Search the project catalog by FTS query (name/plugin/sample) and/or tempo range.

        Returns a list of project rows with id, name, path, tempo, time signature, track counts,
        live_version, last_modified, archived flag, effort_score (0-100, derived),
        parse_status ('ok' | 'failed' | null), and missing_sample_count.

        Effort filters and `order_by="effort"` power forgotten-gem queries: e.g. high-effort
        projects untouched in 6+ months. Use min_effort=60 + a date filter for that pattern.

        `broken=True` returns only projects that failed to parse OR have at least one missing
        sample. `broken=False` excludes them. `broken=None` (default) returns everything.
        """
        conn = open_db(db_path())
        return _search_projects(
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

    @mcp.tool
    def find_duplicates(limit: int = 100) -> list[dict]:
        """Find .als files in the catalog that are byte-identical (same blake3 hash).

        Returns groups, each with a recommended keeper (newest non-archived mtime,
        shortest-path tiebreak) and a list of losers. To clean them up, call
        `propose_batch` with one `ArchiveProject` action per loser id.
        """
        conn = open_db(db_path())
        groups = _find_duplicates(conn)[:limit]
        return [
            {
                "file_hash": g.file_hash,
                "keeper": _summary(g.keeper),
                "losers": [_summary(loser) for loser in g.losers],
            }
            for g in groups
        ]

    return mcp


def _summary(row: dict) -> dict:
    return {
        "id": row["id"],
        "name": row["name"],
        "path": row["path"],
        "last_modified": row["last_modified"],
        "is_archived": bool(row["is_archived"]),
    }


def run() -> None:
    build_server().run()
