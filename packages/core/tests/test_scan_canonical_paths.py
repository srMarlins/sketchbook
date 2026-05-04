"""Regression: scan_one and scan_root must canonicalize paths so a relative-path
scan and an absolute-path scan don't double-insert the same project."""

import shutil
from pathlib import Path

from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_one, scan_root

FIX = Path(__file__).parent / "fixtures"


def test_relative_and_absolute_scan_one_dedupe(tmp_path, monkeypatch):
    proj = tmp_path / "p Project"
    proj.mkdir()
    shutil.copy(FIX / "tiny.als", proj / "x.als")
    conn = open_db(tmp_path / "c.db")
    # Cd into tmp_path so a relative path resolves correctly.
    monkeypatch.chdir(tmp_path)
    pid_rel = scan_one(conn, Path("p Project") / "x.als")
    pid_abs = scan_one(conn, (proj / "x.als").resolve())
    assert pid_rel == pid_abs  # same project, same row
    n = conn.execute("SELECT COUNT(*) FROM projects").fetchone()[0]
    assert n == 1


def test_scan_root_with_relative_path_uses_absolute_in_db(tmp_path, monkeypatch):
    proj = tmp_path / "p Project"
    proj.mkdir()
    shutil.copy(FIX / "tiny.als", proj / "x.als")
    conn = open_db(tmp_path / "c.db")
    monkeypatch.chdir(tmp_path)
    scan_root(conn, Path("."))
    row = conn.execute("SELECT path FROM projects").fetchone()
    assert Path(row[0]).is_absolute()
