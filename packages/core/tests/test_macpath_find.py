import shutil
from pathlib import Path

from audio_core.db.connection import open_db
from audio_core.macpath import find_mac_imports
from audio_core.scanner.scan import scan_one


def _seed_proj(tmp_path, fixture_name: str, name: str, with_info: bool = False):
    fixtures = Path(__file__).parent / "fixtures"
    proj = tmp_path / f"{name} Project"
    proj.mkdir()
    if with_info:
        (proj / "Ableton Project Info").mkdir()
    shutil.copy(fixtures / fixture_name, proj / f"{name}.als")
    return proj / f"{name}.als"


def test_clean_catalog_returns_no_findings(tmp_path):
    conn = open_db(tmp_path / "c.db")
    scan_one(conn, _seed_proj(tmp_path, "tiny.als", "clean", with_info=True))
    assert find_mac_imports(conn) == []


def test_mac_paths_only_is_flagged(tmp_path):
    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, _seed_proj(tmp_path, "mac_imported_tiny.als", "macpaths", with_info=True))
    findings = find_mac_imports(conn)
    assert len(findings) == 1
    assert findings[0].project_id == pid
    assert findings[0].mac_paths_count == 3
    assert findings[0].project_info_missing is False


def test_missing_project_info_only_is_flagged(tmp_path):
    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, _seed_proj(tmp_path, "tiny.als", "noinfo", with_info=False))
    findings = find_mac_imports(conn)
    assert len(findings) == 1
    assert findings[0].project_id == pid
    assert findings[0].mac_paths_count == 0
    assert findings[0].project_info_missing is True


def test_archived_excluded(tmp_path):
    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, _seed_proj(tmp_path, "mac_imported_tiny.als", "arch", with_info=False))
    conn.execute("UPDATE projects SET is_archived=1 WHERE id=?", (pid,))
    conn.commit()
    assert find_mac_imports(conn) == []
