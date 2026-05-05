import json as _json
import shutil
from pathlib import Path

from audio_cli.main import app
from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_one
from typer.testing import CliRunner


def _seed(tmp_path, fixture_name: str, name: str, with_info: bool = False):
    fixtures = Path(__file__).parents[2] / "core" / "tests" / "fixtures"
    proj = tmp_path / f"{name} Project"
    proj.mkdir(parents=True, exist_ok=True)
    if with_info:
        (proj / "Ableton Project Info").mkdir(exist_ok=True)
    shutil.copy(fixtures / fixture_name, proj / f"{name}.als")
    return proj / f"{name}.als"


def test_no_findings(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    open_db(tmp_path / "data" / "catalog.db")
    res = CliRunner().invoke(app, ["repair-mac-paths"])
    assert res.exit_code == 0
    assert "No Mac-imported projects" in res.stdout


def test_lists_findings(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    pid = scan_one(conn, _seed(tmp_path, "mac_imported_tiny.als", "macpaths"))
    res = CliRunner().invoke(app, ["repair-mac-paths"])
    assert res.exit_code == 0
    assert "macpaths" in res.stdout
    assert str(pid) in res.stdout


def test_json(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    scan_one(conn, _seed(tmp_path, "mac_imported_tiny.als", "j"))
    res = CliRunner().invoke(app, ["repair-mac-paths", "--json"])
    assert res.exit_code == 0
    assert _json.loads(res.stdout)


def test_propose_writes_proposal(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    pid = scan_one(conn, _seed(tmp_path, "mac_imported_tiny.als", "p"))
    res = CliRunner().invoke(app, ["repair-mac-paths", "--propose"])
    assert res.exit_code == 0
    files = list((tmp_path / "data" / "proposals").glob("*.json"))
    assert len(files) == 1
    payload = _json.loads(files[0].read_text(encoding="utf-8"))
    assert payload["actor"] == "cli"
    assert {a["args"]["project_id"] for a in payload["actions"]} == {pid}
