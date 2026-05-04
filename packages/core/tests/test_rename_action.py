import shutil
from pathlib import Path

import pytest
from audio_core.actions.rename import RenameProject
from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_one

FIX = Path(__file__).parent / "fixtures"


def _seed(tmp_path: Path, src_name: str, project_dir_name: str, als_basename: str):
    src = FIX / src_name
    proj = tmp_path / project_dir_name
    proj.mkdir()
    als = proj / als_basename
    shutil.copy(src, als)
    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, als)
    return conn, pid, als


def test_rename_moves_directory(tmp_path):
    conn, pid, als = _seed(tmp_path, "tiny.als", "old name Project", "old.als")
    action = RenameProject(project_id=pid, new_dir_name="new name Project", root=tmp_path)
    action.validate(conn)
    entry = action.execute(conn)
    assert (tmp_path / "new name Project" / "old.als").is_file()
    assert not (tmp_path / "old name Project").exists()
    assert entry["type"] == "RenameProject"
    assert entry["from_"].endswith("old name Project")
    assert entry["to"].endswith("new name Project")
    assert entry["project_id"] == pid


def test_rename_updates_db_path(tmp_path):
    conn, pid, als = _seed(tmp_path, "tiny.als", "old Project", "x.als")
    action = RenameProject(project_id=pid, new_dir_name="new Project", root=tmp_path)
    action.validate(conn)
    action.execute(conn)
    new_path = str(tmp_path / "new Project" / "x.als")
    row = conn.execute("SELECT path, parent_dir FROM projects WHERE id=?", (pid,)).fetchone()
    assert row[0] == new_path
    assert row[1] == str(tmp_path / "new Project")


def test_rename_rejects_target_outside_root(tmp_path):
    conn, pid, als = _seed(tmp_path, "tiny.als", "p Project", "x.als")
    action = RenameProject(project_id=pid, new_dir_name="../escape", root=tmp_path)
    with pytest.raises(PermissionError):
        action.validate(conn)


def test_rename_rejects_existing_target(tmp_path):
    conn, pid, als = _seed(tmp_path, "tiny.als", "old Project", "x.als")
    (tmp_path / "occupied Project").mkdir()
    action = RenameProject(project_id=pid, new_dir_name="occupied Project", root=tmp_path)
    with pytest.raises(FileExistsError):
        action.validate(conn)


def test_rename_rejects_unknown_project_id(tmp_path):
    conn, pid, als = _seed(tmp_path, "tiny.als", "p Project", "x.als")
    action = RenameProject(project_id=pid + 999, new_dir_name="whatever", root=tmp_path)
    with pytest.raises(LookupError):
        action.validate(conn)
