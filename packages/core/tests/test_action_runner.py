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
        # `../bad` contains `/` which is rejected by the filename validator
        # (also lexically escapes root). Either error is acceptable.
        RenameProject(project_id=pid, new_dir_name="../bad", root=tmp_path),
    ]
    with pytest.raises((PermissionError, ValueError)):
        run_batch(conn, actions, actor="user", journal_dir=journal)
    color = conn.execute("SELECT color_tag FROM projects WHERE id=?", (pid,)).fetchone()[0]
    assert color is None  # validation prevented the SetColorTag from running
    assert not list(journal.glob("*.json"))


def test_run_batch_partial_failure_journals_completed_actions(tmp_path):
    """If action #2 raises during execute, action #1 must still be journaled
    so the user can see and undo what landed."""
    conn, pid, als = _seed(tmp_path)
    journal = tmp_path / "journal"

    class _Boom:
        def validate(self, conn): pass
        def execute(self, conn): raise RuntimeError("disk full simulation")

    with pytest.raises(RuntimeError, match="disk full"):
        run_batch(
            conn,
            [SetColorTag(project_id=pid, color=4), _Boom()],
            actor="user",
            journal_dir=journal,
            intent=[
                {"type": "SetColorTag", "args": {"project_id": pid, "color": 4}},
                {"type": "_Boom", "args": {}},
            ],
        )
    # SetColorTag landed and was journaled
    color = conn.execute("SELECT color_tag FROM projects WHERE id=?", (pid,)).fetchone()[0]
    assert color == 4
    files = list(journal.glob("*.json"))
    assert len(files) == 1
    payload = read_batch(journal, files[0].stem)
    assert payload["status"] == "partial"
    assert len(payload["actions"]) == 1
    assert payload["actions"][0]["type"] == "SetColorTag"
    assert payload["failed_at_index"] == 1
    assert "disk full" in payload["error"]
    # No leftover pending file
    assert not list((journal / "pending").glob("*.json")) if (journal / "pending").exists() else True


def test_run_batch_writes_pending_then_finalizes(tmp_path):
    """Successful batch leaves no trace under pending/, only the final entry."""
    conn, pid, als = _seed(tmp_path)
    journal = tmp_path / "journal"
    bid = run_batch(
        conn,
        [SetColorTag(project_id=pid, color=2)],
        actor="user",
        journal_dir=journal,
    )
    final = journal / f"{bid}.json"
    pending = journal / "pending" / f"{bid}.json"
    assert final.exists()
    assert not pending.exists()
    assert read_batch(journal, bid)["status"] == "complete"


def test_reconcile_pending_promotes_orphaned_intent(tmp_path):
    """A pending file with no matching final file (process crashed) gets promoted
    to status='interrupted' so the user can see something happened."""
    from audio_core.journal.manifest import (
        list_batches,
        reconcile_pending,
        write_pending,
    )

    journal = tmp_path / "journal"
    bid = write_pending(
        journal,
        actor="user",
        intent=[{"type": "SetColorTag", "args": {"project_id": 1, "color": 4}}],
    )
    reconciled = reconcile_pending(journal)
    assert bid in reconciled
    final = next(b for b in list_batches(journal) if b["batch_id"] == bid)
    assert final["status"] == "interrupted"
    assert final["intent"][0]["type"] == "SetColorTag"
    # Pending file is gone
    assert not (journal / "pending" / f"{bid}.json").exists()
