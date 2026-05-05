"""Tests for the `needs_attention` filter on `search_projects`.

A project "needs attention" when any of these signals is set:

  * mac_paths_count > 0 — scanner found unresolved Mac-style paths
  * has_project_info = 0 — Ableton's project sidecar dir is missing
  * is_missing = 1 — file disappeared since last scan

We seed four rows: one per flag plus a clean row, and assert that
`needs_attention=True` returns the three flagged, `False` returns only the
clean row, and `None` returns all four.
"""
from __future__ import annotations

import time

from audio_core.db.connection import open_db
from audio_core.db.projects import search_projects, upsert_project
from audio_core.parser.model import ProjectMetadata


def _seed(conn, *, path, name):
    return upsert_project(
        conn,
        path=path,
        name=name,
        parent_dir="/x",
        file_hash=name,
        last_modified=time.time(),
        meta=ProjectMetadata(tempo=120.0),
    )


def _seed_four(conn) -> dict[str, int]:
    macpath = _seed(conn, path="/x/macpath.als", name="macpath")
    no_info = _seed(conn, path="/x/no_info.als", name="no_info")
    missing = _seed(conn, path="/x/missing.als", name="missing")
    clean = _seed(conn, path="/x/clean.als", name="clean")
    # Apply each flag with a focused UPDATE so the seed stays explicit.
    conn.execute("UPDATE projects SET mac_paths_count=2 WHERE id=?", (macpath,))
    conn.execute("UPDATE projects SET has_project_info=0 WHERE id=?", (no_info,))
    conn.execute("UPDATE projects SET is_missing=1 WHERE id=?", (missing,))
    conn.execute(
        "UPDATE projects SET mac_paths_count=0, has_project_info=1, is_missing=0 "
        "WHERE id=?",
        (clean,),
    )
    conn.commit()
    return {"macpath": macpath, "no_info": no_info, "missing": missing, "clean": clean}


def test_needs_attention_true_returns_flagged_rows(tmp_path):
    conn = open_db(tmp_path / "t.db")
    _seed_four(conn)
    rows = search_projects(conn, needs_attention=True, archived=None)
    assert {r["name"] for r in rows} == {"macpath", "no_info", "missing"}


def test_needs_attention_false_returns_only_clean_row(tmp_path):
    conn = open_db(tmp_path / "t.db")
    _seed_four(conn)
    rows = search_projects(conn, needs_attention=False, archived=None)
    assert {r["name"] for r in rows} == {"clean"}


def test_needs_attention_none_returns_all(tmp_path):
    conn = open_db(tmp_path / "t.db")
    _seed_four(conn)
    rows = search_projects(conn, needs_attention=None, archived=None)
    assert {r["name"] for r in rows} == {"macpath", "no_info", "missing", "clean"}


def test_needs_attention_treats_null_columns_as_clean(tmp_path):
    """A row with NULL flag columns is not flagged.

    Catalog rows pre-dating the macpath/info backfill have NULL
    `mac_paths_count`/`has_project_info`. The COALESCE in the predicate
    treats them as clean (0 / 1 respectively) so they don't drown the
    "needs attention" view in legacy rows.
    """
    conn = open_db(tmp_path / "t.db")
    pid = _seed(conn, path="/x/legacy.als", name="legacy")
    conn.execute(
        "UPDATE projects SET mac_paths_count=NULL, has_project_info=NULL, is_missing=0 "
        "WHERE id=?",
        (pid,),
    )
    conn.commit()
    flagged = search_projects(conn, needs_attention=True, archived=None)
    assert flagged == []
    clean = search_projects(conn, needs_attention=False, archived=None)
    assert {r["name"] for r in clean} == {"legacy"}
