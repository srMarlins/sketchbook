import time

from audio_core.db.connection import open_db
from audio_core.db.projects import (
    get_project_by_path,
    search_projects,
    upsert_failed_parse,
    upsert_project,
)
from audio_core.parser.model import PluginRef, ProjectMetadata, SampleRef


def test_upsert_inserts_then_updates(tmp_path):
    conn = open_db(tmp_path / "t.db")
    meta = ProjectMetadata(
        tempo=120.0,
        time_sig_numerator=4,
        time_sig_denominator=4,
        track_count=3,
        audio_track_count=1,
        midi_track_count=2,
        live_version="11.3.13",
        plugins=[PluginRef(name="Pro-Q 3", plugin_type="vst3", track_name="Master")],
        samples=[SampleRef(path="C:/s/kick.wav")],
    )
    pid = upsert_project(
        conn,
        path=str(tmp_path / "p.als"),
        name="p",
        parent_dir=str(tmp_path),
        file_hash="h1",
        last_modified=time.time(),
        meta=meta,
    )
    assert pid > 0
    row = get_project_by_path(conn, str(tmp_path / "p.als"))
    assert row is not None
    assert row["tempo"] == 120.0
    assert row["live_version"] == "11.3.13"

    meta2 = meta.model_copy(update={"tempo": 128.0, "plugins": []})
    pid2 = upsert_project(
        conn,
        path=str(tmp_path / "p.als"),
        name="p",
        parent_dir=str(tmp_path),
        file_hash="h2",
        last_modified=time.time(),
        meta=meta2,
    )
    assert pid2 == pid  # same row, updated
    row2 = get_project_by_path(conn, str(tmp_path / "p.als"))
    assert row2["tempo"] == 128.0
    assert row2["file_hash"] == "h2"
    plugins = conn.execute(
        "SELECT COUNT(*) FROM project_plugins WHERE project_id=?", (pid,)
    ).fetchone()[0]
    assert plugins == 0  # rebuilt from new meta


def test_upsert_persists_plugins_and_samples(tmp_path):
    conn = open_db(tmp_path / "t.db")
    meta = ProjectMetadata(
        plugins=[
            PluginRef(name="Diva", plugin_type="vst3", track_name="Lead"),
            PluginRef(name="Eq8", plugin_type="ableton-native", track_name="Lead"),
        ],
        samples=[SampleRef(path="C:/x/a.wav"), SampleRef(path="C:/x/b.wav")],
    )
    pid = upsert_project(
        conn,
        path=str(tmp_path / "p.als"),
        name="p",
        parent_dir=str(tmp_path),
        file_hash="h",
        last_modified=time.time(),
        meta=meta,
    )
    plugin_rows = conn.execute(
        "SELECT plugin_name, plugin_type, track_name FROM project_plugins WHERE project_id=?",
        (pid,),
    ).fetchall()
    assert len(plugin_rows) == 2
    assert {r[0] for r in plugin_rows} == {"Diva", "Eq8"}
    sample_rows = conn.execute(
        "SELECT sample_path FROM project_samples WHERE project_id=?", (pid,)
    ).fetchall()
    assert {r[0] for r in sample_rows} == {"C:/x/a.wav", "C:/x/b.wav"}


def test_upsert_refreshes_fts(tmp_path):
    conn = open_db(tmp_path / "t.db")
    meta = ProjectMetadata(
        plugins=[PluginRef(name="Crystallizer", plugin_type="vst3")],
        samples=[SampleRef(path="C:/x/kick_808.wav")],
    )
    pid = upsert_project(
        conn,
        path=str(tmp_path / "tropical_house.als"),
        name="tropical_house",
        parent_dir=str(tmp_path),
        file_hash="h",
        last_modified=time.time(),
        meta=meta,
    )
    rows = conn.execute(
        "SELECT rowid FROM projects_fts WHERE projects_fts MATCH ?", ("Crystallizer",)
    ).fetchall()
    assert any(r[0] == pid for r in rows)
    rows = conn.execute(
        "SELECT rowid FROM projects_fts WHERE projects_fts MATCH ?", ("kick_808",)
    ).fetchall()
    assert any(r[0] == pid for r in rows)


