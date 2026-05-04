from pathlib import Path

import pytest

from audio_core.parser.als import als_xml

FIX = Path(__file__).parent / "fixtures"


def test_als_xml_returns_root_element():
    root = als_xml(FIX / "tiny.als")
    assert root.tag == "Ableton"
    assert "MajorVersion" in root.attrib


def test_als_xml_raises_on_missing_file(tmp_path):
    with pytest.raises(FileNotFoundError):
        als_xml(tmp_path / "nope.als")
