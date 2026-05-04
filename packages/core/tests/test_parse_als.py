from pathlib import Path

import pytest
from audio_core.parser import parse_als

FIX = Path(__file__).parent / "fixtures"
ALL_FIXTURES = ["tiny.als", "median.als", "huge.als", "old_lofi.als", "new_2026.als"]


@pytest.mark.parametrize("name", ALL_FIXTURES)
def test_parse_als_returns_complete_metadata(name):
    m = parse_als(FIX / name)
    assert m.tempo is not None and m.tempo > 0
    assert m.time_sig_numerator is not None and m.time_sig_denominator is not None
    assert m.live_version is not None and "." in m.live_version
    assert m.track_count == m.audio_track_count + m.midi_track_count + m.return_track_count + (
        m.track_count - m.audio_track_count - m.midi_track_count - m.return_track_count
    )
    assert isinstance(m.plugins, list)
    assert isinstance(m.samples, list)


def test_huge_has_substantive_data():
    m = parse_als(FIX / "huge.als")
    assert m.track_count > 0
    assert len(m.plugins) > 0


def test_live_12_versions_recognized():
    """Confirm Live 12.x projects are parsed and report a 12.x version string."""
    huge = parse_als(FIX / "huge.als")
    new = parse_als(FIX / "new_2026.als")
    assert huge.live_version is not None and huge.live_version.startswith("12.")
    assert new.live_version is not None and new.live_version.startswith("12.")
