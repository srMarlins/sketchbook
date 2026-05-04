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

        else:
            raise NotImplementedError(f"undo not implemented for action type {t!r}")

    conn.commit()
