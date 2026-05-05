from audio_core.db.connection import open_db


def test_samples_table_present(tmp_path):
    conn = open_db(tmp_path / "c.db")
    cols = {r[1] for r in conn.execute("PRAGMA table_info(samples)").fetchall()}
    assert {"id", "path", "filename", "size_bytes", "mtime", "parent_dir"} <= cols


def test_project_samples_has_size_bytes(tmp_path):
    conn = open_db(tmp_path / "c.db")
    cols = {r[1] for r in conn.execute("PRAGMA table_info(project_samples)").fetchall()}
    assert "size_bytes" in cols


def test_samples_indexes_present(tmp_path):
    conn = open_db(tmp_path / "c.db")
    idx = {r[1] for r in conn.execute("PRAGMA index_list(samples)").fetchall()}
    assert "idx_samples_filename_size" in idx
    assert "idx_samples_parent" in idx


def test_migration_idempotent(tmp_path):
    db = tmp_path / "c.db"
    open_db(db).close()
    conn = open_db(db)
    cols = {r[1] for r in conn.execute("PRAGMA table_info(samples)").fetchall()}
    assert "filename" in cols
