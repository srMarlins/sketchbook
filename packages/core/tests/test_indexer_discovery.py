import shutil
from pathlib import Path

from audio_core.indexer.discovery import Discovered, Plan, discover, plan


def test_discover_yields_als_with_stat(tmp_path):
    fixtures = Path(__file__).parent / "fixtures"
    (tmp_path / "p Project").mkdir()
    f = tmp_path / "p Project" / "x.als"
    shutil.copy(fixtures / "tiny.als", f)
    rows = list(discover(tmp_path))
    assert len(rows) == 1
    assert rows[0].path.endswith("x.als")
    assert rows[0].size == f.stat().st_size
    assert rows[0].mtime > 0


def test_discover_skips_non_als_and_unreadable(tmp_path):
    fixtures = Path(__file__).parent / "fixtures"
    (tmp_path / "a Project").mkdir()
    shutil.copy(fixtures / "tiny.als", tmp_path / "a Project" / "x.als")
    (tmp_path / "a Project" / "notes.txt").write_text("hi")  # not .als
    rows = list(discover(tmp_path))
    assert {Path(r.path).name for r in rows} == {"x.als"}


def test_plan_bucketizes_new_unchanged_changed():
    catalog = {
        "/a.als": {"mtime": 100.0, "size": 10},
        "/c.als": {"mtime": 100.0, "size": 10},
    }
    discovered = [
        Discovered(path="/b.als", mtime=200.0, size=20),  # NEW
        Discovered(path="/a.als", mtime=100.0, size=10),  # UNCHANGED
        Discovered(path="/c.als", mtime=100.0, size=99),  # CHANGED (size differs)
    ]
    p = plan(catalog, discovered)
    assert {r.path for r in p.new} == {"/b.als"}
    assert {r.path for r in p.unchanged} == {"/a.als"}
    assert {r.path for r in p.changed} == {"/c.als"}
    assert p.missing == []


def test_plan_changed_when_mtime_only_differs():
    catalog = {"/a.als": {"mtime": 100.0, "size": 10}}
    discovered = [Discovered(path="/a.als", mtime=200.0, size=10)]
    p = plan(catalog, discovered)
    assert [r.path for r in p.changed] == ["/a.als"]
    assert p.unchanged == []


def test_plan_detects_missing():
    catalog = {"/gone.als": {"mtime": 1, "size": 1}}
    p = plan(catalog, [])
    assert p.missing == ["/gone.als"]


def test_plan_handles_empty_inputs():
    assert plan({}, []) == Plan(new=[], changed=[], unchanged=[], missing=[])
