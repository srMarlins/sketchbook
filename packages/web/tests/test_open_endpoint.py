"""Tests for POST /api/projects/{id}/open."""

from __future__ import annotations

import time
from pathlib import Path
from unittest.mock import patch

from audio_core.db.connection import open_db
from audio_core.db.projects import upsert_project
from audio_core.parser.model import ProjectMetadata
from audio_web.app import create_app
from fastapi.testclient import TestClient


def _seed(tmp_path, *, path: Path) -> int:
    conn = open_db(tmp_path / "data" / "catalog.db")
    return upsert_project(
        conn,
        path=str(path),
        name=path.stem,
        parent_dir=str(path.parent),
        file_hash="h",
        last_modified=time.time(),
        meta=ProjectMetadata(),
    )


def test_open_endpoint_calls_subprocess(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    proj_dir = tmp_path / "Projects" / "x Project"
    proj_dir.mkdir(parents=True)
    als = proj_dir / "x.als"
    als.write_bytes(b"fake")
    pid = _seed(tmp_path, path=als)
    client = TestClient(create_app())
    with patch("audio_web.routes_open.subprocess.run") as mock_run:
        mock_run.return_value = None
        res = client.post(f"/api/projects/{pid}/open")
    assert res.status_code == 200
    assert res.json() == {"ok": True}
    mock_run.assert_called_once()
    call_args = mock_run.call_args[0][0]
    # The .als path should be the last argument
    assert str(als) in call_args


def test_open_endpoint_404_on_missing_project(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    open_db(tmp_path / "data" / "catalog.db")
    client = TestClient(create_app())
    res = client.post("/api/projects/9999/open")
    assert res.status_code == 404


def test_open_endpoint_403_on_path_outside_allowlist(tmp_path, monkeypatch):
    """If the row's path is outside the projects root, refuse to launch."""
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    # Seed a project at a path OUTSIDE tmp_path/Projects
    rogue_dir = tmp_path / "elsewhere"
    rogue_dir.mkdir()
    rogue = rogue_dir / "rogue.als"
    rogue.write_bytes(b"fake")
    pid = _seed(tmp_path, path=rogue)
    client = TestClient(create_app())
    with patch("audio_web.routes_open.subprocess.run") as mock_run:
        res = client.post(f"/api/projects/{pid}/open")
    assert res.status_code == 403
    mock_run.assert_not_called()
