from unittest.mock import patch

from audio_core.safety.live_lock import is_live_running, is_open_in_live


def test_returns_false_when_no_live_running(tmp_path):
    p = tmp_path / "x.als"
    p.write_bytes(b"x")
    # Test environment never has Live holding a tmp file open.
    assert is_open_in_live(p) is False


def test_returns_false_for_nonexistent_path(tmp_path):
    assert is_open_in_live(tmp_path / "nope.als") is False


def test_refuses_when_live_running_even_if_file_unproven(tmp_path):
    """When Live is running, is_open_in_live conservatively returns True for
    any path — non-admin Windows can't prove file ownership, so we refuse all."""
    p = tmp_path / "totally_unrelated.als"
    p.write_bytes(b"x")
    with patch("audio_core.safety.live_lock.is_live_running", return_value=True):
        # process_iter inside is_open_in_live will not find a matching open_file
        # in this test environment, but the conservative branch returns True anyway.
        assert is_open_in_live(p) is True


def test_is_live_running_returns_bool():
    """Smoke: function executes without raising and returns a bool."""
    result = is_live_running()
    assert isinstance(result, bool)
