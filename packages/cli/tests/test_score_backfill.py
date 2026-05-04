import time

from audio_cli.main import app
from audio_core.db.connection import open_db
from audio_core.db.projects import upsert_project
from audio_core.parser.model import PluginRef, ProjectMetadata, SampleRef
from typer.testing import CliRunner


def test_score_backfill_recomputes_effort(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    db = tmp_path / "data" / "catalog.db"
    conn = open_db(db)
    pid = upsert_project(
        conn,
        path=str(tmp_path / "missing.als"),  # path may not exist on disk
        name="x",
        parent_dir=str(tmp_path),
        file_hash="h",
        last_modified=time.time(),
        meta=ProjectMetadata(
            track_count=10,
            plugins=[PluginRef(name="p", plugin_type="vst3", track_name="Master")],
            samples=[SampleRef(path="/x/s.wav")],
        ),
    )
    # Wipe the score to prove the backfill recomputes it.
    conn.execute("UPDATE projects SET effort_score=NULL, effort_breakdown=NULL WHERE id=?", (pid,))
    conn.commit()
    conn.close()

    res = CliRunner().invoke(app, ["score-backfill"])
    assert res.exit_code == 0, res.stdout
    assert "recomputed effort_score for 1" in res.stdout

    conn = open_db(db)
    row = conn.execute(
        "SELECT effort_score, effort_breakdown FROM projects WHERE id=?", (pid,)
    ).fetchone()
    assert row[0] is not None
    assert 0 <= row[0] <= 100
    assert row[1] is not None and row[1].startswith("{")
