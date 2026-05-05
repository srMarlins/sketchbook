from pathlib import Path
from audio_core.parser.als import parse_als


def test_clean_project_has_zero_mac_paths():
    md = parse_als(Path(__file__).parent / "fixtures" / "tiny.als")
    assert md.mac_paths_count == 0


def test_mac_imported_fixture_counts_three():
    md = parse_als(Path(__file__).parent / "fixtures" / "mac_imported_tiny.als")
    assert md.mac_paths_count == 3
