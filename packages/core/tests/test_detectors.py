import shutil
from pathlib import Path

from audio_core.db.connection import open_db
from audio_core.detectors import find_duplicates, find_mac_imports, findings_summary
from audio_core.scanner.scan import scan_one


def _seed_proj(tmp_path, fixture_name: str, name: str, with_info: bool = False):
    fixtures = Path(__file__).parent / "fixtures"
    proj = tmp_path / f"{name} Project"
    proj.mkdir()
    if with_info:
        (proj / "Ableton Project Info").mkdir()
    shutil.copy(fixtures / fixture_name, proj / f"{name}.als")
    return proj / f"{name}.als"


def test_findings_summary_clean_db_returns_zeros(tmp_path):
    conn = open_db(tmp_path / "c.db")
    assert findings_summary(conn) == {"macpath": 0, "duplicates": 0, "missing_samples": 0}


def test_findings_summary_counts_mac_imports(tmp_path):
    conn = open_db(tmp_path / "c.db")
    scan_one(conn, _seed_proj(tmp_path, "mac_imported_tiny.als", "macpaths", with_info=True))
    summary = findings_summary(conn)
    assert summary["macpath"] >= 1
    assert summary["duplicates"] == 0


def test_detectors_reexports_match_originals():
    # Identity check: re-exports are the same callables, not wrappers.
    from audio_core.dedup import find_duplicates as _orig_dups
    from audio_core.macpath import find_mac_imports as _orig_mac

    assert find_mac_imports is _orig_mac
    assert find_duplicates is _orig_dups
