import shutil
from pathlib import Path

from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_one


def test_scan_persists_mac_paths_count(tmp_path):
    fixtures = Path(__file__).parent / "fixtures"
    (tmp_path / "p Project").mkdir()
    shutil.copy(fixtures / "mac_imported_tiny.als", tmp_path / "p Project" / "x.als")

    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, tmp_path / "p Project" / "x.als")
    row = conn.execute(
        "SELECT mac_paths_count, has_project_info FROM projects WHERE id=?", (pid,)
    ).fetchone()
    assert row[0] == 3
    # No Ableton Project Info/ folder was created → 0
    assert row[1] == 0


def test_scan_with_project_info_folder(tmp_path):
    fixtures = Path(__file__).parent / "fixtures"
    proj = tmp_path / "p Project"
    proj.mkdir()
    (proj / "Ableton Project Info").mkdir()
    shutil.copy(fixtures / "tiny.als", proj / "x.als")

    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, proj / "x.als")
    row = conn.execute(
        "SELECT mac_paths_count, has_project_info FROM projects WHERE id=?", (pid,)
    ).fetchone()
    assert row[0] == 0
    assert row[1] == 1
