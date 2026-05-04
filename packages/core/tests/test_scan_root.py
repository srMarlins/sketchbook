import shutil
from pathlib import Path

from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_root

FIX = Path(__file__).parent / "fixtures"


def test_scan_root_finds_and_persists(tmp_path):
    for name in ("tiny", "median"):
        d = tmp_path / f"{name} Project"
        d.mkdir()
        shutil.copy(FIX / f"{name}.als", d / f"{name}.als")
    conn = open_db(tmp_path / "c.db")
    stats = scan_root(conn, tmp_path)
    assert stats.scanned == 2
    assert stats.skipped == 0
    assert stats.failed == 0


def test_scan_root_hash_skip_on_second_pass(tmp_path):
    for name in ("tiny", "median"):
        d = tmp_path / f"{name} Project"
        d.mkdir()
        shutil.copy(FIX / f"{name}.als", d / f"{name}.als")
    conn = open_db(tmp_path / "c.db")
    scan_root(conn, tmp_path)
    stats = scan_root(conn, tmp_path)
    assert stats.scanned == 0
    assert stats.skipped == 2


def test_scan_root_excludes_backup(tmp_path):
    d = tmp_path / "p Project"
    d.mkdir()
    shutil.copy(FIX / "tiny.als", d / "p.als")
    backup = d / "Backup"
    backup.mkdir()
    shutil.copy(FIX / "tiny.als", backup / "p [old].als")
    conn = open_db(tmp_path / "c.db")
    stats = scan_root(conn, tmp_path)
    assert stats.scanned == 1


def test_scan_root_invokes_progress_callback(tmp_path):
    d = tmp_path / "p Project"
    d.mkdir()
    shutil.copy(FIX / "tiny.als", d / "p.als")
    conn = open_db(tmp_path / "c.db")
    seen: list[tuple[Path, str]] = []
    scan_root(conn, tmp_path, on_progress=lambda p, status: seen.append((p, status)))
    assert len(seen) == 1
    assert seen[0][1] == "scanned"


def test_scan_root_records_failed_files(tmp_path):
    d = tmp_path / "p Project"
    d.mkdir()
    bad = d / "broken.als"
    bad.write_bytes(b"not a real gzip stream")
    conn = open_db(tmp_path / "c.db")
    stats = scan_root(conn, tmp_path)
    assert stats.failed == 1
    assert stats.scanned == 0
