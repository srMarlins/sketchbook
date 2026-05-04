from __future__ import annotations

from audio_core.parser.model import PluginRef, ProjectMetadata, SampleRef
from audio_core.scoring import compute_effort


def _meta(**kwargs):
    return ProjectMetadata(**kwargs)


def test_minimal_project_scores_low():
    meta = _meta(track_count=0)
    score, breakdown = compute_effort(meta, file_size_bytes=0)
    assert score < 25
    assert isinstance(breakdown, dict)


def test_loaded_project_scores_high():
    plugins = [
        PluginRef(name=f"p{i}", plugin_type="vst3", track_name="Master" if i == 0 else f"t{i}")
        for i in range(30)
    ]
    samples = [SampleRef(path=f"/x/s{i}.wav") for i in range(40)]
    meta = _meta(track_count=50, plugins=plugins, samples=samples)
    score, breakdown = compute_effort(meta, file_size_bytes=20 * 1024 * 1024)
    assert score >= 75
    assert "track_count" in breakdown
    assert "plugin_count" in breakdown
    assert "unique_plugins" in breakdown
    assert "sample_count" in breakdown
    assert "file_size_kb" in breakdown
    assert "has_master_chain" in breakdown
    assert breakdown["has_master_chain"] == 6.0


def test_score_clamped_to_100():
    plugins = [PluginRef(name=f"p{i}", plugin_type="vst3") for i in range(500)]
    samples = [SampleRef(path=f"/x/s{i}.wav") for i in range(500)]
    meta = _meta(track_count=500, plugins=plugins, samples=samples)
    score, _ = compute_effort(meta, file_size_bytes=10 * 1024 * 1024 * 1024)
    assert score == 100


def test_score_clamped_to_zero():
    score, _ = compute_effort(_meta(), file_size_bytes=0)
    assert score >= 0


def test_deterministic():
    meta = _meta(
        track_count=10,
        plugins=[PluginRef(name="a", plugin_type="vst3")],
        samples=[SampleRef(path="/x/s.wav")],
    )
    s1, b1 = compute_effort(meta, file_size_bytes=1_000_000)
    s2, b2 = compute_effort(meta, file_size_bytes=1_000_000)
    assert s1 == s2
    assert b1 == b2


def test_master_chain_detected_via_track_name():
    meta_no_master = _meta(
        track_count=5,
        plugins=[PluginRef(name="x", plugin_type="vst3", track_name="Drums")],
    )
    meta_master = _meta(
        track_count=5,
        plugins=[PluginRef(name="x", plugin_type="vst3", track_name="Master")],
    )
    s_no, b_no = compute_effort(meta_no_master, file_size_bytes=1024)
    s_yes, b_yes = compute_effort(meta_master, file_size_bytes=1024)
    assert b_no["has_master_chain"] == 0.0
    assert b_yes["has_master_chain"] == 6.0
    assert s_yes > s_no


def test_master_chain_detected_via_empty_track_name():
    """Per design doc: track_name matching 'Master' or empty indicates master chain."""
    meta = _meta(
        track_count=5,
        plugins=[PluginRef(name="x", plugin_type="vst3", track_name=None)],
    )
    _, breakdown = compute_effort(meta, file_size_bytes=1024)
    assert breakdown["has_master_chain"] == 6.0


def test_unique_plugins_separate_from_count():
    """v2 weights apply a template baseline of 4 plugins / 3 unique. Use 12 dups
    so plugin_count > baseline; unique stays below baseline so it contributes 0.
    The signal we want here is 'duplicates inflate plugin_count but unique stays
    flat' — both terms see baselines, so we test the relationship qualitatively."""
    meta = _meta(
        track_count=5,
        plugins=[PluginRef(name="dup", plugin_type="vst3", track_name="t") for _ in range(12)],
    )
    _, breakdown = compute_effort(meta, file_size_bytes=1024)
    # 12 plugin instances clears the baseline (4); 1 unique stays at the floor (≤3 baseline → 0)
    assert breakdown["plugin_count"] > 0
    assert breakdown["unique_plugins"] == 0
    # The duplication signal — many instances, no diversity — yields plugin_count > unique_plugins.
    assert breakdown["unique_plugins"] < breakdown["plugin_count"]
