import shutil
from pathlib import Path

import pytest
from audio_core.actions.archive import ARCHIVE_DIR_NAME, ArchiveProject
from audio_core.actions.move import MoveProject
from audio_core.actions.set_color_tag import SetColorTag
from audio_core.actions.set_tags import SetTags
from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_one

FIX = Path(__file__).parent / "fixtures"


def _seed(tmp_path: Path, parent_subdir: str = "p Project") -> tuple:
    proj = tmp_path / parent_subdir
    proj.mkdir(parents=True)
    als = proj / "x.als"
    shutil.copy(FIX / "tiny.als", als)
    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, als)
    return conn, pid, als


# --- MoveProject ---


def test_move_project_to_new_parent(tmp_path):
    conn, pid, als = _seed(tmp_path)
    new_parent = tmp_path / "by_year" / "2025"
    action = MoveProject(project_id=pid, new_parent=new_parent, root=tmp_path)
    action.validate(conn)
    entry = action.execute(conn)
    assert (new_parent / "p Project" / "x.als").is_file()
    assert not (tmp_path / "p Project").exists()
    assert entry["type"] == "MoveProject"
    assert entry["to"].endswith("p Project")


def test_move_rejects_outside_root(tmp_path):
    conn, pid, als = _seed(tmp_path)
    action = MoveProject(project_id=pid, new_parent=tmp_path.parent, root=tmp_path)
    with pytest.raises(PermissionError):
        action.validate(conn)


def test_move_rejects_collision(tmp_path):
    conn, pid, als = _seed(tmp_path)
    new_parent = tmp_path / "elsewhere"
    new_parent.mkdir()
    (new_parent / "p Project").mkdir()
    action = MoveProject(project_id=pid, new_parent=new_parent, root=tmp_path)
    with pytest.raises(FileExistsError):
        action.validate(conn)


# --- ArchiveProject ---


def test_archive_moves_into_archive_dir_and_flags_db(tmp_path):
    conn, pid, als = _seed(tmp_path)
    action = ArchiveProject(project_id=pid, root=tmp_path)
    action.validate(conn)
    entry = action.execute(conn)
    expected = tmp_path / ARCHIVE_DIR_NAME / "p Project" / "x.als"
    assert expected.is_file()
    archived = conn.execute("SELECT is_archived FROM projects WHERE id=?", (pid,)).fetchone()[0]
    assert archived == 1
    assert entry["type"] == "ArchiveProject"


# --- SetColorTag ---


def test_set_color_tag_updates_db(tmp_path):
    conn, pid, als = _seed(tmp_path)
    action = SetColorTag(project_id=pid, color=5)
    action.validate(conn)
    entry = action.execute(conn)
    assert entry["before"] is None and entry["after"] == 5
    color = conn.execute("SELECT color_tag FROM projects WHERE id=?", (pid,)).fetchone()[0]
    assert color == 5


def test_set_color_tag_clear(tmp_path):
    conn, pid, als = _seed(tmp_path)
    SetColorTag(project_id=pid, color=3).execute(conn)
    entry = SetColorTag(project_id=pid, color=None).execute(conn)
    assert entry["before"] == 3 and entry["after"] is None


def test_set_color_tag_rejects_out_of_range(tmp_path):
    conn, pid, als = _seed(tmp_path)
    with pytest.raises(ValueError):
        SetColorTag(project_id=pid, color=99).validate(conn)


# --- SetTags ---


def test_set_tags_replaces_tag_set(tmp_path):
    conn, pid, als = _seed(tmp_path)
    SetTags(project_id=pid, tags=["lofi", "chillhop"]).execute(conn)
    entry = SetTags(project_id=pid, tags=["lofi", "boombap"]).execute(conn)
    assert entry["before"] == ["chillhop", "lofi"]
    assert entry["after"] == ["lofi", "boombap"]
    rows = conn.execute(
        "SELECT t.name FROM tags t JOIN project_tags pt ON pt.tag_id=t.id "
        "WHERE pt.project_id=? ORDER BY t.name",
        (pid,),
    ).fetchall()
    assert {r[0] for r in rows} == {"lofi", "boombap"}


def test_set_tags_empty_clears_tags(tmp_path):
    conn, pid, als = _seed(tmp_path)
    SetTags(project_id=pid, tags=["x", "y"]).execute(conn)
    SetTags(project_id=pid, tags=[]).execute(conn)
    n = conn.execute("SELECT COUNT(*) FROM project_tags WHERE project_id=?", (pid,)).fetchone()[0]
    assert n == 0


def test_set_tags_rejects_empty_string(tmp_path):
    conn, pid, als = _seed(tmp_path)
    with pytest.raises(ValueError):
        SetTags(project_id=pid, tags=[""]).validate(conn)
