from pathlib import Path

import pytest

from audio_core.parser.als import als_xml, parse_live_version, parse_tempo, parse_time_signature

FIX = Path(__file__).parent / "fixtures"
ALL_FIXTURES = ["tiny.als", "median.als", "huge.als", "old_lofi.als", "new_2026.als"]


@pytest.mark.parametrize("name", ALL_FIXTURES)
def test_tempo_is_positive_float(name):
    t = parse_tempo(als_xml(FIX / name))
    assert t is not None and 20 < t < 999


@pytest.mark.parametrize("name", ALL_FIXTURES)
def test_time_signature_is_pair_of_ints(name):
    n, d = parse_time_signature(als_xml(FIX / name))
    assert n is not None and d is not None
    assert 1 <= n <= 32 and d in (1, 2, 4, 8, 16, 32, 64)


@pytest.mark.parametrize("name", ALL_FIXTURES)
def test_live_version_string(name):
    v = parse_live_version(als_xml(FIX / name))
    assert v is not None and "." in v
    # we expect a real release version like 10.1.15, 11.0.6, 12.3.2 — not the schema "5.X"
    assert not v.startswith("5.")
