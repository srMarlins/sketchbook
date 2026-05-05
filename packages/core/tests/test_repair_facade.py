from audio_core.db.connection import open_db
from audio_core.repair import get_repair_findings


def test_empty_catalog(tmp_path):
    conn = open_db(tmp_path / "c.db")
    f = get_repair_findings(conn)
    assert f.mac_imports == []
    assert f.missing_samples == []
