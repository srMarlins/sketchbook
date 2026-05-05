from audio_core.config import sample_roots


def test_default_is_empty(monkeypatch, tmp_path):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    assert sample_roots() == []


def test_reads_toml(monkeypatch, tmp_path):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    extra1 = tmp_path / "extra1"
    extra2 = tmp_path / "extra2"
    extra1.mkdir()
    extra2.mkdir()
    (tmp_path / "config.toml").write_text(
        f'sample_roots = ["{extra1.as_posix()}", "{extra2.as_posix()}"]\n',
        encoding="utf-8",
    )
    roots = sample_roots()
    assert {r.resolve() for r in roots} == {extra1.resolve(), extra2.resolve()}


def test_skips_nonexistent(monkeypatch, tmp_path):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    (tmp_path / "config.toml").write_text(
        'sample_roots = ["Z:/does/not/exist"]\n', encoding="utf-8"
    )
    assert sample_roots() == []
