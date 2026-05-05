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


def test_scan_root_skips_by_mtime_without_hashing(tmp_path):
    """If size + mtime match the stored row, we must not even open the file
    to hash it — that's the perf optimization that makes warm rescans cheap.
    """
    from unittest.mock import patch

    d = tmp_path / "tiny Project"
    d.mkdir()
    shutil.copy(FIX / "tiny.als", d / "tiny.als")
    conn = open_db(tmp_path / "c.db")
    scan_root(conn, tmp_path)
    # Second pass — patch hash_file so we can prove it was NOT called
    with patch("audio_core.scanner.scan.hash_file") as mock_hash:
        stats = scan_root(conn, tmp_path)
    assert stats.skipped == 1
    mock_hash.assert_not_called()


def test_scan_root_parallel_workers_match_serial_results(tmp_path):
    """Running with >1 worker must produce the same stats and DB rows as the
    single-threaded path. as_completed ordering is non-deterministic so we
    compare sets of rows, not insertion order."""
    for name in ("tiny", "median"):
        d = tmp_path / f"{name} Project"
        d.mkdir()
        shutil.copy(FIX / f"{name}.als", d / f"{name}.als")
    conn = open_db(tmp_path / "c.db")
    stats = scan_root(conn, tmp_path, max_workers=4)
    assert stats.scanned == 2
    assert stats.skipped == 0
    assert stats.failed == 0
    rows = conn.execute("SELECT name, parse_status FROM projects ORDER BY name").fetchall()
    assert {r[0] for r in rows} == {"tiny", "median"}
    assert all(r[1] == "ok" for r in rows)


def test_scan_root_parallel_handles_one_corrupt(tmp_path):
    """A corrupt .als alongside good ones must still be flagged failed; the
    others must scan fine. Tests that worker exceptions don't poison sibling
    work."""
    good = tmp_path / "good Project"
    good.mkdir()
    shutil.copy(FIX / "tiny.als", good / "good.als")
    bad = tmp_path / "bad Project"
    bad.mkdir()
    (bad / "broken.als").write_bytes(b"not a real gzip stream")
    conn = open_db(tmp_path / "c.db")
    stats = scan_root(conn, tmp_path, max_workers=4)
    assert stats.scanned == 1
    assert stats.failed == 1


def test_scan_root_falls_back_to_hash_when_mtime_changes(tmp_path):
    """If the mtime changes but content is identical, we should hash it,
    discover it's the same, and skip — not re-parse."""
    import os
    import time

    d = tmp_path / "tiny Project"
    d.mkdir()
    als = d / "tiny.als"
    shutil.copy(FIX / "tiny.als", als)
    conn = open_db(tmp_path / "c.db")
    scan_root(conn, tmp_path)
    # Bump the mtime to simulate a touch, content unchanged
    new_mtime = time.time() + 60
    os.utime(als, (new_mtime, new_mtime))
    stats = scan_root(conn, tmp_path)
    # Hash matches existing row -> still skipped (just a more expensive skip)
    assert stats.skipped == 1
    assert stats.scanned == 0


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


def test_scan_root_persists_failed_parse_as_stub(tmp_path):
    """A corrupt .als should appear in the catalog flagged as failed —
    not silently disappear."""
    d = tmp_path / "mixed Project"
    d.mkdir()
    shutil.copy(FIX / "tiny.als", d / "ok.als")
    bad = d / "broken.als"
    bad.write_bytes(b"not a real gzip stream")
    conn = open_db(tmp_path / "c.db")
    stats = scan_root(conn, tmp_path)
    assert stats.scanned == 1
    assert stats.failed == 1

    rows = conn.execute(
        "SELECT name, parse_status, parse_error FROM projects ORDER BY name"
    ).fetchall()
    assert len(rows) == 2
    by_name = {r[0]: r for r in rows}
    assert by_name["broken"][1] == "failed"
    assert by_name["broken"][2] is not None and by_name["broken"][2] != ""
    assert by_name["ok"][1] == "ok"
    assert by_name["ok"][2] is None
