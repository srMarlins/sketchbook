import time

from audio_core.db.connection import open_db
from audio_core.db.projects import get_project_by_path, upsert_project
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
