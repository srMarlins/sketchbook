package com.sketchbook.core

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Direct port of `packages/core/audio_core/scoring.py::compute_effort` (Python reference impl).
 *
 * Returns a 0..100 effort score derived from parser metadata. Same v2 weights and template
 * baselines tuned 2026-05-04 against the user's ~1,628-project library; v1 saturated >=80 for
 * 87% of old projects, which made "forgotten gem" meaningless. v2 subtracts a stock-template
 * baseline before log-scaling so a project only scores for what was *added* beyond a template.
 *
 * Pure function — same input always yields the same `(score, breakdown)`. Does **not** include
 * `has_automation` because the parser doesn't extract automation envelopes yet.
 *
 * The Kotlin scanner today doesn't fill `ProjectMetadata` — `EffortScore.compute(null, _)` returns
 * `null` so the UI shows "—" until the streaming `.als` parser lands.
 */
object EffortScore {

    // v2 weights — keep in sync with scoring.py.
    private const val W_TRACK_COUNT = 18.0
    private const val W_PLUGIN_COUNT = 10.0
    private const val W_UNIQUE_PLUGINS = 8.0
    private const val W_SAMPLE_COUNT = 4.0
    private const val W_FILE_SIZE_KB = 4.0
    private const val W_MASTER_CHAIN = 6.0

    // Template baselines — counts up to and including these are "free".
    private const val BASE_TRACKS = 8.0
    private const val BASE_PLUGINS = 4.0
    private const val BASE_UNIQUE_PLUGINS = 3.0
    private const val BASE_SAMPLES = 0.0
    private const val BASE_FILE_SIZE_KB = 200.0

    /**
     * Score with full breakdown.
     *
     * If [meta] is non-null, every term contributes (full faithful port). If [meta] is null
     * but [fileSizeBytes] > 0, return a *partial* score using just the file-size term — the
     * signal is weak (caps around ~30 even for huge sessions) but it's enough to surface the
     * fattest old projects on the Forgotten-Gems shelf until the streaming parser lands. When
     * both are absent, return null so callers can render "—".
     */
    fun compute(meta: ProjectMetadata?, fileSizeBytes: Long): Result? {
        if (meta == null) {
            if (fileSizeBytes <= 0L) return null
            val fileSizeKb = max(0.0, fileSizeBytes / 1024.0)
            val term = log10(excess(fileSizeKb, BASE_FILE_SIZE_KB) + 1.0) * W_FILE_SIZE_KB
            // Until the parser fills meta, scale the file-size term up 4x so a heavy session
            // can plausibly clear the gem threshold (65). This is an explicit proxy — when
            // real parser data arrives the un-scaled formula takes over and projects re-score.
            val scaled = term * 4.0
            val score = max(0.0, min(100.0, scaled)).roundToInt()
            return Result(score, mapOf("file_size_kb_proxy" to roundTo4(scaled)))
        }
        val trackCount = max(0, meta.totalTrackCount).toDouble()
        val pluginCount = meta.plugins.size.toDouble()
        val uniquePlugins = meta.plugins.map { it.name }.toSet().size.toDouble()
        val sampleCount = meta.sampleRefs.size.toDouble()
        val fileSizeKb = max(0.0, fileSizeBytes / 1024.0)

        val breakdown = linkedMapOf(
            "track_count" to log10(excess(trackCount, BASE_TRACKS) + 1.0) * W_TRACK_COUNT,
            "plugin_count" to log10(excess(pluginCount, BASE_PLUGINS) + 1.0) * W_PLUGIN_COUNT,
            "unique_plugins" to log10(excess(uniquePlugins, BASE_UNIQUE_PLUGINS) + 1.0) * W_UNIQUE_PLUGINS,
            "sample_count" to log10(excess(sampleCount, BASE_SAMPLES) + 1.0) * W_SAMPLE_COUNT,
            "file_size_kb" to log10(excess(fileSizeKb, BASE_FILE_SIZE_KB) + 1.0) * W_FILE_SIZE_KB,
            "has_master_chain" to if (hasMasterChain(meta.plugins)) W_MASTER_CHAIN else 0.0,
        )
        val raw = breakdown.values.sum()
        val score = max(0.0, min(100.0, raw)).roundToInt()
        // Round breakdown for stable storage / determinism, matching scoring.py.
        val rounded = breakdown.mapValues { (_, v) -> roundTo4(v) }
        return Result(score, rounded)
    }

    /** Convenience: score only, or null. */
    fun scoreOf(meta: ProjectMetadata?, fileSizeBytes: Long): Int? = compute(meta, fileSizeBytes)?.score

    private fun excess(value: Double, baseline: Double): Double = max(0.0, value - baseline)

    /**
     * Heuristic: any plugin whose track_name is empty/null or matches "Master"
     * (case-insensitive) indicates the master chain. Matches scoring.py exactly.
     */
    private fun hasMasterChain(plugins: List<PluginRef>): Boolean = plugins.any { p ->
        val tn = p.trackName?.trim().orEmpty()
        tn.isEmpty() || tn.equals("Master", ignoreCase = true)
    }

    private fun roundTo4(v: Double): Double {
        val scaled = (v * 10_000.0)
        return scaled.roundToInt() / 10_000.0
    }

    data class Result(val score: Int, val breakdown: Map<String, Double>)
}
