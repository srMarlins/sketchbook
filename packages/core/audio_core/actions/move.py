from __future__ import annotations

import shutil
import sqlite3
from dataclasses import dataclass
from pathlib import Path

from audio_core.safety.live_lock import is_open_in_live
from audio_core.safety.paths import ensure_within


@dataclass
class MoveProject:
    """Move a project directory under a new parent directory (rename of parent only)."""

    project_id: int
    new_parent: Path
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
        ensure_within(self.new_parent, self.root)
        target = self.new_parent / old_dir.name
        if target.exists():
            raise FileExistsError(target)
        if is_open_in_live(row["path"]):
            raise RuntimeError(f"Live has {row['path']} open; close it first")

    def execute(self, conn: sqlite3.Connection) -> dict:
        row = self._row(conn)
        old_dir = Path(row["parent_dir"])
        new_dir = self.new_parent / old_dir.name
        new_dir.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(old_dir), str(new_dir))
        new_path = str(new_dir / Path(row["path"]).name)
        conn.execute(
            "UPDATE projects SET parent_dir=?, path=? WHERE id=?",
            (str(new_dir), new_path, self.project_id),
        )
        conn.commit()
        return {
            "type": "MoveProject",
            "project_id": self.project_id,
            "from_": str(old_dir),
            "to": str(new_dir),
            "hash_before": row["file_hash"],
        }
