from audio_core.safety.live_lock import is_open_in_live


def test_returns_false_when_no_live_running(tmp_path):
    p = tmp_path / "x.als"
    p.write_bytes(b"x")
    # Test environment never has Live holding a tmp file open.
    assert is_open_in_live(p) is False


def test_returns_false_for_nonexistent_path(tmp_path):
    assert is_open_in_live(tmp_path / "nope.als") is False
