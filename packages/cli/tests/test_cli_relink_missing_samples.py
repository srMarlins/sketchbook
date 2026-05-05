import json as _json
import shutil
from pathlib import Path

from audio_cli.main import app
from audio_core.db.connection import open_db
from audio_core.samples import upsert_sample
from audio_core.scanner.scan import scan_one
from typer.testing import CliRunner


def _seed(tmp_path):
    fixtures = Path(__file__).parents[2] / "core" / "tests" / "fixtures"
    proj = tmp_path / "Projects" / "p Project"
    proj.mkdir(parents=True)
    shutil.copy(fixtures / "missing_sample_tiny.als", proj / "p.als")
    cand = tmp_path / "lib" / "relink_test_kick.wav"
    cand.parent.mkdir(parents=True)
    cand.write_bytes(b"x")
    conn = open_db(tmp_path / "data" / "catalog.db")
    pid = scan_one(conn, proj / "p.als")
    upsert_sample(conn, cand)
    return pid


def test_no_findings(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    open_db(tmp_path / "data" / "catalog.db")
    res = CliRunner().invoke(app, ["relink-missing-samples"])
    assert res.exit_code == 0
    assert "No missing samples" in res.stdout


def test_lists_findings(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed(tmp_path)
    res = CliRunner().invoke(app, ["relink-missing-samples"])
    assert res.exit_code == 0
    assert str(pid) in res.stdout


def test_json(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed(tmp_path)
    res = CliRunner().invoke(app, ["relink-missing-samples", "--json"])
    assert res.exit_code == 0
    payload = _json.loads(res.stdout)
    assert isinstance(payload, list)


def test_propose_writes_only_auto_matches(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed(tmp_path)
    res = CliRunner().invoke(app, ["relink-missing-samples", "--propose"])
    assert res.exit_code == 0
    files = list((tmp_path / "data" / "proposals").glob("*.json"))
    assert len(files) == 1
    payload = _json.loads(files[0].read_text(encoding="utf-8"))
    actions = payload["actions"]
    assert all(a["type"] == "RelinkMissingSamples" for a in actions)
    assert {a["args"]["project_id"] for a in actions} == {pid}
