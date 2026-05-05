import shutil
from pathlib import Path

import pytest
from audio_core.actions.archive import ArchiveProject
from audio_core.actions.move import MoveProject
from audio_core.actions.rename import RenameProject
from audio_core.actions.runner import run_batch
from audio_core.actions.set_color_tag import SetColorTag
from audio_core.actions.set_tags import SetTags
from audio_core.actions.undo import UndoError, undo_batch
from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_one

FIX = Path(__file__).parent / "fixtures"


def _seed(tmp_path: Path, dir_name: str = "p Project") -> tuple:
    proj = tmp_path / dir_name
    proj.mkdir(parents=True)
    als = proj / "x.als"
    shutil.copy(FIX / "tiny.als", als)
    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, als)
    return conn, pid, als


def test_undo_rename_round_trip(tmp_path):
    conn, pid, als = _seed(tmp_path, "old Project")
    journal = tmp_path / "journal"
    bid = run_batch(
        conn,
        [RenameProject(project_id=pid, new_dir_name="new Project", root=tmp_path)],
        actor="user",
        journal_dir=journal,
    )
    assert (tmp_path / "new Project" / "x.als").is_file()
    undo_batch(conn, journal, bid)
    assert (tmp_path / "old Project" / "x.als").is_file()
    assert not (tmp_path / "new Project").exists()
    row = conn.execute("SELECT path, parent_dir FROM projects WHERE id=?", (pid,)).fetchone()
    assert row[0] == str(tmp_path / "old Project" / "x.als")
    assert row[1] == str(tmp_path / "old Project")


def test_undo_move_round_trip(tmp_path):
    conn, pid, als = _seed(tmp_path)
    journal = tmp_path / "journal"
    bid = run_batch(
        conn,
        [MoveProject(project_id=pid, new_parent=tmp_path / "by_year" / "2025", root=tmp_path)],
        actor="user",
        journal_dir=journal,
    )
    undo_batch(conn, journal, bid)
    assert (tmp_path / "p Project" / "x.als").is_file()


def test_undo_archive_restores_parent_and_flag(tmp_path):
    conn, pid, als = _seed(tmp_path)
    journal = tmp_path / "journal"
    bid = run_batch(
        conn, [ArchiveProject(project_id=pid, root=tmp_path)], actor="user", journal_dir=journal
    )
    flag = conn.execute("SELECT is_archived FROM projects WHERE id=?", (pid,)).fetchone()[0]
    assert flag == 1
    undo_batch(conn, journal, bid)
    flag = conn.execute("SELECT is_archived FROM projects WHERE id=?", (pid,)).fetchone()[0]
    assert flag == 0
    assert (tmp_path / "p Project" / "x.als").is_file()


def test_undo_set_color_tag_restores_prior(tmp_path):
    conn, pid, als = _seed(tmp_path)
    journal = tmp_path / "journal"
    SetColorTag(project_id=pid, color=2).execute(conn)  # set baseline
    bid = run_batch(conn, [SetColorTag(project_id=pid, color=7)], actor="user", journal_dir=journal)
    assert conn.execute("SELECT color_tag FROM projects WHERE id=?", (pid,)).fetchone()[0] == 7
    undo_batch(conn, journal, bid)
    assert conn.execute("SELECT color_tag FROM projects WHERE id=?", (pid,)).fetchone()[0] == 2


def test_undo_set_tags_restores_prior_set(tmp_path):
    conn, pid, als = _seed(tmp_path)
    journal = tmp_path / "journal"
    SetTags(project_id=pid, tags=["lofi", "chillhop"]).execute(conn)
    bid = run_batch(
        conn,
        [SetTags(project_id=pid, tags=["techno", "industrial"])],
        actor="user",
        journal_dir=journal,
    )
    undo_batch(conn, journal, bid)
    rows = conn.execute(
        "SELECT t.name FROM tags t JOIN project_tags pt ON pt.tag_id=t.id "
        "WHERE pt.project_id=? ORDER BY t.name",
        (pid,),
    ).fetchall()
    assert {r[0] for r in rows} == {"lofi", "chillhop"}


def test_undo_batch_reverses_actions_in_reverse_order(tmp_path):
    conn, pid, als = _seed(tmp_path, "alpha Project")
    journal = tmp_path / "journal"
    bid = run_batch(
        conn,
        [
            SetColorTag(project_id=pid, color=3),
            RenameProject(project_id=pid, new_dir_name="bravo Project", root=tmp_path),
        ],
        actor="user",
        journal_dir=journal,
    )
    undo_batch(conn, journal, bid)
    assert (tmp_path / "alpha Project" / "x.als").is_file()
    assert conn.execute("SELECT color_tag FROM projects WHERE id=?", (pid,)).fetchone()[0] is None


def test_double_undo_refuses(tmp_path):
    """Calling undo twice on the same batch must refuse the second call."""
    conn, pid, als = _seed(tmp_path, "alpha Project")
    journal = tmp_path / "journal"
    bid = run_batch(
        conn,
        [RenameProject(project_id=pid, new_dir_name="bravo Project", root=tmp_path)],
        actor="user",
        journal_dir=journal,
    )
    undo_batch(conn, journal, bid)
    with pytest.raises(UndoError, match="already been undone"):
        undo_batch(conn, journal, bid)


def test_undo_of_undo_entry_refuses(tmp_path):
    """The journal entry written by an undo can't itself be undone."""
    conn, pid, als = _seed(tmp_path, "alpha Project")
    journal = tmp_path / "journal"
    bid = run_batch(
        conn,
        [SetColorTag(project_id=pid, color=2)],
        actor="user",
        journal_dir=journal,
    )
    undo_bid = undo_batch(conn, journal, bid)
    assert undo_bid is not None
    with pytest.raises(UndoError, match="undo entry"):
        undo_batch(conn, journal, undo_bid)


def test_undo_when_state_already_restored_is_safe(tmp_path):
    """If the user manually moved the dir back before undo, undo treats it as
    already-undone for that action (it does not crash, does not double-move)."""
    conn, pid, als = _seed(tmp_path, "alpha Project")
    journal = tmp_path / "journal"
    bid = run_batch(
        conn,
        [RenameProject(project_id=pid, new_dir_name="bravo Project", root=tmp_path)],
        actor="user",
        journal_dir=journal,
    )
    # Simulate user manually restoring the dir before clicking undo
    shutil.move(str(tmp_path / "bravo Project"), str(tmp_path / "alpha Project"))
    undo_batch(conn, journal, bid)
    assert (tmp_path / "alpha Project" / "x.als").is_file()


def test_undo_refuses_when_both_source_and_dest_present(tmp_path):
    """If a stranger directory has appeared at the original location, undo
    refuses rather than merging or overwriting."""
    conn, pid, als = _seed(tmp_path, "alpha Project")
    journal = tmp_path / "journal"
    bid = run_batch(
        conn,
        [RenameProject(project_id=pid, new_dir_name="bravo Project", root=tmp_path)],
        actor="user",
        journal_dir=journal,
    )
    # Recreate the original folder so undo would have to merge
    (tmp_path / "alpha Project").mkdir()
    with pytest.raises(UndoError, match="both source"):
        undo_batch(conn, journal, bid)
