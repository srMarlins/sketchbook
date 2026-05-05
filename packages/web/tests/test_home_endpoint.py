"""Tests for GET /api/home and the shelf composition."""

from __future__ import annotations

import time

from audio_core.db.connection import open_db
from audio_core.db.projects import upsert_project
from audio_core.parser.model import ProjectMetadata
from audio_web.app import create_app
from audio_web.home import COLOR_NAMES
from fastapi.testclient import TestClient

DAY = 86400.0


def _seed(
    conn,
    *,
    name: str,
    mtime: float,
    color: int | None = None,
    effort: int | None = None,
    archived: bool = False,
) -> int:
    pid = upsert_project(
        conn,
        path=f"/x/{name}.als",
        name=name,
        parent_dir="/x",
        file_hash=name,
        last_modified=mtime,
        meta=ProjectMetadata(),
    )
    if color is not None:
        conn.execute("UPDATE projects SET color_tag=? WHERE id=?", (color, pid))
    if effort is not None:
        conn.execute("UPDATE projects SET effort_score=? WHERE id=?", (effort, pid))
    if archived:
        conn.execute("UPDATE projects SET is_archived=1 WHERE id=?", (pid,))
    conn.commit()
    return pid


def _shelf(shelves, sid):
    return next(s for s in shelves if s["id"] == sid)


def test_home_returns_all_shelves_in_order(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    open_db(tmp_path / "data" / "catalog.db")
    res = TestClient(create_app()).get("/api/home")
    assert res.status_code == 200
    body = res.json()
    ids = [s["id"] for s in body["shelves"]]
    assert ids == [
        "currently-working",
        "forgotten-gems",
        "almost-done",
        "has-potential",
        "untriaged",
        "recent-activity",
        "gems-sample",
    ]


def test_currently_working_blue_or_recent(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    now = time.time()
    blue = _seed(conn, name="blue_old", mtime=now - 365 * DAY, color=COLOR_NAMES["blue"])
    recent = _seed(conn, name="recent_no_color", mtime=now - 1 * DAY)
    _seed(conn, name="old_uncolored", mtime=now - 365 * DAY)
    res = TestClient(create_app()).get("/api/home")
    shelf = _shelf(res.json()["shelves"], "currently-working")
    ids = {p["id"] for p in shelf["projects"]}
    assert ids == {blue, recent}


def test_forgotten_gems_high_effort_old_no_green_or_red(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    now = time.time()
    gem_a = _seed(conn, name="gem_a", mtime=now - 200 * DAY, effort=95)
    gem_b = _seed(conn, name="gem_b", mtime=now - 365 * DAY, effort=85)
    _seed(conn, name="recent_high_effort", mtime=now - 30 * DAY, effort=95)  # not old
    _seed(conn, name="old_below_floor", mtime=now - 200 * DAY, effort=70)  # below 80 floor
    _seed(
        conn,
        name="old_high_effort_green",
        mtime=now - 200 * DAY,
        effort=80,
        color=COLOR_NAMES["green"],
    )  # excluded by color
    _seed(
        conn,
        name="old_high_effort_red",
        mtime=now - 200 * DAY,
        effort=80,
        color=COLOR_NAMES["red"],
    )
    _seed(
        conn,
        name="archived",
        mtime=now - 200 * DAY,
        effort=80,
        archived=True,
    )
    res = TestClient(create_app()).get("/api/home")
    shelf = _shelf(res.json()["shelves"], "forgotten-gems")
    ids = [p["id"] for p in shelf["projects"]]
    assert ids == [gem_a, gem_b]  # sorted by effort desc


def test_almost_done_orange_yellow(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    now = time.time()
    o = _seed(conn, name="o", mtime=now, color=COLOR_NAMES["orange"], effort=50)
    y = _seed(conn, name="y", mtime=now, color=COLOR_NAMES["yellow"], effort=80)
    _seed(conn, name="b", mtime=now, color=COLOR_NAMES["blue"], effort=90)
    res = TestClient(create_app()).get("/api/home")
    shelf = _shelf(res.json()["shelves"], "almost-done")
    ids = [p["id"] for p in shelf["projects"]]
    assert ids == [y, o]  # effort desc


def test_has_potential_purple_only(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    now = time.time()
    p_old = _seed(conn, name="po", mtime=now - 100 * DAY, color=COLOR_NAMES["purple"])
    p_new = _seed(conn, name="pn", mtime=now, color=COLOR_NAMES["purple"])
    _seed(conn, name="green", mtime=now, color=COLOR_NAMES["green"])
    res = TestClient(create_app()).get("/api/home")
    shelf = _shelf(res.json()["shelves"], "has-potential")
    ids = [p["id"] for p in shelf["projects"]]
    assert ids == [p_new, p_old]


def test_untriaged_no_color_not_archived(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    now = time.time()
    a = _seed(conn, name="a", mtime=now, effort=80)
    b = _seed(conn, name="b", mtime=now, effort=20)
    _seed(conn, name="c", mtime=now, color=COLOR_NAMES["blue"])
    _seed(conn, name="d", mtime=now, archived=True)
    res = TestClient(create_app()).get("/api/home")
    shelf = _shelf(res.json()["shelves"], "untriaged")
    ids = [p["id"] for p in shelf["projects"]]
    assert ids == [a, b]


def test_empty_shelves_remain_in_response(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    open_db(tmp_path / "data" / "catalog.db")
    body = TestClient(create_app()).get("/api/home").json()
    for s in body["shelves"]:
        assert s["projects"] == []
        assert "title" in s
        assert "description" in s
        assert "see_all_query" in s
