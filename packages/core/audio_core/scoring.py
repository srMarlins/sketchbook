"""Compute the per-project 'effort_score' (0-100) from parser metadata.

Pure function — derived from fields the parser already extracts. See
`docs/plans/2026-05-04-effort-score-design.md` for design and v1 weights.
"""

from __future__ import annotations

import math

from audio_core.parser.model import ProjectMetadata

# v2 weights — tuned 2026-05-04 against the user's actual ~1,628-project library.
# The v1 weights saturated too fast: 87% of old projects scored >=80, making
# 'forgotten gem' meaningless. v2 subtracts a "template baseline" before log-
# scaling so a project only scores for what was *added* beyond a stock template.
_W_TRACK_COUNT = 18.0
_W_PLUGIN_COUNT = 10.0
_W_UNIQUE_PLUGINS = 8.0
_W_SAMPLE_COUNT = 4.0
_W_FILE_SIZE_KB = 4.0
_W_MASTER_CHAIN = 6.0

# Template baselines — counts up to and including these are treated as "free"
# (a stock template costs nothing). Excess above the baseline is what the
# log-scaled term sees.
_BASE_TRACKS = 8
_BASE_PLUGINS = 4
_BASE_UNIQUE_PLUGINS = 3
_BASE_SAMPLES = 0
_BASE_FILE_SIZE_KB = 200.0  # ~200 KB covers an empty-ish .als
# has_automation deferred — current parser does not extract automation envelopes.


def _excess(value: float, baseline: float) -> float:
    """Return max(0, value - baseline). Anything at or below the template
    baseline contributes zero; we only score what was added on top."""
    return max(0.0, value - baseline)


def _has_master_chain(meta: ProjectMetadata) -> bool:
    """Heuristic: any plugin whose track_name matches 'Master' (case-insensitive)
    or is empty/None indicates the master chain. Matches design-doc guidance.
    """
    for p in meta.plugins:
        tn = (p.track_name or "").strip()
        if tn == "" or tn.lower() == "master":
            return True
    return False


def compute_effort(
    meta: ProjectMetadata, file_size_bytes: int
) -> tuple[int, dict[str, float]]:
    """Return (score 0..100, breakdown of pre-clamp contributions).

    Pure function. Same input -> same output. Skips `has_automation` (not yet
    parseable). Score is clamped to [0, 100]; breakdown captures each signal's
    contribution to the raw (pre-clamp) score so a UI can show 'why is this an 80'.
    """
    track_count = max(0, int(meta.track_count or 0))
    plugin_count = len(meta.plugins)
    unique_plugins = len({p.name for p in meta.plugins})
    sample_count = len(meta.samples)
    file_size_kb = max(0.0, file_size_bytes / 1024.0)

    breakdown: dict[str, float] = {
        "track_count": math.log10(_excess(track_count, _BASE_TRACKS) + 1) * _W_TRACK_COUNT,
        "plugin_count": math.log10(_excess(plugin_count, _BASE_PLUGINS) + 1) * _W_PLUGIN_COUNT,
        "unique_plugins": math.log10(_excess(unique_plugins, _BASE_UNIQUE_PLUGINS) + 1)
        * _W_UNIQUE_PLUGINS,
        "sample_count": math.log10(_excess(sample_count, _BASE_SAMPLES) + 1) * _W_SAMPLE_COUNT,
        "file_size_kb": math.log10(_excess(file_size_kb, _BASE_FILE_SIZE_KB) + 1) * _W_FILE_SIZE_KB,
        "has_master_chain": _W_MASTER_CHAIN if _has_master_chain(meta) else 0.0,
    }
    raw = sum(breakdown.values())
    score = int(round(max(0.0, min(100.0, raw))))
    # Round breakdown for stable JSON storage / determinism across runs.
    rounded = {k: round(v, 4) for k, v in breakdown.items()}
    return score, rounded
