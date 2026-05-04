def test_archive_already_archived_is_safe(tmp_path):
    """Re-archiving a row that's already is_archived=1 must not raise.
    Dedup proposals can race with manual archives; the runner must tolerate it."""
    import shutil
    from pathlib import Path
    from audio_core.actions.archive import ArchiveProject
    from audio_core.actions.runner import run_batch
    from audio_core.db.connection import open_db
    from audio_core.scanner.scan import scan_one

    fixtures = Path(__file__).parent / "fixtures"
    root = tmp_path / "root"
    (root / "Projects" / "p Project").mkdir(parents=True)
    shutil.copy(fixtures / "tiny.als", root / "Projects" / "p Project" / "x.als")
    conn = open_db(root / "data" / "catalog.db")
    pid = scan_one(conn, root / "Projects" / "p Project" / "x.als")
    run_batch(
        conn,
        [ArchiveProject(project_id=pid, root=root / "Projects")],
        actor="test",
        journal_dir=root / "data" / "journal",
    )
    # Second archive is a no-op, not a crash.
    run_batch(
        conn,
        [ArchiveProject(project_id=pid, root=root / "Projects")],
        actor="test",
        journal_dir=root / "data" / "journal",
    )
    assert conn.execute(
        "SELECT is_archived FROM projects WHERE id=?", (pid,)
    ).fetchone()[0] == 1


def test_undo_of_idempotent_noop_does_not_unarchive(tmp_path):
    """Undoing a batch whose only entry is a noop ArchiveProject must leave
    is_archived=1 — the noop guard in undo.py is what enforces this."""
    import shutil
    from pathlib import Path
    from audio_core.actions.archive import ArchiveProject
    from audio_core.actions.runner import run_batch
    from audio_core.actions.undo import undo_batch
    from audio_core.db.connection import open_db
    from audio_core.scanner.scan import scan_one

    fixtures = Path(__file__).parent / "fixtures"
    root = tmp_path / "root"
    (root / "Projects" / "p Project").mkdir(parents=True)
    shutil.copy(fixtures / "tiny.als", root / "Projects" / "p Project" / "x.als")
    conn = open_db(root / "data" / "catalog.db")
    pid = scan_one(conn, root / "Projects" / "p Project" / "x.als")
    journal = root / "data" / "journal"
    run_batch(conn, [ArchiveProject(project_id=pid, root=root / "Projects")], actor="test", journal_dir=journal)
    second_bid = run_batch(
        conn, [ArchiveProject(project_id=pid, root=root / "Projects")], actor="test", journal_dir=journal
    )
    undo_batch(conn, journal, second_bid)
    assert conn.execute(
        "SELECT is_archived FROM projects WHERE id=?", (pid,)
    ).fetchone()[0] == 1
