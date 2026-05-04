import shutil
from pathlib import Path

from audio_core.db.connection import open_db
from audio_core.db.projects import get_project_by_path
from audio_core.scanner.scan import scan_one

FIX = Path(__file__).parent / "fixtures"


def test_scan_one_persists_metadata(tmp_path):
    src = FIX / "tiny.als"
    dst_dir = tmp_path / "tiny Project"
    dst_dir.mkdir()
    dst = dst_dir / "tiny.als"
    shutil.copy(src, dst)
    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, dst)
    assert pid > 0
    row = get_project_by_path(conn, str(dst))
    assert row is not None
    assert row["tempo"] is not None
    assert row["live_version"] is not None
    assert row["file_hash"]


def test_scan_one_writes_effort_score(tmp_path):
    src = FIX / "tiny.als"
    dst_dir = tmp_path / "tiny Project"
    dst_dir.mkdir()
    dst = dst_dir / "tiny.als"
    shutil.copy(src, dst)
    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, dst)
    row = conn.execute(
        "SELECT effort_score, effort_breakdown FROM projects WHERE id=?", (pid,)
    ).fetchone()
    assert row[0] is not None
    assert 0 <= row[0] <= 100
    assert row[1] is not None and row[1].startswith("{")


def test_scan_one_links_plugins_and_samples(tmp_path):
    src = FIX / "old_lofi.als"
    dst_dir = tmp_path / "lofi Project"
    dst_dir.mkdir()
    dst = dst_dir / "lofi.als"
    shutil.copy(src, dst)
    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, dst)
    plugins = conn.execute(
        "SELECT COUNT(*) FROM project_plugins WHERE project_id=?", (pid,)
    ).fetchone()[0]
    samples = conn.execute(
        "SELECT COUNT(*) FROM project_samples WHERE project_id=?", (pid,)
    ).fetchone()[0]
    assert plugins > 0 and samples > 0
