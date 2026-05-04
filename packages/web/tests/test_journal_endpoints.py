import shutil
from pathlib import Path

from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_one
from audio_web.app import create_app
from fastapi.testclient import TestClient

FIX = Path(__file__).parents[2] / "core" / "tests" / "fixtures"


def _seed(tmp_path: Path, dir_name: str = "p Project") -> int:
    proj = tmp_path / "Projects" / dir_name
    proj.mkdir(parents=True)
    als = proj / "x.als"
    shutil.copy(FIX / "tiny.als", als)
    conn = open_db(tmp_path / "data" / "catalog.db")
    return scan_one(conn, als)


def _approve_a_rename(client, pid):
    submit = client.post(
        "/api/proposals",
        json={
            "actor": "user",
            "actions": [
                {
                    "type": "RenameProject",
                    "args": {"project_id": pid, "new_dir_name": "renamed Project"},
                }
            ],
        },
    )
    proposal_id = submit.json()["proposal_id"]
    return client.post(f"/api/proposals/{proposal_id}/approve").json()["batch_id"]


def test_list_journal(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed(tmp_path, "old Project")
    client = TestClient(create_app())
    bid = _approve_a_rename(client, pid)
    res = client.get("/api/journal")
    assert res.status_code == 200
    assert any(b["batch_id"] == bid for b in res.json())


def test_get_batch_detail(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed(tmp_path, "old Project")
    client = TestClient(create_app())
    bid = _approve_a_rename(client, pid)
    res = client.get(f"/api/journal/{bid}")
    assert res.status_code == 200
    body = res.json()
    assert body["actor"] == "user"
    assert body["actions"][0]["type"] == "RenameProject"


def test_undo_via_endpoint(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed(tmp_path, "old Project")
    client = TestClient(create_app())
    bid = _approve_a_rename(client, pid)
    assert (tmp_path / "Projects" / "renamed Project" / "x.als").is_file()
    res = client.post(f"/api/journal/{bid}/undo")
    assert res.status_code == 200
    assert (tmp_path / "Projects" / "old Project" / "x.als").is_file()
    assert not (tmp_path / "Projects" / "renamed Project").exists()


def test_unknown_batch_404(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    client = TestClient(create_app())
    assert client.get("/api/journal/nope").status_code == 404
    assert client.post("/api/journal/nope/undo").status_code == 404
