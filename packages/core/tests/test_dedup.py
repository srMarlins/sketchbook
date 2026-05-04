import time

from audio_core.db.connection import open_db
from audio_core.db.projects import upsert_project
from audio_core.dedup import build_archive_proposal, find_duplicates
from audio_core.parser.model import ProjectMetadata


def _seed(conn, *, path, file_hash, mtime, name=None, archived=False):
    pid = upsert_project(
        conn,
        path=path,
        name=name or path.rsplit("/", 1)[-1].removesuffix(".als"),
        parent_dir=path.rsplit("/", 1)[0],
        file_hash=file_hash,
        last_modified=mtime,
        meta=ProjectMetadata(),
    )
    if archived:
        conn.execute("UPDATE projects SET is_archived=1 WHERE id=?", (pid,))
        conn.commit()
    return pid


def test_empty_db(tmp_path):
    conn = open_db(tmp_path / "c.db")
    assert find_duplicates(conn) == []


def test_no_duplicates(tmp_path):
    conn = open_db(tmp_path / "c.db")
    _seed(conn, path="/a/x.als", file_hash="h1", mtime=time.time())
    _seed(conn, path="/b/y.als", file_hash="h2", mtime=time.time())
    assert find_duplicates(conn) == []


def test_two_way_dup_picks_newest_mtime(tmp_path):
    conn = open_db(tmp_path / "c.db")
    older = _seed(conn, path="/a/x.als", file_hash="h", mtime=1000.0)
    newer = _seed(conn, path="/b/x.als", file_hash="h", mtime=2000.0)
    groups = find_duplicates(conn)
    assert len(groups) == 1
    assert groups[0].keeper["id"] == newer
    assert [l["id"] for l in groups[0].losers] == [older]


def test_mtime_tie_picks_shortest_path(tmp_path):
    conn = open_db(tmp_path / "c.db")
    long_id = _seed(conn, path="/a/very/long/path/x.als", file_hash="h", mtime=1000.0)
    short_id = _seed(conn, path="/b/x.als", file_hash="h", mtime=1000.0)
    groups = find_duplicates(conn)
    assert groups[0].keeper["id"] == short_id
    assert [l["id"] for l in groups[0].losers] == [long_id]


def test_archived_never_chosen_as_keeper(tmp_path):
    conn = open_db(tmp_path / "c.db")
    arch_newer = _seed(conn, path="/arch/x.als", file_hash="h", mtime=2000.0, archived=True)
    live_older = _seed(conn, path="/live/x.als", file_hash="h", mtime=1000.0)
    groups = find_duplicates(conn)
    assert groups[0].keeper["id"] == live_older
    assert [l["id"] for l in groups[0].losers] == [arch_newer]


def test_all_archived_group_still_reported(tmp_path):
    conn = open_db(tmp_path / "c.db")
    a = _seed(conn, path="/arch1/x.als", file_hash="h", mtime=2000.0, archived=True)
    b = _seed(conn, path="/arch2/x.als", file_hash="h", mtime=1000.0, archived=True)
    groups = find_duplicates(conn)
    assert len(groups) == 1
    assert {groups[0].keeper["id"], *(l["id"] for l in groups[0].losers)} == {a, b}


def test_null_file_hash_excluded(tmp_path):
    conn = open_db(tmp_path / "c.db")
    _seed(conn, path="/a/x.als", file_hash="h", mtime=1000.0)
    _seed(conn, path="/b/x.als", file_hash="h", mtime=1000.0)
    # Two NULL-hash rows would form a phantom group via GROUP BY (SQLite treats
    # NULLs as equal for grouping). The filter must drop them.
    upsert_project(
        conn, path="/c/x.als", name="x", parent_dir="/c",
        file_hash=None, last_modified=1000.0, meta=ProjectMetadata(),
    )
    upsert_project(
        conn, path="/d/y.als", name="y", parent_dir="/d",
        file_hash=None, last_modified=1000.0, meta=ProjectMetadata(),
    )
    groups = find_duplicates(conn)
    assert len(groups) == 1
    assert groups[0].file_hash == "h"


def test_build_archive_proposal_one_action_per_loser(tmp_path):
    conn = open_db(tmp_path / "c.db")
    keeper = _seed(conn, path="/k/x.als", file_hash="h", mtime=2000.0)
    l1 = _seed(conn, path="/a/x.als", file_hash="h", mtime=1000.0)
    l2 = _seed(conn, path="/b/x.als", file_hash="h", mtime=900.0)
    actions = build_archive_proposal(find_duplicates(conn))
    assert {a["args"]["project_id"] for a in actions} == {l1, l2}
    assert all(a["type"] == "ArchiveProject" for a in actions)
    assert keeper not in [a["args"]["project_id"] for a in actions]


def test_build_archive_proposal_skips_all_archived_groups(tmp_path):
    conn = open_db(tmp_path / "c.db")
    _seed(conn, path="/a/x.als", file_hash="h", mtime=2000.0, archived=True)
    _seed(conn, path="/b/x.als", file_hash="h", mtime=1000.0, archived=True)
    assert build_archive_proposal(find_duplicates(conn)) == []


def test_round_trip_proposal_archives_losers(tmp_path):
    """End-to-end at the core layer: build_archive_proposal -> instantiate
    ArchiveProject -> run_batch. Losers should end up with is_archived=1."""
    import shutil
    from pathlib import Path

    from audio_core.actions.archive import ArchiveProject
    from audio_core.actions.runner import run_batch
    from audio_core.scanner.scan import scan_one

    fixtures = Path(__file__).parent / "fixtures"
    root = tmp_path / "root"
    (root / "Projects" / "keep Project").mkdir(parents=True)
    (root / "Projects" / "drop Project").mkdir(parents=True)
    shutil.copy(fixtures / "tiny.als", root / "Projects" / "keep Project" / "x.als")
    shutil.copy(fixtures / "tiny.als", root / "Projects" / "drop Project" / "x.als")

    conn = open_db(root / "data" / "catalog.db")
    keep_id = scan_one(conn, root / "Projects" / "keep Project" / "x.als")
    drop_id = scan_one(conn, root / "Projects" / "drop Project" / "x.als")
    # Force keeper to win: bump its last_modified.
    conn.execute(
        "UPDATE projects SET last_modified=? WHERE id=?", (9_999_999_999.0, keep_id)
    )
    conn.commit()

    actions_json = build_archive_proposal(find_duplicates(conn))
    assert {a["args"]["project_id"] for a in actions_json} == {drop_id}

    actions = [
        ArchiveProject(project_id=a["args"]["project_id"], root=root / "Projects")
        for a in actions_json
    ]
    run_batch(conn, actions, actor="test", journal_dir=root / "data" / "journal")

    flag = lambda pid: conn.execute(
        "SELECT is_archived FROM projects WHERE id=?", (pid,)
    ).fetchone()[0]
    assert flag(drop_id) == 1
    assert flag(keep_id) == 0
