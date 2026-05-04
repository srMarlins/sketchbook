import time

from audio_core.db.connection import open_db
from audio_core.db.projects import upsert_project
from audio_core.parser.model import PluginRef, ProjectMetadata, SampleRef
from audio_web.app import create_app
from fastapi.testclient import TestClient


def _seed(tmp_path):
    conn = open_db(tmp_path / "data" / "catalog.db")
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


def test_list_projects(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed(tmp_path)
    client = TestClient(create_app())
    res = client.get("/api/projects")
    assert res.status_code == 200
    rows = res.json()
    assert len(rows) == 2
    assert {r["name"] for r in rows} == {"alpha_track", "bravo"}


def test_list_projects_query_filter(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed(tmp_path)
    client = TestClient(create_app())
    res = client.get("/api/projects", params={"query": "alpha"})
    assert res.status_code == 200
    assert {r["name"] for r in res.json()} == {"alpha_track"}


def test_list_projects_tempo_range(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed(tmp_path)
    client = TestClient(create_app())
    res = client.get("/api/projects", params={"tempo_min": 120, "tempo_max": 160})
    assert {r["name"] for r in res.json()} == {"alpha_track"}


def test_project_detail(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    a, _ = _seed(tmp_path)
    client = TestClient(create_app())
    res = client.get(f"/api/projects/{a}")
    assert res.status_code == 200
    body = res.json()
    assert body["name"] == "alpha_track"
    assert body["tempo"] == 140.0
    assert len(body["plugins"]) == 1
    assert body["plugins"][0]["plugin_name"] == "Pro-Q 3"
    assert len(body["samples"]) == 1
    assert body["samples"][0]["sample_path"] == "/x/kick.wav"


def test_project_detail_404(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed(tmp_path)
    client = TestClient(create_app())
    res = client.get("/api/projects/9999")
    assert res.status_code == 404


def test_list_projects_includes_effort_score(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed(tmp_path)
    client = TestClient(create_app())
    res = client.get("/api/projects")
    assert res.status_code == 200
    rows = res.json()
    for r in rows:
        assert "effort_score" in r


def test_list_projects_min_max_effort(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    a, b = _seed(tmp_path)
    # Force scores
    from audio_core.config import db_path
    from audio_core.db.connection import open_db

    conn = open_db(db_path())
    conn.execute("UPDATE projects SET effort_score=80 WHERE id=?", (a,))
    conn.execute("UPDATE projects SET effort_score=10 WHERE id=?", (b,))
    conn.commit()
    client = TestClient(create_app())
    res = client.get("/api/projects", params={"min_effort": 50})
    assert {r["name"] for r in res.json()} == {"alpha_track"}
    res = client.get("/api/projects", params={"max_effort": 30})
    assert {r["name"] for r in res.json()} == {"bravo"}


def test_list_projects_order_by_effort(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    a, b = _seed(tmp_path)
    from audio_core.config import db_path
    from audio_core.db.connection import open_db

    conn = open_db(db_path())
    conn.execute("UPDATE projects SET effort_score=10 WHERE id=?", (a,))
    conn.execute("UPDATE projects SET effort_score=90 WHERE id=?", (b,))
    conn.commit()
    client = TestClient(create_app())
    res = client.get("/api/projects", params={"order_by": "effort", "order_dir": "desc"})
    assert [r["name"] for r in res.json()] == ["bravo", "alpha_track"]
