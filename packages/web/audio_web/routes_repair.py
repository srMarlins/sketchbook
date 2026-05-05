from __future__ import annotations

from audio_core.config import db_path
from audio_core.db.connection import open_db
from audio_core.repair import get_repair_findings
from fastapi import APIRouter

router = APIRouter(prefix="/api/repair", tags=["repair"])


@router.get("/findings")
def findings(project_id: int | None = None, limit: int = 1000) -> dict:
    """Findings payload. When `project_id` is given, scoped to that project.
    Otherwise returns up to `limit` missing-sample rows (default 1000); the
    full library can have 100k+ broken sample references and a single payload
    that large freezes the UI."""
    conn = open_db(db_path())
    f = get_repair_findings(conn, project_id=project_id)
    truncated_missing = f.missing_samples[:limit]
    return {
        "mac_imports": [
            {
                "project_id": x.project_id,
                "path": x.path,
                "name": x.name,
                "parent_dir": x.parent_dir,
                "mac_paths_count": x.mac_paths_count,
                "project_info_missing": x.project_info_missing,
            }
            for x in f.mac_imports
        ],
        "missing_samples": [
            {
                "project_id": m.project_id,
                "project_path": m.project_path,
                "project_name": m.project_name,
                "missing_path": m.missing_path,
                "auto_match": (
                    {
                        "path": m.auto_match.path,
                        "filename": m.auto_match.filename,
                        "size_bytes": m.auto_match.size_bytes,
                    }
                    if m.auto_match
                    else None
                ),
                "candidates": [
                    {"path": c.path, "filename": c.filename, "size_bytes": c.size_bytes}
                    for c in m.candidates
                ],
            }
            for m in truncated_missing
        ],
        "missing_samples_total": len(f.missing_samples),
        "missing_samples_truncated": len(f.missing_samples) > limit,
    }
