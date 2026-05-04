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
    # Tags are eagerly attached so the frontend doesn't N+1 to detail.
    for r in rows:
        assert "tags" in r and isinstance(r["tags"], list)


def test_list_projects_tags_populated(tmp_path, monkeypatch):
    """Projects with tags surface them in the list response."""
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    a, _ = _seed(tmp_path)
    conn = open_db(tmp_path / "data" / "catalog.db")
    conn.execute("INSERT INTO tags (name) VALUES ('vox'), ('demos')")
    conn.execute(
        "INSERT INTO project_tags (project_id, tag_id) "
        "SELECT ?, id FROM tags WHERE name IN ('vox','demos')",
        (a,),
    )
    conn.commit()
    client = TestClient(create_app())
    rows = client.get("/api/projects").json()
    by_name = {r["name"]: r for r in rows}
    assert sorted(by_name["alpha_track"]["tags"]) == ["demos", "vox"]
    assert by_name["bravo"]["tags"] == []


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
