from audio_core.db.connection import open_db


def test_macpath_columns_present(tmp_path):
    conn = open_db(tmp_path / "c.db")
    cols = {r[1] for r in conn.execute("PRAGMA table_info(projects)").fetchall()}
    assert "mac_paths_count" in cols
    assert "has_project_info" in cols


def test_migration_is_idempotent(tmp_path):
    db = tmp_path / "c.db"
    open_db(db).close()
    # Second open must not raise on duplicate-column ALTER.
    conn = open_db(db)
    cols = {r[1] for r in conn.execute("PRAGMA table_info(projects)").fetchall()}
    assert "mac_paths_count" in cols and "has_project_info" in cols


def test_file_size_bytes_column_present(tmp_path):
    from audio_core.db.connection import open_db
    conn = open_db(tmp_path / "c.db")
    cols = {r[1] for r in conn.execute("PRAGMA table_info(projects)").fetchall()}
    assert "file_size_bytes" in cols


def test_existing_rows_get_null_defaults(tmp_path):
    db = tmp_path / "c.db"
    conn = open_db(db)
    # Simulate a pre-migration insert (raw SQL, bypassing upsert_project's new fields).
    conn.execute(
        "INSERT INTO projects(path, name, parent_dir, last_modified, last_scanned) VALUES (?,?,?,?,?)",
        ("/x.als", "x", "/", 0.0, 0.0),
    )
    conn.commit()
    conn.close()
    conn2 = open_db(db)  # re-open triggers migration if it weren't already run
    row = conn2.execute(
        "SELECT mac_paths_count, has_project_info FROM projects WHERE path='/x.als'"
    ).fetchone()
    assert row[0] is None
    assert row[1] is None
