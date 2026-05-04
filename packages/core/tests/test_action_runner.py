import shutil
from pathlib import Path

import pytest
from audio_core.actions.rename import RenameProject
from audio_core.actions.runner import run_batch
from audio_core.actions.set_color_tag import SetColorTag
from audio_core.db.connection import open_db
from audio_core.journal.manifest import read_batch
from audio_core.scanner.scan import scan_one

FIX = Path(__file__).parent / "fixtures"


def _seed(tmp_path: Path) -> tuple:
    proj = tmp_path / "old Project"
    proj.mkdir()
    als = proj / "x.als"
    shutil.copy(FIX / "tiny.als", als)
    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, als)
    return conn, pid, als


def test_run_batch_writes_journal(tmp_path):
    conn, pid, als = _seed(tmp_path)
    journal = tmp_path / "journal"
    bid = run_batch(
        conn,
        [RenameProject(project_id=pid, new_dir_name="new Project", root=tmp_path)],
        actor="user",
        journal_dir=journal,
    )
    assert (journal / f"{bid}.json").exists()
    assert (tmp_path / "new Project" / "x.als").is_file()
    batch = read_batch(journal, bid)
    assert batch["actor"] == "user"
    assert len(batch["actions"]) == 1
    assert batch["actions"][0]["type"] == "RenameProject"


def test_run_batch_executes_multiple_actions(tmp_path):
    conn, pid, als = _seed(tmp_path)
    journal = tmp_path / "journal"
    bid = run_batch(
        conn,
        [
            SetColorTag(project_id=pid, color=4),
            RenameProject(project_id=pid, new_dir_name="renamed Project", root=tmp_path),
        ],
        actor="claude",
        journal_dir=journal,
    )
    batch = read_batch(journal, bid)
    assert [a["type"] for a in batch["actions"]] == ["SetColorTag", "RenameProject"]
    color = conn.execute("SELECT color_tag FROM projects WHERE id=?", (pid,)).fetchone()[0]
    assert color == 4
    assert (tmp_path / "renamed Project" / "x.als").is_file()


def test_run_batch_validates_all_before_executing_any(tmp_path):
    """If any action fails validation, no action should have executed."""
    conn, pid, als = _seed(tmp_path)
    journal = tmp_path / "journal"
    actions = [
        SetColorTag(project_id=pid, color=2),  # valid
        RenameProject(project_id=pid, new_dir_name="../bad", root=tmp_path),  # invalid
    ]
    with pytest.raises(PermissionError):
        run_batch(conn, actions, actor="user", journal_dir=journal)
    color = conn.execute("SELECT color_tag FROM projects WHERE id=?", (pid,)).fetchone()[0]
    assert color is None  # validation prevented the SetColorTag from running
    assert not list(journal.glob("*.json"))
