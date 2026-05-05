from __future__ import annotations

import re
import shutil
import sqlite3
from dataclasses import dataclass
from pathlib import Path

from audio_core.safety.live_lock import is_open_in_live
from audio_core.safety.paths import ensure_within

# Windows-illegal filename characters plus path separators. We never want a
# rename to silently mangle a name (e.g. ":" creates an alternate data stream).
# Reject control chars and trailing dots/spaces — Windows trims those silently
# which causes the resulting dir to differ from what we recorded in the journal.
_WINDOWS_ILLEGAL = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


def _validate_dir_name(name: str) -> None:
    if not name or not isinstance(name, str):
        raise ValueError(f"new_dir_name must be a non-empty string, got {name!r}")
    if name in {".", ".."}:
        raise ValueError(f"new_dir_name cannot be {name!r}")
    if _WINDOWS_ILLEGAL.search(name):
        raise ValueError(
            f"new_dir_name contains illegal characters (one of <>:\"/\\|?* or control chars): {name!r}"
        )
    if name.endswith(" ") or name.endswith("."):
        raise ValueError(
            f"new_dir_name cannot end with a space or dot (Windows trims them): {name!r}"
        )
    # Reserved Windows names (case-insensitive)
    reserved = {
        "CON", "PRN", "AUX", "NUL",
        *(f"COM{i}" for i in range(1, 10)),
        *(f"LPT{i}" for i in range(1, 10)),
    }
    if name.upper().split(".")[0] in reserved:
        raise ValueError(f"new_dir_name is a reserved Windows device name: {name!r}")


@dataclass
class RenameProject:
    """Rename the on-disk *directory* that contains a project's .als file.

    The .als basename is unchanged; only the parent dir name moves. Reversed
    by undoing with the recorded `from_`/`to` paths.
    """

    project_id: int
    new_dir_name: str
    root: Path

    def _row(self, conn: sqlite3.Connection) -> sqlite3.Row:
        conn.row_factory = sqlite3.Row
        r = conn.execute("SELECT * FROM projects WHERE id=?", (self.project_id,)).fetchone()
        if r is None:
            raise LookupError(f"no project id={self.project_id}")
        return r

    def validate(self, conn: sqlite3.Connection) -> None:
        row = self._row(conn)
        old_dir = Path(row["parent_dir"])
        ensure_within(old_dir, self.root)
        _validate_dir_name(self.new_dir_name)
        new_dir = old_dir.parent / self.new_dir_name
        ensure_within(new_dir, self.root)
        if new_dir.exists():
            raise FileExistsError(new_dir)
        if is_open_in_live(row["path"]):
            raise RuntimeError(f"Live has {row['path']} open; close it first")

    def execute(self, conn: sqlite3.Connection) -> dict:
        row = self._row(conn)
        old_dir = Path(row["parent_dir"])
        old_path = row["path"]
        new_dir = old_dir.parent / self.new_dir_name
        shutil.move(str(old_dir), str(new_dir))
        new_path = str(new_dir / Path(old_path).name)
        conn.execute(
            "UPDATE projects SET parent_dir=?, path=? WHERE id=?",
            (str(new_dir), new_path, self.project_id),
        )
        conn.commit()
        return {
            "type": "RenameProject",
            "project_id": self.project_id,
            "from_": str(old_dir),
            "to": str(new_dir),
            "path_before": old_path,
            "path_after": new_path,
            "hash_before": row["file_hash"],
        }
