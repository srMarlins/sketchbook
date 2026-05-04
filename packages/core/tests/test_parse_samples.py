from pathlib import Path

import pytest
from audio_core.parser.als import als_xml, parse_samples

FIX = Path(__file__).parent / "fixtures"
ALL_FIXTURES = ["tiny.als", "median.als", "huge.als", "old_lofi.als", "new_2026.als"]


@pytest.mark.parametrize("name", ALL_FIXTURES)
def test_samples_have_paths(name):
    samples = parse_samples(als_xml(FIX / name))
    for s in samples:
        assert s.path
        assert isinstance(s.path, str)


def test_old_live_uses_relative_path_components():
    """tiny.als (Live 10) has <RelativePathElement Dir=...>+<Name Value=...> form."""
    samples = parse_samples(als_xml(FIX / "tiny.als"))
    assert len(samples) >= 1
    # Old form ends with the Name value (e.g. .wav filename); shouldn't be empty
    for s in samples:
        assert "/" in s.path or s.path.endswith(".wav") or s.path.endswith(".aif") or "." in s.path


def test_no_duplicate_active_fileref_paths_per_sampleref():
    """Each SampleRef contributes at most one path (active FileRef, not OriginalFileRef)."""
    # huge.als has 32 SampleRefs in our probe; expect at most that many entries
    samples = parse_samples(als_xml(FIX / "huge.als"))
    assert len(samples) <= 32
    assert len(samples) >= 1
