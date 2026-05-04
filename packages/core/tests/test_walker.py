from pathlib import Path

from audio_core.scanner.walker import walk_projects


def test_walker_finds_top_level_als(tmp_path: Path):
    (tmp_path / "a Project").mkdir()
    (tmp_path / "a Project" / "a.als").write_bytes(b"x")
    (tmp_path / "b.als").write_bytes(b"z")
    paths = {p.relative_to(tmp_path).as_posix() for p in walk_projects(tmp_path)}
    assert "a Project/a.als" in paths
    assert "b.als" in paths


def test_walker_excludes_backup(tmp_path: Path):
    (tmp_path / "a Project").mkdir()
    (tmp_path / "a Project" / "a.als").write_bytes(b"x")
    (tmp_path / "a Project" / "Backup").mkdir()
    (tmp_path / "a Project" / "Backup" / "old.als").write_bytes(b"y")
    paths = {p.relative_to(tmp_path).as_posix() for p in walk_projects(tmp_path)}
    assert "a Project/a.als" in paths
    assert "a Project/Backup/old.als" not in paths


def test_walker_excludes_archive(tmp_path: Path):
    (tmp_path / "_Archive").mkdir()
    (tmp_path / "_Archive" / "old.als").write_bytes(b"x")
    (tmp_path / "active.als").write_bytes(b"y")
    paths = {p.relative_to(tmp_path).as_posix() for p in walk_projects(tmp_path)}
    assert "active.als" in paths
    assert "_Archive/old.als" not in paths


def test_walker_returns_paths(tmp_path: Path):
    (tmp_path / "x.als").write_bytes(b"x")
    out = list(walk_projects(tmp_path))
    assert all(isinstance(p, Path) for p in out)
