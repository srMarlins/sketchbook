import shutil
from pathlib import Path

from audio_cli.main import app
from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_one
from typer.testing import CliRunner

FIX = Path(__file__).parents[2] / "core" / "tests" / "fixtures"


def _seed(tmp_path: Path, dir_name: str = "p Project"):
    """Set up a tmp Projects/ root with one .als file scanned into the catalog."""
    proj_root = tmp_path / "Projects"
    proj = proj_root / dir_name
    proj.mkdir(parents=True)
    als = proj / "x.als"
    shutil.copy(FIX / "tiny.als", als)
    db = tmp_path / "data" / "catalog.db"
    conn = open_db(db)
    pid = scan_one(conn, als)
    return pid


def test_rename_then_undo(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed(tmp_path, "old Project")
    runner = CliRunner()
    res = runner.invoke(app, ["rename", str(pid), "new Project"])
    assert res.exit_code == 0, res.stdout
    assert (tmp_path / "Projects" / "new Project" / "x.als").is_file()
    res = runner.invoke(app, ["undo", "last"])
    assert res.exit_code == 0
    assert (tmp_path / "Projects" / "old Project" / "x.als").is_file()


def test_color_command(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed(tmp_path)
    res = CliRunner().invoke(app, ["color", str(pid), "5"])
    assert res.exit_code == 0
    db = tmp_path / "data" / "catalog.db"
    conn = open_db(db)
    assert conn.execute("SELECT color_tag FROM projects WHERE id=?", (pid,)).fetchone()[0] == 5


def test_tag_command(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed(tmp_path)
    res = CliRunner().invoke(app, ["tag", str(pid), "lofi", "chillhop"])
    assert res.exit_code == 0
    conn = open_db(tmp_path / "data" / "catalog.db")
    rows = conn.execute(
        "SELECT t.name FROM tags t JOIN project_tags pt ON pt.tag_id=t.id "
        "WHERE pt.project_id=? ORDER BY t.name",
        (pid,),
    ).fetchall()
    assert {r[0] for r in rows} == {"lofi", "chillhop"}


def test_archive_command(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed(tmp_path)
    res = CliRunner().invoke(app, ["archive", str(pid)])
    assert res.exit_code == 0, res.stdout
    archive_root = tmp_path / "Projects" / "_Archive"
    archived = list(archive_root.iterdir())
    assert len(archived) == 1
    assert archived[0].name.startswith("p Project__")
    assert (archived[0] / "x.als").is_file()


def test_journal_lists_recent_batches(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed(tmp_path)
    runner = CliRunner()
    runner.invoke(app, ["color", str(pid), "3"])
    runner.invoke(app, ["tag", str(pid), "x"])
    res = runner.invoke(app, ["journal"])
    assert res.exit_code == 0
    assert "SetColorTag" in res.stdout
    assert "SetTags" in res.stdout


def test_undo_with_no_batches_errors(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed(tmp_path)
    res = CliRunner().invoke(app, ["undo", "last"])
    assert res.exit_code != 0
