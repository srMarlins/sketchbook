import time

from audio_core.db.connection import open_db
from audio_core.db.projects import upsert_project
from audio_core.dedup import find_duplicates
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
    pid = upsert_project(
        conn, path="/c/x.als", name="x", parent_dir="/c",
        file_hash=None, last_modified=1000.0, meta=ProjectMetadata(),
    )
    assert pid is not None
    groups = find_duplicates(conn)
    assert len(groups) == 1
    assert all(m["file_hash"] == "h" for m in [groups[0].keeper, *groups[0].losers])
