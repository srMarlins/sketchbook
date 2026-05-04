"""Compute the per-project 'effort_score' (0-100) from parser metadata.

Pure function — derived from fields the parser already extracts. See
`docs/plans/2026-05-04-effort-score-design.md` for design and v1 weights.
"""

from __future__ import annotations

import math

from audio_core.parser.model import ProjectMetadata

# v1 weights — tunable. Keep in sync with the design doc.
_W_TRACK_COUNT = 18.0
_W_PLUGIN_COUNT = 12.0
_W_UNIQUE_PLUGINS = 10.0
_W_SAMPLE_COUNT = 6.0
_W_FILE_SIZE_KB = 8.0
_W_MASTER_CHAIN = 10.0
# has_automation deferred — current parser does not extract automation envelopes.


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
        "track_count": math.log10(track_count + 1) * _W_TRACK_COUNT,
        "plugin_count": math.log10(plugin_count + 1) * _W_PLUGIN_COUNT,
        "unique_plugins": math.log10(unique_plugins + 1) * _W_UNIQUE_PLUGINS,
        "sample_count": math.log10(sample_count + 1) * _W_SAMPLE_COUNT,
        "file_size_kb": math.log10(file_size_kb + 1) * _W_FILE_SIZE_KB,
        "has_master_chain": _W_MASTER_CHAIN if _has_master_chain(meta) else 0.0,
    }
    raw = sum(breakdown.values())
    score = int(round(max(0.0, min(100.0, raw))))
    # Round breakdown for stable JSON storage / determinism across runs.
    rounded = {k: round(v, 4) for k, v in breakdown.items()}
    return score, rounded
