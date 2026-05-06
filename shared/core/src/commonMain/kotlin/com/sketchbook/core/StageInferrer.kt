package com.sketchbook.core

import kotlin.time.Instant

/**
 * PR-R: rule-based stage classifier. Pure function from a small input DTO to a [Stage] (or null
 * when no rule matches — null = "no chip", which is the right answer for the ambiguous middle
 * of the user's library).
 *
 * The heuristic intentionally favors precision over recall: with ~1,628 projects in wildly
 * varied states, a noisy chip is worse than a missing one. Rule order matters because some
 * branches overlap (e.g. a 12-track project with a Limiter edited last week qualifies for both
 * `InProgress` and `Mixing`); see [infer] for the resolution order.
 *
 * Lives in `core` (not `sync-io`, despite the original PR-R sketch) because the JvmScanner —
 * which calls this at index time — is in the catalog module, and `catalog` can't depend on
 * `sync-io`. Keeping the pure logic here lets every layer (scanner, repo, MCP) share it. The
 * filesystem-side bounce probe lives next to the scanner in `JvmScanner.kt` (jvmMain only).
 */
object StageInferrer {

    /** Plugin name substrings that indicate a mastering / loudness-shaping chain is present. */
    private val MASTERING_PLUGIN_TOKENS = listOf("OTT", "Pro-L", "Ozone", "Limiter", "Maximizer")

    private const val DAY_MS: Long = 86_400_000L

    /**
     * Classify a project. Returns null when no rule matches — the UI omits the chip in that case.
     *
     * **Rule order** (first match wins):
     *   1. `Mixing` — mastering chain + edited within 14d. Tested first because a heavy track
     *      count + recent edit + mastering chain otherwise gets caught by `InProgress`.
     *   2. `Done` — mastering chain + nearby bounce + last edit ≥ 30d ago.
     *   3. `Stuck` — ≥10 tracks, no edit in 90+ days, no nearby bounce.
     *   4. `Sketch` — small (<5 tracks), no mastering, no bounce, recent (<30d).
     *   5. `InProgress` — ≥5 tracks, recent (<30d), no mastering chain.
     *   6. else null.
     *
     * Edge case: a stale low-track sketch (no mastering, no bounce, last touched 2 years ago)
     * deliberately falls through — we don't want to surface "Sketch" for a long-abandoned 3-track
     * idea because the chip would imply "active sketch" rather than "something I once started".
     */
    fun infer(inputs: StageInputs, now: Instant): Stage? {
        val daysSinceEdit = (now.toEpochMilliseconds() - inputs.lastModified.toEpochMilliseconds()) / DAY_MS
        val hasMastering = inputs.pluginNames.any { name ->
            MASTERING_PLUGIN_TOKENS.any { token -> name.contains(token, ignoreCase = true) }
        }
        return when {
            // Mixing first: a busy project with mastering, recently edited, regardless of track
            // count. Catches the "I'm tweaking the limiter today" case before it falls into
            // InProgress's net.
            hasMastering && daysSinceEdit < 14 -> Stage.Mixing
            // Done: mastering + bounce + cooled off. Once a producer renders a final mix the .als
            // sits untouched while they live with the bounce; that gap is the signal.
            hasMastering && inputs.hasLocalBounce && daysSinceEdit >= 30 -> Stage.Done
            // Stuck: large project that's been ignored for ages and never got a bounce. The
            // 10-track threshold is deliberately stricter than InProgress's 5 — we want "real
            // projects that stalled", not "any half-finished idea".
            inputs.trackCount >= 10 && daysSinceEdit > 90 && !inputs.hasLocalBounce -> Stage.Stuck
            // Sketch: small + recent + nothing finalized.
            inputs.trackCount < 5 && !hasMastering && !inputs.hasLocalBounce && daysSinceEdit < 30 -> Stage.Sketch
            // InProgress: mid-sized project, actively being worked, not yet at mixing stage.
            inputs.trackCount >= 5 && daysSinceEdit < 30 && !hasMastering -> Stage.InProgress
            else -> null
        }
    }
}

/**
 * Tiny DTO of the inputs [StageInferrer.infer] consumes. Pulled out so tests can construct
 * scenarios directly without instantiating a full [ProjectMetadata].
 */
data class StageInputs(
    val trackCount: Int,
    val lastModified: Instant,
    /** Plugin names from `ProjectMetadata.plugins.map { it.name }` — substring-matched against
     *  the mastering-token list so "FabFilter Pro-L 2" hits as well as bare "Pro-L". */
    val pluginNames: List<String>,
    val hasLocalBounce: Boolean,
)
