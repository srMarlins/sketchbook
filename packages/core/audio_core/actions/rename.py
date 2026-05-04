from __future__ import annotations

import shutil
import sqlite3
from dataclasses import dataclass
from pathlib import Path

from audio_core.safety.live_lock import is_open_in_live
from audio_core.safety.paths import ensure_within


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
        new_dir = old_dir.parent / self.new_dir_name
        ensure_within(new_dir, self.root)
        if new_dir.exists():
            raise FileExistsError(new_dir)
        if is_open_in_live(row["path"]):
            raise RuntimeError(f"Live has {row['path']} open; close it first")

    def execute(self, conn: sqlite3.Connection) -> dict:
        row = self._row(conn)
        old_dir = Path(row["parent_dir"])
        new_dir = old_dir.parent / self.new_dir_name
        shutil.move(str(old_dir), str(new_dir))
        new_path = str(new_dir / Path(row["path"]).name)
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
            "hash_before": row["file_hash"],
        }
