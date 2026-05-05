from __future__ import annotations

import sqlite3
from dataclasses import dataclass

from audio_core.macpath import MacImportFinding, find_mac_imports
from audio_core.relink import MissingSampleFinding, find_missing_samples


@dataclass(frozen=True)
class RepairFindings:
    mac_imports: list[MacImportFinding]
    missing_samples: list[MissingSampleFinding]


def get_repair_findings(
    conn: sqlite3.Connection,
    *,
    project_id: int | None = None,
) -> RepairFindings:
    """Combined findings. Pass `project_id` to scope to one project — used by
    the per-project Overview panel; a catalog with 100k+ missing samples makes
    the unscoped variant unrenderable in the UI."""
    macs = find_mac_imports(conn)
    if project_id is not None:
        macs = [m for m in macs if m.project_id == project_id]
    return RepairFindings(
        mac_imports=macs,
        missing_samples=find_missing_samples(conn, project_id=project_id),
    )
