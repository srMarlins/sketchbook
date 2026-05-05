from fastapi.testclient import TestClient


def test_findings_endpoint(monkeypatch, tmp_path):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    (tmp_path / "Projects").mkdir()
    from audio_web.app import create_app
    with TestClient(create_app()) as client:
        r = client.get("/api/repair/findings")
        assert r.status_code == 200
        body = r.json()
        assert "mac_imports" in body
        assert "missing_samples" in body
