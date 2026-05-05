from __future__ import annotations

import gzip
import shutil
import sqlite3
from dataclasses import dataclass
from pathlib import Path

from lxml import etree

from audio_core.safety.live_lock import is_open_in_live
from audio_core.safety.paths import ensure_within

_MAC_PREFIXES = ("/Volumes/", "/Users/", "/Library/", "/Applications/", "/private/")
_PROJECT_INFO = "Ableton Project Info"
_MIN_CFG = "Project11.cfg"


@dataclass
class RepairMacPaths:
    """Fix a Mac-saved .als so it loads on Windows without freezing.

    Strips Mac-prefix <Path Value> children from every FileRef, drops
    <OriginalFileRef> wrappers (they trigger Live's missing-files dialog),
    and creates the Ableton Project Info/ marker folder if it's missing.
    Backup is left next to the .als as <stem>.als.bak; undo restores from it.
    """

    project_id: int
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

    def execute(self, conn: sqlite3.Connection) -> dict:
        row = self._row(conn)
        als = Path(row["path"])
        proj = als.parent
        bak = als.with_suffix(".als.bak")
        had_pi = (proj / _PROJECT_INFO).is_dir()
        no_op = (row["mac_paths_count"] or 0) == 0 and had_pi

        if no_op:
            return {
                "type": "RepairMacPaths",
                "project_id": self.project_id,
                "path": str(als),
                "noop": True,
            }

        if not bak.exists():
            shutil.copy2(als, bak)

        stripped = int(row["mac_paths_count"] or 0)
        if stripped > 0:
            with gzip.open(als, "rb") as fh:
                tree = etree.parse(fh, etree.XMLParser(huge_tree=True))
            xroot = tree.getroot()
            for orig in list(xroot.iter("OriginalFileRef")):
                parent = orig.getparent()
                if parent is not None:
                    parent.remove(orig)
            for fr in xroot.iter("FileRef"):
                p_attr = fr.get("Path")
                if p_attr and p_attr.startswith(_MAC_PREFIXES):
                    del fr.attrib["Path"]
                pc = fr.find("Path")
                if pc is not None and pc.get("Value", "").startswith(_MAC_PREFIXES):
                    fr.remove(pc)
            tmp = als.with_suffix(".als.tmp")
            with gzip.open(tmp, "wb") as fh:
                tree.write(fh, xml_declaration=True, encoding="UTF-8")
            tmp.replace(als)

        if not had_pi:
            (proj / _PROJECT_INFO).mkdir(exist_ok=True)
            cfg = proj / _PROJECT_INFO / _MIN_CFG
            if not cfg.exists():
                cfg.write_bytes(b"")

        conn.execute(
            "UPDATE projects SET mac_paths_count=0, has_project_info=1 WHERE id=?",
            (self.project_id,),
        )
        conn.commit()

        return {
            "type": "RepairMacPaths",
            "project_id": self.project_id,
            "path": str(als),
            "backup": str(bak),
            "created_project_info": not had_pi,
            "stripped_mac_paths": stripped,
        }
