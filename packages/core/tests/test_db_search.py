import time

from audio_core.db.connection import open_db
from audio_core.db.projects import search_projects, upsert_project
from audio_core.parser.model import PluginRef, ProjectMetadata, SampleRef


def _seed(conn, *, path, name, tempo, plugins=(), samples=(), archived=False):
    pid = upsert_project(
        conn,
        path=path,
        name=name,
        parent_dir="/x",
        file_hash=name,
        last_modified=time.time(),
        meta=ProjectMetadata(
            tempo=tempo,
            plugins=[PluginRef(name=p, plugin_type="vst3") for p in plugins],
            samples=[SampleRef(path=s) for s in samples],
        ),
    )
    if archived:
        conn.execute("UPDATE projects SET is_archived=1 WHERE id=?", (pid,))
        conn.commit()
    return pid


def test_search_by_plugin_name(tmp_path):
    conn = open_db(tmp_path / "t.db")
    _seed(conn, path="/x/a.als", name="a", tempo=140.0, plugins=["Pro-Q 3"])
    _seed(conn, path="/x/b.als", name="b", tempo=90.0, plugins=["Diva"])
    rows = search_projects(conn, query="Pro-Q")
    assert len(rows) == 1 and rows[0]["name"] == "a"


def test_search_by_tempo_range(tmp_path):
    conn = open_db(tmp_path / "t.db")
    _seed(conn, path="/x/a.als", name="a", tempo=140.0)
    _seed(conn, path="/x/b.als", name="b", tempo=90.0)
    _seed(conn, path="/x/c.als", name="c", tempo=128.0)
    rows = search_projects(conn, tempo_min=120, tempo_max=150)
    assert {r["name"] for r in rows} == {"a", "c"}


def test_search_by_sample_filename(tmp_path):
    conn = open_db(tmp_path / "t.db")
    _seed(conn, path="/x/a.als", name="a", tempo=120.0, samples=["C:/x/kick_808.wav"])
    _seed(conn, path="/x/b.als", name="b", tempo=120.0, samples=["C:/x/snare.wav"])
    rows = search_projects(conn, query="kick_808")
    assert len(rows) == 1 and rows[0]["name"] == "a"


def test_search_excludes_archived_by_default(tmp_path):
    conn = open_db(tmp_path / "t.db")
    _seed(conn, path="/x/a.als", name="a", tempo=120.0)
    _seed(conn, path="/x/b.als", name="b", tempo=120.0, archived=True)
    rows = search_projects(conn)
    assert {r["name"] for r in rows} == {"a"}


def test_search_archived_only_when_requested(tmp_path):
    conn = open_db(tmp_path / "t.db")
    _seed(conn, path="/x/a.als", name="a", tempo=120.0)
    _seed(conn, path="/x/b.als", name="b", tempo=120.0, archived=True)
    rows = search_projects(conn, archived=True)
    assert {r["name"] for r in rows} == {"b"}


def test_search_archived_none_returns_both(tmp_path):
    conn = open_db(tmp_path / "t.db")
    _seed(conn, path="/x/a.als", name="a", tempo=120.0)
    _seed(conn, path="/x/b.als", name="b", tempo=120.0, archived=True)
    rows = search_projects(conn, archived=None)
    assert {r["name"] for r in rows} == {"a", "b"}


def _set_effort(conn, pid: int, score: int) -> None:
    conn.execute("UPDATE projects SET effort_score=? WHERE id=?", (score, pid))
    conn.commit()


def test_search_min_effort(tmp_path):
    conn = open_db(tmp_path / "t.db")
    a = _seed(conn, path="/x/a.als", name="a", tempo=120.0)
    b = _seed(conn, path="/x/b.als", name="b", tempo=120.0)
    _set_effort(conn, a, 80)
    _set_effort(conn, b, 20)
    rows = search_projects(conn, min_effort=50)
    assert {r["name"] for r in rows} == {"a"}


def test_search_max_effort(tmp_path):
    conn = open_db(tmp_path / "t.db")
    a = _seed(conn, path="/x/a.als", name="a", tempo=120.0)
    b = _seed(conn, path="/x/b.als", name="b", tempo=120.0)
    _set_effort(conn, a, 80)
    _set_effort(conn, b, 20)
    rows = search_projects(conn, max_effort=30)
    assert {r["name"] for r in rows} == {"b"}


def test_search_order_by_effort_desc(tmp_path):
    conn = open_db(tmp_path / "t.db")
    a = _seed(conn, path="/x/a.als", name="a", tempo=120.0)
    b = _seed(conn, path="/x/b.als", name="b", tempo=120.0)
    c = _seed(conn, path="/x/c.als", name="c", tempo=120.0)
    _set_effort(conn, a, 50)
    _set_effort(conn, b, 90)
    _set_effort(conn, c, 10)
    rows = search_projects(conn, order_by="effort", order_dir="desc")
    assert [r["name"] for r in rows] == ["b", "a", "c"]


def test_search_order_by_name_asc(tmp_path):
    conn = open_db(tmp_path / "t.db")
    _seed(conn, path="/x/c.als", name="c", tempo=120.0)
    _seed(conn, path="/x/a.als", name="a", tempo=120.0)
    _seed(conn, path="/x/b.als", name="b", tempo=120.0)
    rows = search_projects(conn, order_by="name", order_dir="asc")
    assert [r["name"] for r in rows] == ["a", "b", "c"]
