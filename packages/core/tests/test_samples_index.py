from audio_core.db.connection import open_db
from audio_core.samples import (
    SampleRow,
    delete_sample,
    find_by_filename,
    upsert_sample,
)


def test_upsert_inserts(tmp_path):
    conn = open_db(tmp_path / "c.db")
    p = tmp_path / "a.wav"
    p.write_bytes(b"abc")
    upsert_sample(conn, p)
    rows = list(conn.execute("SELECT path, filename, size_bytes FROM samples"))
    assert rows == [(str(p.resolve()), "a.wav", 3)]


def test_upsert_updates(tmp_path):
    conn = open_db(tmp_path / "c.db")
    p = tmp_path / "a.wav"
    p.write_bytes(b"abc")
    upsert_sample(conn, p)
    p.write_bytes(b"abcdef")
    upsert_sample(conn, p)
    row = conn.execute("SELECT size_bytes FROM samples WHERE path=?", (str(p.resolve()),)).fetchone()
    assert row[0] == 6


def test_find_by_filename(tmp_path):
    conn = open_db(tmp_path / "c.db")
    a = tmp_path / "kick.wav"
    a.write_bytes(b"x")
    b = tmp_path / "sub" / "kick.wav"
    b.parent.mkdir()
    b.write_bytes(b"yy")
    c = tmp_path / "snare.wav"
    c.write_bytes(b"z")
    upsert_sample(conn, a)
    upsert_sample(conn, b)
    upsert_sample(conn, c)
    matches = find_by_filename(conn, "kick.wav")
    assert {m.path for m in matches} == {str(a.resolve()), str(b.resolve())}
    assert all(isinstance(m, SampleRow) for m in matches)


def test_delete_sample(tmp_path):
    conn = open_db(tmp_path / "c.db")
    p = tmp_path / "a.wav"
    p.write_bytes(b"x")
    upsert_sample(conn, p)
    delete_sample(conn, p)
    assert conn.execute("SELECT COUNT(*) FROM samples").fetchone()[0] == 0
