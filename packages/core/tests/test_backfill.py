import shutil
from pathlib import Path

from audio_core.backfill import BACKFILL_SPECS, needs_backfill
from audio_core.db.connection import open_db
from audio_core.db.projects import upsert_project
from audio_core.parser.model import ProjectMetadata


def _seed_legacy_row(conn, *, path, parent_dir):
    """Seed a row that pre-dates the macpath columns (mac_paths_count IS NULL)."""
    pid = upsert_project(
        conn,
        path=path,
        name=Path(path).stem,
        parent_dir=parent_dir,
        file_hash="h",
        last_modified=0.0,
        meta=ProjectMetadata(),
    )
    # upsert_project will set mac_paths_count to whatever was passed (default None);
    # to be sure, NULL them explicitly:
    conn.execute(
        "UPDATE projects SET mac_paths_count=NULL, has_project_info=NULL WHERE id=?",
        (pid,),
    )
    conn.commit()
    return pid


def test_needs_backfill_empty(tmp_path):
    conn = open_db(tmp_path / "c.db")
    assert needs_backfill(conn) == []


def test_needs_backfill_detects_macpath_nulls(tmp_path):
    fixtures = Path(__file__).parent / "fixtures"
    proj = tmp_path / "p Project"
    proj.mkdir()
    shutil.copy(fixtures / "tiny.als", proj / "x.als")

    conn = open_db(tmp_path / "c.db")
    _seed_legacy_row(conn, path=str(proj / "x.als"), parent_dir=str(proj))
    assert "macpath" in needs_backfill(conn)


def test_macpath_spec_fills_one_row_correctly(tmp_path):
    fixtures = Path(__file__).parent / "fixtures"
    proj = tmp_path / "p Project"
    proj.mkdir()
    (proj / "Ableton Project Info").mkdir()
    shutil.copy(fixtures / "mac_imported_tiny.als", proj / "x.als")

    conn = open_db(tmp_path / "c.db")
    pid = _seed_legacy_row(conn, path=str(proj / "x.als"), parent_dir=str(proj))

    spec = next(s for s in BACKFILL_SPECS if s.name == "macpath")
    rows = list(conn.execute(spec.null_check_sql))
    assert len(rows) == 1
    spec.fill_one(conn, dict(zip(("id", "path", "parent_dir"), rows[0])))

    row = conn.execute(
        "SELECT mac_paths_count, has_project_info FROM projects WHERE id=?", (pid,)
    ).fetchone()
    assert row[0] == 3
    assert row[1] == 1


def test_macpath_spec_handles_missing_project_info(tmp_path):
    fixtures = Path(__file__).parent / "fixtures"
    proj = tmp_path / "p Project"
    proj.mkdir()
    shutil.copy(fixtures / "tiny.als", proj / "x.als")

    conn = open_db(tmp_path / "c.db")
    pid = _seed_legacy_row(conn, path=str(proj / "x.als"), parent_dir=str(proj))

    spec = next(s for s in BACKFILL_SPECS if s.name == "macpath")
    rows = list(conn.execute(spec.null_check_sql))
    spec.fill_one(conn, dict(zip(("id", "path", "parent_dir"), rows[0])))

    row = conn.execute(
        "SELECT mac_paths_count, has_project_info FROM projects WHERE id=?", (pid,)
    ).fetchone()
    assert row[0] == 0   # tiny.als has no Mac paths
    assert row[1] == 0   # no Ableton Project Info/ folder


def test_needs_backfill_clears_after_fill(tmp_path):
    fixtures = Path(__file__).parent / "fixtures"
    proj = tmp_path / "p Project"
    proj.mkdir()
    shutil.copy(fixtures / "tiny.als", proj / "x.als")
    conn = open_db(tmp_path / "c.db")
    _seed_legacy_row(conn, path=str(proj / "x.als"), parent_dir=str(proj))

    spec = next(s for s in BACKFILL_SPECS if s.name == "macpath")
    for r in conn.execute(spec.null_check_sql):
        spec.fill_one(conn, dict(zip(("id", "path", "parent_dir"), r)))
    conn.commit()

    assert "macpath" not in needs_backfill(conn)
