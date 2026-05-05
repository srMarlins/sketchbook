import os
import sys

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


def test_ensure_within_rejects_absolute_outside(tmp_path):
    """Absolute path outside root must be rejected even if it doesn't exist."""
    with pytest.raises(PermissionError):
        ensure_within("/totally/elsewhere/x.als" if os.name != "nt" else "C:/Windows/Temp/x.als", tmp_path)


@pytest.mark.skipif(os.name == "nt", reason="symlinks require admin on Windows")
def test_ensure_within_rejects_symlink_pointing_outside(tmp_path):
    """A symlink inside root that points outside resolves outside; must be rejected."""
    inside = tmp_path / "Projects"
    inside.mkdir()
    outside = tmp_path / "elsewhere"
    outside.mkdir()
    sneaky = inside / "sneaky"
    sneaky.symlink_to(outside, target_is_directory=True)
    with pytest.raises(PermissionError):
        ensure_within(sneaky / "x.als", inside)


def test_ensure_within_handles_case_difference(tmp_path):
    """On Windows, paths compare case-insensitively. On POSIX, case-sensitive."""
    if sys.platform == "win32":
        ensure_within(str(tmp_path).upper() + "/sub/x.als", tmp_path)
    else:
        # POSIX: case-sensitive. Different case is genuinely a different path.
        with pytest.raises(PermissionError):
            ensure_within(str(tmp_path).upper() + "/sub/x.als", tmp_path)
