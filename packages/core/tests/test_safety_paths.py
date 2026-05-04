import pytest
from audio_core.safety.paths import ensure_within


def test_ensure_within_accepts_subpath(tmp_path):
    ensure_within(tmp_path / "sub" / "x.als", tmp_path)


def test_ensure_within_accepts_root_itself(tmp_path):
    ensure_within(tmp_path, tmp_path)


def test_ensure_within_rejects_escape(tmp_path):
    other = tmp_path.parent
    with pytest.raises(PermissionError):
        ensure_within(other / "x.als", tmp_path)


def test_ensure_within_rejects_dotdot_traversal(tmp_path):
    nested = tmp_path / "sub"
    nested.mkdir()
    with pytest.raises(PermissionError):
        ensure_within(nested / ".." / ".." / "outside.als", tmp_path / "sub")
