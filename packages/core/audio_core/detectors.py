from __future__ import annotations

import sqlite3

from audio_core.dedup import find_duplicates
from audio_core.macpath import find_mac_imports

__all__ = ["find_mac_imports", "find_duplicates", "findings_summary"]


def findings_summary(conn: sqlite3.Connection) -> dict[str, int]:
    """Aggregate counts the indexer publishes after every scan/backfill finish.
    Cheap to compute on the catalog volume we expect (low thousands of rows)."""
    return {
        "macpath": len(find_mac_imports(conn)),
        "duplicates": len(find_duplicates(conn)),
    }
