import time
from pathlib import Path

from audio_cli.main import app
from audio_core.db.connection import open_db
from audio_core.db.projects import upsert_project
from audio_core.parser.model import PluginRef, ProjectMetadata, SampleRef
from typer.testing import CliRunner

FIX = Path(__file__).parents[2] / "core" / "tests" / "fixtures"


def _seed_db_with_two(tmp_path):
    db = tmp_path / "data" / "catalog.db"
    conn = open_db(db)
    a = upsert_project(
        conn,
        path="/x/a.als",
        name="alpha_track",
        parent_dir="/x",
        file_hash="ha",
        last_modified=time.time(),
        meta=ProjectMetadata(
            tempo=140.0,
            plugins=[PluginRef(name="Pro-Q 3", plugin_type="vst3", track_name="Master")],
            samples=[SampleRef(path="/x/kick.wav")],
        ),
    )
    b = upsert_project(
        conn,
        path="/x/b.als",
        name="bravo",
        parent_dir="/x",
        file_hash="hb",
        last_modified=time.time(),
        meta=ProjectMetadata(tempo=90.0),
    )
    return a, b


def test_search_returns_results(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed_db_with_two(tmp_path)
    res = CliRunner().invoke(app, ["search", "--query", "alpha"])
    assert res.exit_code == 0, res.stdout
    assert "alpha_track" in res.stdout


def test_search_tempo_range(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed_db_with_two(tmp_path)
    res = CliRunner().invoke(app, ["search", "--tempo-min", "120", "--tempo-max", "150"])
    assert res.exit_code == 0
    assert "alpha_track" in res.stdout
    assert "bravo" not in res.stdout


def test_search_no_results(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed_db_with_two(tmp_path)
    res = CliRunner().invoke(app, ["search", "--query", "nonexistent_xyz"])
    assert res.exit_code == 0
    assert "no results" in res.stdout.lower()


def test_show_prints_metadata_and_plugins(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    a, _ = _seed_db_with_two(tmp_path)
    res = CliRunner().invoke(app, ["show", str(a)])
    assert res.exit_code == 0
    assert "alpha_track" in res.stdout
    assert "Pro-Q 3" in res.stdout
    assert "kick.wav" in res.stdout


def test_show_unknown_id_exits_nonzero(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed_db_with_two(tmp_path)
    res = CliRunner().invoke(app, ["show", "9999"])
    assert res.exit_code != 0
