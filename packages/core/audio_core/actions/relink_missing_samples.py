from __future__ import annotations

import gzip
import shutil
import sqlite3
from dataclasses import dataclass
from pathlib import Path

from lxml import etree

from audio_core.safety.live_lock import is_open_in_live
from audio_core.safety.paths import ensure_within


@dataclass(frozen=True)
class Relink:
    old: str
    new: str


@dataclass
class RelinkMissingSamples:
    """Rewrite missing-sample paths in a .als to point at indexed candidates.

    For each (old, new) pair, find every <FileRef> whose <Path Value> resolves
    to `old` (treating `old` as either the recorded sample_path, or its
    basename), and replace it with the absolute `new` path; clear
    <HasRelativePath> so Live re-resolves on save.

    Backup convention matches RepairMacPaths: <stem>.als.bak created if absent.
    Atomic temp-replace. Refuses to run if Live has the file open or the
    project's parent_dir is outside `root`. Each `new` candidate must exist on
    disk at validate-time.
    """

    project_id: int
    relinks: list[Relink]
    root: Path

    def _row(self, conn: sqlite3.Connection) -> sqlite3.Row:
        conn.row_factory = sqlite3.Row
        r = conn.execute("SELECT * FROM projects WHERE id=?", (self.project_id,)).fetchone()
        if r is None:
            raise LookupError(f"no project id={self.project_id}")
        return r

    def validate(self, conn: sqlite3.Connection) -> None:
        row = self._row(conn)
        ensure_within(Path(row["parent_dir"]), self.root)
        if is_open_in_live(row["path"]):
            raise RuntimeError(f"Live has {row['path']} open; close it first")
        for r in self.relinks:
            if not Path(r.new).is_file():
                raise FileNotFoundError(f"relink candidate missing: {r.new}")

    def execute(self, conn: sqlite3.Connection) -> dict:
        row = self._row(conn)
        als = Path(row["path"])
        bak = als.with_suffix(".als.bak")

        if not self.relinks:
            return {
                "type": "RelinkMissingSamples",
                "project_id": self.project_id,
                "path": str(als),
                "noop": True,
            }

        if not bak.exists():
            shutil.copy2(als, bak)

        with gzip.open(als, "rb") as fh:
            tree = etree.parse(fh, etree.XMLParser(huge_tree=True))
        xroot = tree.getroot()

        relink_map = {r.old: str(Path(r.new).resolve()) for r in self.relinks}
        basename_map: dict[str, str] = {}
        for old, new in relink_map.items():
            base = old.rsplit("/", 1)[-1].rsplit("\\", 1)[-1]
            basename_map.setdefault(base, new)

        rewritten = 0
        for fr in xroot.iter("FileRef"):
            pn = fr.find("Path")
            if pn is None:
                continue
            cur = pn.get("Value", "")
            new = relink_map.get(cur)
            if new is None:
                base = cur.rsplit("/", 1)[-1].rsplit("\\", 1)[-1]
                new = basename_map.get(base)
            if new is None:
                continue
            pn.set("Value", new)
            for tag in ("RelativePath", "HasRelativePath", "RelativePathType"):
                el = fr.find(tag)
                if el is not None:
                    fr.remove(el)
            rewritten += 1

        if rewritten == 0:
            return {
                "type": "RelinkMissingSamples",
                "project_id": self.project_id,
                "path": str(als),
                "noop": True,
            }

        tmp = als.with_suffix(".als.tmp")
        with gzip.open(tmp, "wb") as fh:
            tree.write(fh, xml_declaration=True, encoding="UTF-8")
        tmp.replace(als)

        for r in self.relinks:
            new_abs = str(Path(r.new).resolve())
            stat = Path(r.new).stat()
            conn.execute(
                "UPDATE project_samples SET sample_path=?, is_missing=0, size_bytes=? "
                "WHERE project_id=? AND sample_path=?",
                (new_abs, stat.st_size, self.project_id, r.old),
            )
        conn.commit()

        return {
            "type": "RelinkMissingSamples",
            "project_id": self.project_id,
            "path": str(als),
            "backup": str(bak),
            "relinks": [{"old": r.old, "new": r.new} for r in self.relinks],
            "rewritten": rewritten,
        }
