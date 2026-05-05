import shutil
from pathlib import Path

from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_one
from audio_web.app import create_app
from fastapi.testclient import TestClient

FIX = Path(__file__).parents[2] / "core" / "tests" / "fixtures"


def _seed_real_project(tmp_path: Path, dir_name: str = "p Project"):
    proj = tmp_path / "Projects" / dir_name
    proj.mkdir(parents=True)
    als = proj / "x.als"
    shutil.copy(FIX / "tiny.als", als)
    conn = open_db(tmp_path / "data" / "catalog.db")
    return scan_one(conn, als)


def test_submit_proposal_does_not_execute(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed_real_project(tmp_path)
    client = TestClient(create_app())
    res = client.post(
        "/api/proposals",
        json={
            "actor": "claude",
            "actions": [
                {"type": "RenameProject", "args": {"project_id": pid, "new_dir_name": "moved"}}
            ],
            "rationale": "test",
        },
    )
    assert res.status_code == 201, res.text
    proposal_id = res.json()["proposal_id"]
    assert (tmp_path / "data" / "proposals" / f"{proposal_id}.json").exists()
    # The original directory must still be there — proposal is unexecuted.
    assert (tmp_path / "Projects" / "p Project" / "x.als").is_file()


def test_list_and_get_proposal(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed_real_project(tmp_path)
    client = TestClient(create_app())
    create = client.post(
        "/api/proposals",
        json={"actor": "claude", "actions": [], "rationale": "noop"},
    )
    proposal_id = create.json()["proposal_id"]
    listing = client.get("/api/proposals").json()
    assert any(x["proposal_id"] == proposal_id for x in listing)
    one = client.get(f"/api/proposals/{proposal_id}").json()
    assert one["actor"] == "claude"
    assert one["rationale"] == "noop"


def test_approve_proposal_executes_and_writes_journal(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed_real_project(tmp_path, "old Project")
    client = TestClient(create_app())
    submit = client.post(
        "/api/proposals",
        json={
            "actor": "claude",
            "actions": [
                {
                    "type": "RenameProject",
                    "args": {"project_id": pid, "new_dir_name": "new Project"},
                }
            ],
        },
    )
    proposal_id = submit.json()["proposal_id"]
    approve = client.post(f"/api/proposals/{proposal_id}/approve")
    assert approve.status_code == 200, approve.text
    assert (tmp_path / "Projects" / "new Project" / "x.als").is_file()
    assert (tmp_path / "data" / "journal" / f"{approve.json()['batch_id']}.json").exists()
    # Proposal was consumed.
    assert not (tmp_path / "data" / "proposals" / f"{proposal_id}.json").exists()


def test_approve_invalid_proposal_returns_400(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed_real_project(tmp_path)
    client = TestClient(create_app())
    submit = client.post(
        "/api/proposals",
        json={
            "actor": "claude",
            "actions": [
                {
                    "type": "RenameProject",
                    "args": {"project_id": pid, "new_dir_name": "../escape"},
                }
            ],
        },
    )
    proposal_id = submit.json()["proposal_id"]
    res = client.post(f"/api/proposals/{proposal_id}/approve")
    assert res.status_code == 400
    # Proposal stays around for retry.
    assert (tmp_path / "data" / "proposals" / f"{proposal_id}.json").exists()


def test_reject_proposal(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed_real_project(tmp_path)
    client = TestClient(create_app())
    submit = client.post(
        "/api/proposals",
        json={"actor": "claude", "actions": []},
    )
    proposal_id = submit.json()["proposal_id"]
    res = client.delete(f"/api/proposals/{proposal_id}")
    assert res.status_code == 204
    assert not (tmp_path / "data" / "proposals" / f"{proposal_id}.json").exists()


def test_unknown_proposal_404(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    client = TestClient(create_app())
    assert client.get("/api/proposals/nope").status_code == 404
    assert client.post("/api/proposals/nope/approve").status_code == 404
    assert client.delete("/api/proposals/nope").status_code == 404


def _seed_mac_project(tmp_path: Path, dir_name: str = "mac Project"):
    proj = tmp_path / "Projects" / dir_name
    proj.mkdir(parents=True)
    als = proj / "m.als"
    shutil.copy(FIX / "mac_imported_tiny.als", als)
    conn = open_db(tmp_path / "data" / "catalog.db")
    return scan_one(conn, als)


def test_approve_repair_mac_paths_proposal(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed_mac_project(tmp_path)
    als = tmp_path / "Projects" / "mac Project" / "m.als"
    bak = als.with_suffix(".als.bak")
    assert als.is_file()
    assert not bak.exists()

    client = TestClient(create_app())
    submit = client.post(
        "/api/proposals",
        json={
            "actor": "claude",
            "actions": [
                {"type": "RepairMacPaths", "args": {"project_id": pid}},
            ],
        },
    )
    assert submit.status_code == 201, submit.text
    proposal_id = submit.json()["proposal_id"]

    approve = client.post(f"/api/proposals/{proposal_id}/approve")
    assert approve.status_code == 200, approve.text

    assert bak.is_file()
    import gzip

    with gzip.open(als, "rb") as fh:
        body = fh.read()
    for prefix in (b"/Volumes/", b"/Users/", b"/Library/", b"/Applications/", b"/private/"):
        assert prefix not in body, f"Mac prefix {prefix!r} still present in {als}"

    import sqlite3

    conn = open_db(tmp_path / "data" / "catalog.db")
    conn.row_factory = sqlite3.Row
    row = conn.execute(
        "SELECT mac_paths_count, has_project_info FROM projects WHERE id=?", (pid,)
    ).fetchone()
    assert row["mac_paths_count"] == 0
    assert row["has_project_info"] == 1

    assert not (tmp_path / "data" / "proposals" / f"{proposal_id}.json").exists()
    assert (tmp_path / "data" / "journal" / f"{approve.json()['batch_id']}.json").exists()


def test_submit_rejects_move_outside_allowlist(tmp_path, monkeypatch):
    """A MoveProject with new_parent outside projects_root must be rejected
    at SUBMIT, not just at approve — never write a malicious proposal to disk."""
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed_real_project(tmp_path)
    client = TestClient(create_app())
    res = client.post(
        "/api/proposals",
        json={
            "actor": "claude",
            "actions": [
                {
                    "type": "MoveProject",
                    "args": {
                        "project_id": pid,
                        "new_parent": "C:/Windows/Temp",
                    },
                }
            ],
        },
    )
    assert res.status_code == 400, res.text
    assert "MoveProject" in res.text
    pdir = tmp_path / "data" / "proposals"
    if pdir.exists():
        assert list(pdir.glob("*.json")) == []


def test_submit_rejects_unknown_action_type(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed_real_project(tmp_path)
    client = TestClient(create_app())
    res = client.post(
        "/api/proposals",
        json={
            "actor": "claude",
            "actions": [{"type": "DropDatabase", "args": {}}],
        },
    )
    assert res.status_code == 422


def test_approve_race_second_call_404s(tmp_path, monkeypatch):
    """Once approve renames the proposal to .processing, a concurrent call
    for the same proposal id sees the original path missing and 404s."""
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed_real_project(tmp_path, "old Project")
    client = TestClient(create_app())
    submit = client.post(
        "/api/proposals",
        json={
            "actor": "claude",
            "actions": [
                {
                    "type": "RenameProject",
                    "args": {"project_id": pid, "new_dir_name": "new Project"},
                }
            ],
        },
    )
    proposal_id = submit.json()["proposal_id"]
    p = tmp_path / "data" / "proposals" / f"{proposal_id}.json"
    p.rename(p.with_suffix(p.suffix + ".processing"))
    res = client.post(f"/api/proposals/{proposal_id}/approve")
    assert res.status_code == 404


def test_approve_failure_restores_proposal_for_retry(tmp_path, monkeypatch):
    """If run_batch raises, the .processing file is renamed back so the user
    can fix and retry. The proposal must NOT be lost on the floor."""
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed_real_project(tmp_path)
    client = TestClient(create_app())
    submit = client.post(
        "/api/proposals",
        json={
            "actor": "claude",
            "actions": [
                {
                    "type": "RenameProject",
                    "args": {"project_id": pid, "new_dir_name": "a:b"},
                }
            ],
        },
    )
    proposal_id = submit.json()["proposal_id"]
    res = client.post(f"/api/proposals/{proposal_id}/approve")
    assert res.status_code == 400
    proposal_path = tmp_path / "data" / "proposals" / f"{proposal_id}.json"
    assert proposal_path.exists(), "failed approve must leave the proposal in place for retry"


def test_submit_rejects_missing_args(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed_real_project(tmp_path)
    client = TestClient(create_app())
    res = client.post(
        "/api/proposals",
        json={
            "actor": "claude",
            "actions": [{"type": "RenameProject", "args": {"project_id": 1}}],
        },
    )
    assert res.status_code == 400
    assert "new_dir_name" in res.text or "field required" in res.text.lower()
