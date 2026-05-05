import shutil
from pathlib import Path

from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_root


def test_unchanged_file_skipped_without_hashing(tmp_path, monkeypatch):
    fixtures = Path(__file__).parent / "fixtures"
    (tmp_path / "p Project").mkdir()
    shutil.copy(fixtures / "tiny.als", tmp_path / "p Project" / "x.als")

    conn = open_db(tmp_path / "c.db")
    scan_root(conn, tmp_path)

    import audio_core.scanner.scan as scan_mod
    real_hash = scan_mod.hash_file
    calls = {"n": 0}

    def counting(p):
        calls["n"] += 1
        return real_hash(p)

    monkeypatch.setattr(scan_mod, "hash_file", counting)

    stats = scan_root(conn, tmp_path)
    assert stats.skipped == 1
    assert stats.scanned == 0
    assert calls["n"] == 0


def test_size_change_triggers_rescan(tmp_path):
    fixtures = Path(__file__).parent / "fixtures"
    (tmp_path / "p Project").mkdir()
    target = tmp_path / "p Project" / "x.als"
    shutil.copy(fixtures / "tiny.als", target)

    conn = open_db(tmp_path / "c.db")
    scan_root(conn, tmp_path)

    shutil.copy(fixtures / "median.als", target)

    stats = scan_root(conn, tmp_path)
    assert stats.scanned == 1
    assert stats.skipped == 0


def test_mtime_drift_same_content_refreshes_stat_without_full_rescan(tmp_path):
    import os

    fixtures = Path(__file__).parent / "fixtures"
    (tmp_path / "p Project").mkdir()
    target = tmp_path / "p Project" / "x.als"
    shutil.copy(fixtures / "tiny.als", target)

    conn = open_db(tmp_path / "c.db")
    scan_root(conn, tmp_path)

    new_mtime = target.stat().st_mtime + 60
    os.utime(target, (new_mtime, new_mtime))

    stats = scan_root(conn, tmp_path)
    assert stats.scanned == 0
    assert stats.skipped == 1
    row = conn.execute(
        "SELECT last_modified FROM projects WHERE path=?", (str(target),)
    ).fetchone()
    assert abs(row[0] - new_mtime) < 1.0
