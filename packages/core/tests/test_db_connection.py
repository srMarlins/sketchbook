from audio_core.db.connection import open_db


def test_open_db_creates_tables(tmp_path):
    conn = open_db(tmp_path / "test.db")
    rows = conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
    ).fetchall()
    names = {r[0] for r in rows}
    expected = {
        "projects",
        "project_plugins",
        "project_samples",
        "project_tags",
        "tags",
        "schema_version",
    }
    assert expected.issubset(names)


def test_open_db_creates_fts_table(tmp_path):
    conn = open_db(tmp_path / "t.db")
    rows = conn.execute("SELECT name FROM sqlite_master WHERE name='projects_fts'").fetchall()
    assert rows


def test_open_db_is_idempotent(tmp_path):
    p = tmp_path / "t.db"
    open_db(p).close()
    conn = open_db(p)  # second open should not error
    version = conn.execute("SELECT version FROM schema_version").fetchone()[0]
    assert version == 1


def test_foreign_keys_enabled(tmp_path):
    conn = open_db(tmp_path / "t.db")
    enabled = conn.execute("PRAGMA foreign_keys").fetchone()[0]
    assert enabled == 1