def test_upsert_marks_missing_samples(tmp_path):
    """is_missing reflects whether the sample exists on disk after upsert."""
    conn = open_db(tmp_path / "t.db")
    real = tmp_path / "Samples"
    real.mkdir()
    real_sample = real / "kick.wav"
    real_sample.write_bytes(b"riff")
    meta = ProjectMetadata(
        samples=[
            SampleRef(path=str(real_sample)),  # absolute, exists
            SampleRef(path=str(tmp_path / "missing.wav")),  # absolute, missing
            SampleRef(path="Samples/kick.wav"),  # relative to parent_dir, exists
            SampleRef(path="Samples/ghost.wav"),  # relative, missing
        ],
    )
    pid = upsert_project(
        conn,
        path=str(tmp_path / "p.als"),
        name="p",
        parent_dir=str(tmp_path),
        file_hash="h",
        last_modified=time.time(),
        meta=meta,
    )
    rows = conn.execute(
        "SELECT sample_path, is_missing FROM project_samples WHERE project_id=? "
        "ORDER BY sample_path",
        (pid,),
    ).fetchall()
    by_path = {r[0]: r[1] for r in rows}
    assert by_path[str(real_sample)] == 0
    assert by_path[str(tmp_path / "missing.wav")] == 1
    assert by_path["Samples/kick.wav"] == 0
    assert by_path["Samples/ghost.wav"] == 1


def test_search_projects_includes_missing_sample_count(tmp_path):
    conn = open_db(tmp_path / "t.db")
    meta = ProjectMetadata(
        samples=[
            SampleRef(path=str(tmp_path / "ghost1.wav")),
            SampleRef(path=str(tmp_path / "ghost2.wav")),
        ],
    )
    pid = upsert_project(
        conn,
        path=str(tmp_path / "p.als"),
        name="p",
        parent_dir=str(tmp_path),
        file_hash="h",
        last_modified=time.time(),
        meta=meta,
    )
    rows = search_projects(conn)
    assert len(rows) == 1
    assert rows[0]["id"] == pid
    assert rows[0]["missing_sample_count"] == 2


def test_upsert_failed_parse_creates_stub_row(tmp_path):
    conn = open_db(tmp_path / "t.db")
    pid = upsert_failed_parse(
        conn,
        path=str(tmp_path / "broken.als"),
        name="broken",
        parent_dir=str(tmp_path),
        file_hash="bh",
        last_modified=time.time(),
        error="GzipError: not a gzip file",
    )
    row = get_project_by_path(conn, str(tmp_path / "broken.als"))
    assert row is not None
    assert row["id"] == pid
    assert row["parse_status"] == "failed"
    assert "GzipError" in row["parse_error"]
    assert row["tempo"] is None
    assert row["track_count"] == 0


def test_failed_then_successful_parse_clears_status(tmp_path):
    """Re-parsing a previously broken project successfully should reset flags."""
    conn = open_db(tmp_path / "t.db")
    path = str(tmp_path / "p.als")
    upsert_failed_parse(
        conn,
        path=path,
        name="p",
        parent_dir=str(tmp_path),
        file_hash="h0",
        last_modified=time.time(),
        error="boom",
    )
    upsert_project(
        conn,
        path=path,
        name="p",
        parent_dir=str(tmp_path),
        file_hash="h1",
        last_modified=time.time(),
        meta=ProjectMetadata(tempo=120.0),
    )
    row = get_project_by_path(conn, path)
    assert row["parse_status"] == "ok"
    assert row["parse_error"] is None
    assert row["tempo"] == 120.0


def test_search_projects_broken_filter(tmp_path):
    conn = open_db(tmp_path / "t.db")
    # Project A: clean (no missing samples, parsed ok)
    a = upsert_project(
        conn,
        path=str(tmp_path / "a.als"),
        name="a",
        parent_dir=str(tmp_path),
        file_hash="ha",
        last_modified=time.time(),
        meta=ProjectMetadata(),
    )
    # Project B: missing sample
    b = upsert_project(
        conn,
        path=str(tmp_path / "b.als"),
        name="b",
        parent_dir=str(tmp_path),
        file_hash="hb",
        last_modified=time.time(),
        meta=ProjectMetadata(samples=[SampleRef(path=str(tmp_path / "ghost.wav"))]),
    )
    # Project C: failed parse
    c = upsert_failed_parse(
        conn,
        path=str(tmp_path / "c.als"),
        name="c",
        parent_dir=str(tmp_path),
        file_hash="hc",
        last_modified=time.time(),
        error="boom",
    )

    broken_rows = search_projects(conn, broken=True)
    broken_ids = {r["id"] for r in broken_rows}
    assert broken_ids == {b, c}

    healthy_rows = search_projects(conn, broken=False)
    healthy_ids = {r["id"] for r in healthy_rows}
    assert healthy_ids == {a}

    all_rows = search_projects(conn, broken=None)
    all_ids = {r["id"] for r in all_rows}
    assert all_ids == {a, b, c}
