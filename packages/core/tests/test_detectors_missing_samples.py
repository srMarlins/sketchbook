from audio_core.db.connection import open_db
from audio_core.detectors import findings_summary


def test_summary_has_missing_samples_count(tmp_path):
    conn = open_db(tmp_path / "c.db")
    s = findings_summary(conn)
    assert "missing_samples" in s
    assert s["missing_samples"] == 0
