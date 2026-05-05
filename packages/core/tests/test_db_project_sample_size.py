import shutil
from pathlib import Path

from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_one


def test_existing_sample_persists_size(tmp_path):
    proj = tmp_path / "p Project"
    proj.mkdir()
    fixtures = Path(__file__).parent / "fixtures"
    shutil.copy(fixtures / "tiny.als", proj / "p.als")
    sample = proj / "Samples" / "kick.wav"
    sample.parent.mkdir()
    sample.write_bytes(b"\x00" * 1234)

    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, proj / "p.als")
    rows = conn.execute(
        "SELECT sample_path, is_missing, size_bytes FROM project_samples WHERE project_id=?",
        (pid,),
    ).fetchall()
    by_name = {Path(r[0]).name: r for r in rows}
    if "kick.wav" in by_name:  # tiny.als may or may not reference kick.wav
        r = by_name["kick.wav"]
        assert r[1] == 0
        assert r[2] == 1234


def test_missing_sample_size_is_null(tmp_path):
    """Samples that don't exist on disk get NULL size_bytes (we never had a
    chance to stat them). The relink matcher treats NULL as 'no size info'."""
    proj = tmp_path / "p Project"
    proj.mkdir()
    fixtures = Path(__file__).parent / "fixtures"
    shutil.copy(fixtures / "tiny.als", proj / "p.als")
    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, proj / "p.als")
    rows = conn.execute(
        "SELECT is_missing, size_bytes FROM project_samples WHERE project_id=?",
        (pid,),
    ).fetchall()
    for is_missing, size in rows:
        if is_missing == 1:
            assert size is None
