import gzip
import shutil
from pathlib import Path

import pytest
from lxml import etree

from audio_core.actions.repair_mac_paths import RepairMacPaths
from audio_core.actions.runner import run_batch
from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_one


def _seed_mac_project(tmp_path, root_name="root"):
    fixtures = Path(__file__).parent / "fixtures"
    root = tmp_path / root_name
    proj = root / "Projects" / "p Project"
    proj.mkdir(parents=True)
    shutil.copy(fixtures / "mac_imported_tiny.als", proj / "x.als")
    conn = open_db(root / "data" / "catalog.db")
    pid = scan_one(conn, proj / "x.als")
    return root, conn, pid, proj / "x.als"


def _als_xml(path: Path):
    with gzip.open(path, "rb") as fh:
        return etree.parse(fh, etree.XMLParser(huge_tree=True)).getroot()


def test_repair_strips_mac_paths_and_creates_project_info(tmp_path):
    root, conn, pid, als = _seed_mac_project(tmp_path)
    assert not (als.parent / "Ableton Project Info").exists()

    run_batch(
        conn,
        [RepairMacPaths(project_id=pid, root=root / "Projects")],
        actor="test",
        journal_dir=root / "data" / "journal",
    )

    rxml = _als_xml(als)
    mac = [
        p for p in rxml.iter("Path")
        if p.get("Value", "").startswith(
            ("/Volumes/", "/Users/", "/Library/", "/Applications/", "/private/")
        )
    ]
    assert mac == []
    assert list(rxml.iter("OriginalFileRef")) == []
    assert (als.parent / "Ableton Project Info").is_dir()
    assert (als.with_suffix(".als.bak")).is_file()

    row = conn.execute(
        "SELECT mac_paths_count, has_project_info FROM projects WHERE id=?", (pid,)
    ).fetchone()
    assert row[0] == 0
    assert row[1] == 1


def test_repair_is_idempotent(tmp_path):
    root, conn, pid, als = _seed_mac_project(tmp_path)
    run_batch(
        conn,
        [RepairMacPaths(project_id=pid, root=root / "Projects")],
        actor="test",
        journal_dir=root / "data" / "journal",
    )
    # Second run: nothing to do, must not raise.
    run_batch(
        conn,
        [RepairMacPaths(project_id=pid, root=root / "Projects")],
        actor="test",
        journal_dir=root / "data" / "journal",
    )
    row = conn.execute(
        "SELECT mac_paths_count, has_project_info FROM projects WHERE id=?", (pid,)
    ).fetchone()
    assert row[0] == 0 and row[1] == 1


def test_repair_refuses_outside_root(tmp_path):
    root, conn, pid, als = _seed_mac_project(tmp_path)
    bogus_root = tmp_path / "elsewhere"
    bogus_root.mkdir()
    with pytest.raises(Exception):
        RepairMacPaths(project_id=pid, root=bogus_root).validate(conn)


def test_repair_refuses_when_live_has_file_open(tmp_path, monkeypatch):
    root, conn, pid, als = _seed_mac_project(tmp_path)
    monkeypatch.setattr(
        "audio_core.actions.repair_mac_paths.is_open_in_live", lambda _path: True
    )
    with pytest.raises(RuntimeError):
        RepairMacPaths(project_id=pid, root=root / "Projects").validate(conn)
