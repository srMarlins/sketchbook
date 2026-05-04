import json

from audio_cli.main import app
from audio_core.db.connection import open_db
from audio_core.db.projects import upsert_project
from audio_core.parser.model import ProjectMetadata
from typer.testing import CliRunner


def _seed_dup(conn, *, path, file_hash, mtime):
    return upsert_project(
        conn, path=path, name=path.rsplit("/", 1)[-1].removesuffix(".als"),
        parent_dir=path.rsplit("/", 1)[0], file_hash=file_hash, last_modified=mtime,
        meta=ProjectMetadata(),
    )


def test_dedup_no_dups(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    open_db(tmp_path / "data" / "catalog.db")  # create empty DB
    result = CliRunner().invoke(app, ["dedup"])
    assert result.exit_code == 0
    assert "No duplicates" in result.stdout


def test_dedup_lists_groups_human(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    keeper = _seed_dup(conn, path="/short.als", file_hash="abc123", mtime=2000.0)
    loser = _seed_dup(conn, path="/much/longer/path/x.als", file_hash="abc123", mtime=1000.0)
    result = CliRunner().invoke(app, ["dedup"])
    assert result.exit_code == 0
    assert "abc123" in result.stdout
    assert "KEEP" in result.stdout
    assert str(keeper) in result.stdout
    assert str(loser) in result.stdout


def test_dedup_json_output(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    _seed_dup(conn, path="/a.als", file_hash="h", mtime=2000.0)
    _seed_dup(conn, path="/b.als", file_hash="h", mtime=1000.0)
    result = CliRunner().invoke(app, ["dedup", "--json"])
    assert result.exit_code == 0
    payload = json.loads(result.stdout)
    assert len(payload) == 1
    assert payload[0]["file_hash"] == "h"
    assert "keeper" in payload[0] and "losers" in payload[0]


def test_dedup_propose_writes_proposal_file(tmp_path, monkeypatch):
    import json as _json
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    keeper = _seed_dup(conn, path="/k.als", file_hash="h", mtime=2000.0)
    loser = _seed_dup(conn, path="/l.als", file_hash="h", mtime=1000.0)
    result = CliRunner().invoke(app, ["dedup", "--propose"])
    assert result.exit_code == 0
    proposals = list((tmp_path / "data" / "proposals").glob("*.json"))
    assert len(proposals) == 1
    payload = _json.loads(proposals[0].read_text(encoding="utf-8"))
    assert payload["actor"] == "cli"
    pids = {a["args"]["project_id"] for a in payload["actions"]}
    assert pids == {loser}
    assert keeper not in pids
    assert payload["proposal_id"] in result.stdout
