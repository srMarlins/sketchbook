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
    body = res.json()
    rows = body["items"]
    assert body["next_cursor"] is None
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
    rows = client.get("/api/projects").json()["items"]
    by_name = {r["name"]: r for r in rows}
    assert sorted(by_name["alpha_track"]["tags"]) == ["demos", "vox"]
    assert by_name["bravo"]["tags"] == []


def test_list_projects_query_filter(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed(tmp_path)
    client = TestClient(create_app())
    res = client.get("/api/projects", params={"query": "alpha"})
    assert res.status_code == 200
    assert {r["name"] for r in res.json()["items"]} == {"alpha_track"}


def test_list_projects_tempo_range(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed(tmp_path)
    client = TestClient(create_app())
    res = client.get("/api/projects", params={"tempo_min": 120, "tempo_max": 160})
    assert {r["name"] for r in res.json()["items"]} == {"alpha_track"}


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
    rows = res.json()["items"]
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
    assert {r["name"] for r in res.json()["items"]} == {"alpha_track"}
    res = client.get("/api/projects", params={"max_effort": 30})
    assert {r["name"] for r in res.json()["items"]} == {"bravo"}


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
    assert [r["name"] for r in res.json()["items"]] == ["bravo", "alpha_track"]


def test_cursor_pagination_walks_all_rows_without_duplicates(tmp_path, monkeypatch):
    """Walking pages via next_cursor must yield exactly the full set, in
    order, with no duplicates and no skips."""
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    # Seed 12 projects with monotonically increasing mtimes
    for i in range(12):
        upsert_project(
            conn,
            path=f"/x/{i:02d}.als",
            name=f"proj{i:02d}",
            parent_dir="/x",
            file_hash=f"h{i}",
            last_modified=1000.0 + i,
            meta=ProjectMetadata(),
        )
    client = TestClient(create_app())
    seen: list[int] = []
    cursor: str | None = None
    pages = 0
    while True:
        params: dict = {"limit": 5}
        if cursor:
            params["cursor"] = cursor
        body = client.get("/api/projects", params=params).json()
        seen.extend(p["id"] for p in body["items"])
        pages += 1
        if pages > 10:
            raise AssertionError("infinite cursor loop")
        if body["next_cursor"] is None:
            break
        cursor = body["next_cursor"]
    # All 12 ids returned exactly once
    assert len(seen) == 12
    assert len(set(seen)) == 12
    # Default order is mtime DESC — newest first
    assert seen == sorted(seen, reverse=True)


def test_cursor_pagination_invalid_cursor_400(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed(tmp_path)
    client = TestClient(create_app())
    res = client.get("/api/projects", params={"cursor": "garbage!@#"})
    assert res.status_code == 400


def test_cursor_pagination_stable_across_equal_sort_keys(tmp_path, monkeypatch):
    """Multiple rows with identical sort values must paginate without
    duplication. The id tiebreaker is what guarantees this."""
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    # All same mtime — a real-world failure mode for naive offset pagination
    same_mtime = 1700000000.0
    for i in range(8):
        upsert_project(
            conn,
            path=f"/x/{i:02d}.als",
            name=f"proj{i:02d}",
            parent_dir="/x",
            file_hash=f"h{i}",
            last_modified=same_mtime,
            meta=ProjectMetadata(),
        )
    client = TestClient(create_app())
    seen: list[int] = []
    cursor: str | None = None
    while True:
        params: dict = {"limit": 3}
        if cursor:
            params["cursor"] = cursor
        body = client.get("/api/projects", params=params).json()
        seen.extend(p["id"] for p in body["items"])
        if body["next_cursor"] is None:
            break
        cursor = body["next_cursor"]
    assert len(seen) == 8
    assert len(set(seen)) == 8
