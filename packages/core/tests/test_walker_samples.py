from pathlib import Path

from audio_core.scanner.walker import walk_samples


def _touch(p: Path):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(b"x")


def test_finds_audio_extensions(tmp_path):
    _touch(tmp_path / "a.wav")
    _touch(tmp_path / "sub/b.AIFF")
    _touch(tmp_path / "sub/c.flac")
    _touch(tmp_path / "sub/d.mp3")
    _touch(tmp_path / "ignore.als")
    _touch(tmp_path / "ignore.txt")
    paths = sorted(p.name.lower() for p in walk_samples(tmp_path))
    assert paths == ["a.wav", "b.aiff", "c.flac", "d.mp3"]


def test_excludes_backup_dirs(tmp_path):
    _touch(tmp_path / "Backup" / "a.wav")
    _touch(tmp_path / "_Archive" / "b.wav")
    _touch(tmp_path / "Ableton Project Info" / "c.wav")
    _touch(tmp_path / "real.wav")
    paths = [p.name for p in walk_samples(tmp_path)]
    assert paths == ["real.wav"]
