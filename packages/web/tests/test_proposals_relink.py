from fastapi.testclient import TestClient


def test_post_relink_proposal_accepted(monkeypatch, tmp_path):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    (tmp_path / "Projects").mkdir()
    (tmp_path / "data" / "proposals").mkdir(parents=True)

    from audio_web.app import create_app
    app = create_app()
    body = {
        "actor": "user",
        "rationale": "relink",
        "actions": [
            {
                "type": "RelinkMissingSamples",
                "args": {
                    "project_id": 1,
                    "relinks": [{"old": "k.wav", "new": "Z:/lib/k.wav"}],
                },
            }
        ],
    }
    with TestClient(app) as client:
        r = client.post("/api/proposals", json=body)
        assert r.status_code == 201, r.text


def test_post_relink_rejects_empty_relinks(monkeypatch, tmp_path):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    (tmp_path / "Projects").mkdir()
    (tmp_path / "data" / "proposals").mkdir(parents=True)
    from audio_web.app import create_app
    app = create_app()
    body = {
        "actor": "user",
        "actions": [{"type": "RelinkMissingSamples", "args": {"project_id": 1, "relinks": []}}],
    }
    with TestClient(app) as client:
        r = client.post("/api/proposals", json=body)
        assert r.status_code == 400
