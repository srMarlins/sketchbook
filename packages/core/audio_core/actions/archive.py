from __future__ import annotations

import shutil
import sqlite3
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path

from audio_core.safety.live_lock import is_open_in_live
from audio_core.safety.paths import ensure_within

ARCHIVE_DIR_NAME = "_Archive"


def _archive_suffix() -> str:
    """Stable per-action suffix to prevent silent merges of same-named projects.
    Format: <YYYYMMDDTHHMMSS>__<4-hex>. Generated once per ArchiveProject instance."""
    return f"{datetime.now(UTC).strftime('%Y%m%dT%H%M%S')}__{uuid.uuid4().hex[:4]}"


@dataclass
class ArchiveProject:
    """Move a project into <root>/_Archive/<name>__<timestamp>__<rand>/ and
    toggle is_archived=1. The timestamp suffix prevents collision with prior
    archived folders of the same name (Windows shutil.move otherwise silently
    merges into an existing dir).

    Reversed by undo: project returns to its recorded original parent and is_archived=0.
    """

    project_id: int
    root: Path
    archive_subname: str = field(default_factory=_archive_suffix)

    def _row(self, conn: sqlite3.Connection) -> sqlite3.Row:
        conn.row_factory = sqlite3.Row
        r = conn.execute("SELECT * FROM projects WHERE id=?", (self.project_id,)).fetchone()
        if r is None:
            raise LookupError(f"no project id={self.project_id}")
        return r

    def _target_dir_name(self, old_dir: Path) -> str:
        return f"{old_dir.name}__{self.archive_subname}"

    def validate(self, conn: sqlite3.Connection) -> None:
        row = self._row(conn)
        if row["is_archived"]:
            return  # idempotent: already archived, execute() will be a no-op
        old_dir = Path(row["parent_dir"])
        ensure_within(old_dir, self.root)
        archive = self.root / ARCHIVE_DIR_NAME
        target = archive / self._target_dir_name(old_dir)
        if target.exists():
            raise FileExistsError(target)
        if is_open_in_live(row["path"]):
            raise RuntimeError(f"Live has {row['path']} open; close it first")

    def execute(self, conn: sqlite3.Connection) -> dict:
        row = self._row(conn)
        if row["is_archived"]:
            return {
                "type": "ArchiveProject",
                "project_id": self.project_id,
                "from_": row["parent_dir"],
                "to": row["parent_dir"],
                "path_before": row["path"],
                "path_after": row["path"],
                "hash_before": row["file_hash"],
                "noop": True,
            }
        old_dir = Path(row["parent_dir"])
        old_path = row["path"]
        archive = self.root / ARCHIVE_DIR_NAME
        archive.mkdir(exist_ok=True)
        new_dir = archive / self._target_dir_name(old_dir)
        shutil.move(str(old_dir), str(new_dir))
        new_path = str(new_dir / Path(old_path).name)
        conn.execute(
            "UPDATE projects SET parent_dir=?, path=?, is_archived=1 WHERE id=?",
            (str(new_dir), new_path, self.project_id),
        )
        conn.commit()
        return {
            "type": "ArchiveProject",
            "project_id": self.project_id,
            "from_": str(old_dir),
            "to": str(new_dir),
            "path_before": old_path,
            "path_after": new_path,
            "hash_before": row["file_hash"],
        }
