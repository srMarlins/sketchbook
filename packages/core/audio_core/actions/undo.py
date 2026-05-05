from __future__ import annotations

import shutil
import sqlite3
from pathlib import Path

from audio_core.actions.set_tags import _set_tags
from audio_core.journal.manifest import read_batch


def undo_batch(conn: sqlite3.Connection, journal_dir: str | Path, batch_id: str) -> None:
    """Reverse every action in a batch, in reverse order."""
    batch = read_batch(journal_dir, batch_id)
    for entry in reversed(batch["actions"]):
        t = entry["type"]

        if t in {"RenameProject", "MoveProject", "ArchiveProject"}:
            if entry.get("noop"):
                continue  # idempotent ArchiveProject noop — nothing to reverse
            from_, to = entry["from_"], entry["to"]
            Path(from_).parent.mkdir(parents=True, exist_ok=True)
            shutil.move(to, from_)
            conn.execute(
                "UPDATE projects SET parent_dir=?, path=replace(path, ?, ?) WHERE id=?",
                (from_, to, from_, entry["project_id"]),
            )
            if t == "ArchiveProject":
                conn.execute("UPDATE projects SET is_archived=0 WHERE id=?", (entry["project_id"],))

        elif t == "SetColorTag":
            conn.execute(
                "UPDATE projects SET color_tag=? WHERE id=?",
                (entry["before"], entry["project_id"]),
            )

        elif t == "SetTags":
            _set_tags(conn, entry["project_id"], list(entry["before"]))

        elif t == "RepairMacPaths":
            _undo_repair_mac_paths(entry, conn)

        else:
            raise NotImplementedError(f"undo not implemented for action type {t!r}")

    conn.commit()


def _undo_repair_mac_paths(entry: dict, conn: sqlite3.Connection) -> None:
    """Restore the .als from its .als.bak and re-scan to refresh the catalog row.

    The Ableton Project Info/ folder (if created by the repair) is left in place;
    undo doesn't delete directories. The .als.bak file is also left in place so a
    subsequent undo (or re-undo after redo) is cheap.
    """
    if entry.get("noop"):
        return
    # Lazy import to avoid a circular dependency between scanner and actions.
    from audio_core.scanner.scan import scan_one

    als = Path(entry["path"])
    bak = Path(entry["backup"])
    if not bak.exists():
        raise FileNotFoundError(
            f"cannot undo RepairMacPaths: backup missing at {bak}"
        )
    shutil.copy2(bak, als)
    scan_one(conn, als)
