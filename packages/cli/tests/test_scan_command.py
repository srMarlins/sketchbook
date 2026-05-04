import shutil
from pathlib import Path

from audio_cli.main import app
from typer.testing import CliRunner

FIX = Path(__file__).parents[2] / "core" / "tests" / "fixtures"


def test_scan_outputs_summary(tmp_path, monkeypatch):
    proj = tmp_path / "Projects" / "tiny Project"
    proj.mkdir(parents=True)
    shutil.copy(FIX / "tiny.als", proj / "tiny.als")
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    res = CliRunner().invoke(app, ["scan", "--quiet"])
    assert res.exit_code == 0, res.stdout
    assert "scanned: 1" in res.stdout.lower()


def test_scan_second_pass_skips(tmp_path, monkeypatch):
    proj = tmp_path / "Projects" / "tiny Project"
    proj.mkdir(parents=True)
    shutil.copy(FIX / "tiny.als", proj / "tiny.als")
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    runner = CliRunner()
    runner.invoke(app, ["scan", "--quiet"])
    res = runner.invoke(app, ["scan", "--quiet"])
    assert res.exit_code == 0
    assert "scanned: 0" in res.stdout.lower()
    assert "skipped: 1" in res.stdout.lower()
