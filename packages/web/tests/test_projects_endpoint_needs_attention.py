"""HTTP-level coverage for the `needs_attention` query param on /api/projects.

Mirrors the search-level test in core: seed a row per flagged signal plus
a clean row, hit the endpoint with `needs_attention=true`, assert only
the flagged rows come back.
"""
from __future__ import annotations

import time

from audio_core.db.connection import open_db
from audio_core.db.projects import upsert_project
from audio_core.parser.model import ProjectMetadata
from audio_web.app import create_app
from fastapi.testclient import TestClient


def _seed(tmp_path):
    conn = open_db(tmp_path / "data" / "catalog.db")

    def _row(path: str, name: str) -> int:
        return upsert_project(
            conn,
            path=path,
            name=name,
            parent_dir="/x",
            file_hash=name,
            last_modified=time.time(),
            meta=ProjectMetadata(tempo=120.0),
        )

    macpath = _row("/x/mac.als", "mac")
    no_info = _row("/x/info.als", "info")
    missing = _row("/x/miss.als", "miss")
    clean = _row("/x/clean.als", "clean")
    conn.execute("UPDATE projects SET mac_paths_count=2 WHERE id=?", (macpath,))
    conn.execute("UPDATE projects SET has_project_info=0 WHERE id=?", (no_info,))
    conn.execute("UPDATE projects SET is_missing=1 WHERE id=?", (missing,))
    conn.execute(
        "UPDATE projects SET mac_paths_count=0, has_project_info=1, is_missing=0 "
        "WHERE id=?",
        (clean,),
    )
    conn.commit()


def test_list_projects_needs_attention_true(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed(tmp_path)
    client = TestClient(create_app())
    res = client.get("/api/projects", params={"needs_attention": "true"})
    assert res.status_code == 200
    assert {r["name"] for r in res.json()["items"]} == {"mac", "info", "miss"}


def test_list_projects_needs_attention_false_returns_clean(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed(tmp_path)
    client = TestClient(create_app())
    res = client.get("/api/projects", params={"needs_attention": "false"})
    assert res.status_code == 200
    assert {r["name"] for r in res.json()["items"]} == {"clean"}


def test_list_projects_needs_attention_omitted_returns_all(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed(tmp_path)
    client = TestClient(create_app())
    res = client.get("/api/projects")
    assert res.status_code == 200
    assert {r["name"] for r in res.json()["items"]} == {"mac", "info", "miss", "clean"}
