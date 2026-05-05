from pathlib import Path

from fastapi.testclient import TestClient


def test_watcher_started_with_sample_roots(monkeypatch, tmp_path):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    (tmp_path / "Projects").mkdir()
    extra = tmp_path / "Samples"
    extra.mkdir()
    (tmp_path / "config.toml").write_text(
        f'sample_roots = ["{extra.as_posix()}"]\n', encoding="utf-8"
    )

    from audio_web.app import create_app
    app = create_app()
    with TestClient(app) as client:
        client.get("/api/health")
        watcher = app.state.fs_watcher
        assert extra.resolve() in [Path(p).resolve() for p in watcher._sample_roots]
