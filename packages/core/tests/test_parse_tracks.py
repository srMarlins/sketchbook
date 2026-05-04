from pathlib import Path

import pytest
from audio_core.parser.als import als_xml, parse_tracks

FIX = Path(__file__).parent / "fixtures"
ALL_FIXTURES = ["tiny.als", "median.als", "huge.als", "old_lofi.als", "new_2026.als"]


@pytest.mark.parametrize("name", ALL_FIXTURES)
def test_track_counts_nonneg_and_sum(name):
    counts = parse_tracks(als_xml(FIX / name))
    assert counts.audio >= 0 and counts.midi >= 0 and counts.return_ >= 0 and counts.group >= 0
    assert counts.total == counts.audio + counts.midi + counts.return_ + counts.group


def test_huge_has_tracks():
    counts = parse_tracks(als_xml(FIX / "huge.als"))
    assert counts.total > 0
