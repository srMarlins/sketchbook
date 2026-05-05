from __future__ import annotations

import sqlite3
from dataclasses import dataclass

from audio_core.macpath import MacImportFinding, find_mac_imports
from audio_core.relink import MissingSampleFinding, find_missing_samples


@dataclass(frozen=True)
class RepairFindings:
    mac_imports: list[MacImportFinding]
    missing_samples: list[MissingSampleFinding]


def get_repair_findings(conn: sqlite3.Connection) -> RepairFindings:
    return RepairFindings(
        mac_imports=find_mac_imports(conn),
        missing_samples=find_missing_samples(conn),
    )
