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
