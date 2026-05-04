"""Confirm the streaming parse_als produces the same ProjectMetadata as the DOM helpers."""

from pathlib import Path

import pytest
from audio_core.parser import parse_als
from audio_core.parser.als import (
    als_xml,
    parse_live_version,
    parse_plugins,
    parse_samples,
    parse_tempo,
    parse_time_signature,
    parse_tracks,
)

FIX = Path(__file__).parent / "fixtures"
ALL_FIXTURES = ["tiny.als", "median.als", "huge.als", "old_lofi.als", "new_2026.als"]


@pytest.mark.parametrize("name", ALL_FIXTURES)
def test_streaming_matches_dom(name):
    p = FIX / name
    streaming = parse_als(p)

    root = als_xml(p)
    n, d = parse_time_signature(root)
    counts = parse_tracks(root)
    dom_tempo = parse_tempo(root)
    dom_version = parse_live_version(root)
    dom_plugins = parse_plugins(root)
    dom_samples = parse_samples(root)

    assert streaming.tempo == dom_tempo
    assert streaming.time_sig_numerator == n
    assert streaming.time_sig_denominator == d
    assert streaming.track_count == counts.total
    assert streaming.audio_track_count == counts.audio
    assert streaming.midi_track_count == counts.midi
    assert streaming.return_track_count == counts.return_
    assert streaming.live_version == dom_version

    # Plugins: same count and same multiset of (name, type) pairs.
    assert len(streaming.plugins) == len(dom_plugins)
    streaming_plugins = sorted((p.name, p.plugin_type) for p in streaming.plugins)
    dom_plugin_pairs = sorted((p.name, p.plugin_type) for p in dom_plugins)
    assert streaming_plugins == dom_plugin_pairs

    # Samples: same count and same multiset of paths.
    assert len(streaming.samples) == len(dom_samples)
    assert sorted(s.path for s in streaming.samples) == sorted(s.path for s in dom_samples)
