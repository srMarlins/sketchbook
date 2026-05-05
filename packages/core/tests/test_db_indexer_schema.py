from audio_core.db.connection import open_db


def test_is_missing_column_present_with_default(tmp_path):
    conn = open_db(tmp_path / "c.db")
    cols = {r[1]: r for r in conn.execute("PRAGMA table_info(projects)").fetchall()}
    assert "is_missing" in cols
    # `notnull` is field 3, `dflt_value` is field 4 in PRAGMA table_info.
    assert cols["is_missing"][3] == 1  # NOT NULL
    assert cols["is_missing"][4] == "0"


def test_last_seen_column_present(tmp_path):
    conn = open_db(tmp_path / "c.db")
    cols = {r[1] for r in conn.execute("PRAGMA table_info(projects)").fetchall()}
    assert "last_seen" in cols


def test_indexer_state_table_exists_with_singleton_check(tmp_path):
    conn = open_db(tmp_path / "c.db")
    rows = conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='indexer_state'"
    ).fetchall()
    assert len(rows) == 1
    cols = {r[1] for r in conn.execute("PRAGMA table_info(indexer_state)").fetchall()}
    assert cols == {"id", "job_kind", "job_path", "total", "done", "started_at", "pid"}
    # Singleton constraint: id must be 1.
    conn.execute("INSERT INTO indexer_state (id) VALUES (1)")
    import pytest
    with pytest.raises(Exception):
        conn.execute("INSERT INTO indexer_state (id) VALUES (2)")


def test_existing_db_migrates_is_missing_to_zero(tmp_path):
    """Pre-existing rows must have is_missing=0 after the additive ALTER."""
    db = tmp_path / "c.db"
    conn = open_db(db)
    conn.execute(
        "INSERT INTO projects(path, name, parent_dir, last_modified, last_scanned) "
        "VALUES (?, ?, ?, ?, ?)",
        ("/x.als", "x", "/", 0.0, 0.0),
    )
    conn.commit()
    conn.close()
    # Re-open: migration is idempotent, columns stay.
    conn2 = open_db(db)
    row = conn2.execute(
        "SELECT is_missing, last_seen FROM projects WHERE path='/x.als'"
    ).fetchone()
    assert row[0] == 0
    assert row[1] is None


def test_migration_idempotent(tmp_path):
    db = tmp_path / "c.db"
    open_db(db).close()
    open_db(db).close()  # second open must not raise
    conn = open_db(db)
    cols = {r[1] for r in conn.execute("PRAGMA table_info(projects)").fetchall()}
    assert {"is_missing", "last_seen"} <= cols
