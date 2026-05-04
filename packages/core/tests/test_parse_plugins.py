from pathlib import Path

import pytest
from audio_core.parser.als import als_xml, parse_plugins

FIX = Path(__file__).parent / "fixtures"

# tiny.als happens to be empty of plugins; the others have many. Test against fixtures
# that we know carry plugin data, plus a smoke test that tiny doesn't crash.
FIXTURES_WITH_PLUGINS = ["median.als", "huge.als", "old_lofi.als", "new_2026.als"]


@pytest.mark.parametrize("name", FIXTURES_WITH_PLUGINS)
def test_plugins_have_names_and_types(name):
    plugins = parse_plugins(als_xml(FIX / name))
    assert len(plugins) > 0
    for p in plugins:
        assert p.name and isinstance(p.name, str)
        assert p.plugin_type in {"vst", "vst3", "au", "ableton-native", "unknown"}


def test_old_lofi_has_vst2_and_vst3():
    plugins = parse_plugins(als_xml(FIX / "old_lofi.als"))
    types = {p.plugin_type for p in plugins}
    assert "vst" in types
    assert "vst3" in types


def test_new_2026_has_vst3():
    plugins = parse_plugins(als_xml(FIX / "new_2026.als"))
    assert any(p.plugin_type == "vst3" for p in plugins)


def test_tiny_returns_list():
    # tiny may have zero plugins; just ensure no exception and a list back.
    assert isinstance(parse_plugins(als_xml(FIX / "tiny.als")), list)
