package com.sketchbook.core

import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Coarse-grained classification of where a project sits in its life-cycle. Used by PR-R's
 * stage chip on each project row and by the toolbar filter that narrows to a stage subset.
 *
 * Inferred by [StageInferrer.infer] from heuristic signals (track count, mtime, mastering chain
 * presence, local bounce). The user can override via the per-project chip popup; the override
 * lives in `projects.stage_override` (PR-R R3) and wins over the inferred value at render time.
 *
 * Wire-format: persisted into `projects.stage_inferred` / `projects.stage_override` as the
 * variant `name`. Adding a new variant means writing a migration that's tolerant of values
 * outside the new enum (treat as `null`); see [parseOrNull].
 */
enum class Stage(val label: String, val displayName: String) {
    /** Track count <5, no mastering, no bounce, edited recently — fresh idea. */
    Sketch(label = "sketch", displayName = "Sketch"),

    /** ≥5 tracks, edited within 30d, no mastering yet — actively being developed. */
    InProgress(label = "in progress", displayName = "In Progress"),

    /** Mastering chain present + edited within 30d — final pre-export polish. */
    Mixing(label = "mixing", displayName = "Mixing"),

    /** Mastering chain present, has a local bounce, edited >30d ago — finished. */
    Done(label = "done", displayName = "Done"),

    /** Many tracks, untouched for >90d, no bounce — abandoned or stalled. */
    Stuck(label = "stuck", displayName = "Stuck"),
    ;

    companion object {
        /**
         * Round-trip parse from the column string. Tolerant of unknown values (returns null) so
         * a future Stage variant added by a newer Sketchbook build doesn't crash older readers.
         */
        fun parseOrNull(raw: String?): Stage? = when (raw) {
            null -> null
            else -> values().firstOrNull { it.name == raw }
        }
    }
}

/**
 * Pure heuristic that maps observable per-project signals to a [Stage] (or `null` when no rule
 * matches). Lives in `:shared:core` so both the JVM scanner (`:shared:catalog`) and the UI layer
 * can use it without a circular dep.
 *
 * Rule order is significant — the first match wins. The Mixing rule sits above InProgress so a
 * 12-track project with a Limiter edited 3 days ago classifies as `Mixing`, not `InProgress`.
 *
 * Heuristic rules (rule-ordered):
 * 1. Mastering chain + local bounce + edited >30d ago → `Done`
 * 2. Mastering chain + edited within 30d → `Mixing`
 * 3. ≥10 tracks + edited >90d ago + no bounce → `Stuck`
 * 4. ≥5 tracks + edited within 30d + no mastering → `InProgress`
 * 5. <5 tracks + no mastering + no bounce + edited within 30d → `Sketch`
 * 6. else → `null`
 */
object StageInferrer {

    /**
     * Substring matches inside plugin names (case-insensitive) that mark a mastering chain.
     * OTT is intentionally excluded: it's a popular upward/downward compressor used on individual
     * tracks (synths, drums) far more often than on the master bus, so including it caused false
     * positives. Callers should also restrict the input to plugins on the "Master" track.
     */
    private val MASTERING_NEEDLES = listOf("pro-l", "ozone", "limiter", "maximizer")

    /**
     * Classify a project from its scanned signals. All inputs are pre-extracted from the
     * scanner's `ParseOutcome.Ok`; this function is pure and trivially testable.
     *
     * @param trackCount total track count from `ProjectMetadata.totalTrackCount`.
     * @param pluginNames every plugin name on every track. Order doesn't matter.
     * @param hasLocalBounce true if a `.wav`/`.mp3`/`.aiff` mixdown is present in the project's
     *   working tree (best-effort filesystem probe; false on I/O errors).
     * @param lastModified the project's `.als` mtime.
     * @param now the reference time the rules compare against (injected so the inferrer is
     *   deterministic in tests).
     */
    fun infer(
        trackCount: Int,
        pluginNames: List<String>,
        hasLocalBounce: Boolean,
        lastModified: Instant,
        now: Instant,
    ): Stage? {
        val hasMastering = pluginNames.any { name ->
            val lower = name.lowercase()
            MASTERING_NEEDLES.any { needle -> lower.contains(needle) }
        }
        val age = now - lastModified

        // Rule 1: Done — mastered + bounced + cooled off.
        if (hasMastering && hasLocalBounce && age > 30.days) return Stage.Done

        // Rule 2: Mixing — mastered + actively being polished. The 30d cutoff matches Rule 1's
        // "Done" cool-off threshold so a mastered project edited 14–30d ago doesn't fall into the
        // dead zone between rules.
        if (hasMastering && age <= 30.days) return Stage.Mixing

        // Rule 3: Stuck — large project that nobody's touched in months.
        if (trackCount >= 10 && age > 90.days && !hasLocalBounce) return Stage.Stuck

        // Rule 4: InProgress — meaningful track count, recently edited, not yet mastered.
        if (trackCount >= 5 && age <= 30.days && !hasMastering) return Stage.InProgress

        // Rule 5: Sketch — small, no mastering, no bounce, recent.
        if (trackCount < 5 && !hasMastering && !hasLocalBounce && age <= 30.days) return Stage.Sketch

        // Rule 6: nothing classified — leave inferred null so the UI shows no chip.
        return null
    }
}
