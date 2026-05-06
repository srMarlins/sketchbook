package com.sketchbook.core

/**
 * Auto-inferred (or user-overridden) classification of where a project sits in its lifecycle.
 *
 * The scanner runs a cheap rule-based heuristic per project (track count + edit recency +
 * mastering-chain detection + nearby-bounce check) and stores the result in `stage_inferred`.
 * The user can override via the detail panel; `stage_override` takes priority for display.
 *
 * Null-vs-`Stage` is meaningful: a project that doesn't match any rule has *no* chip rather
 * than a "Misc" bucket — over-classifying makes the chip noise. With ~1,628 projects in varied
 * states the goal is to surface the obvious ones and leave the ambiguous middle alone.
 *
 * Persisted by `name` in `projects.stage_inferred` / `projects.stage_override`.
 */
enum class Stage {
    /** Track count <5, no mastering chain, no bounce nearby, edited within 30d. */
    Sketch,

    /** Track count >=5, edited within 30d (and not yet at the Mixing stage). */
    InProgress,

    /** Mastering chain present + edited within 14d. */
    Mixing,

    /** Mastering chain + bounce file in the parent folder + not edited within 30d. */
    Done,

    /** Track count >=10, no edits in 90+ days, no bounce nearby. */
    Stuck,
    ;

    companion object {
        /** Parse a persisted enum name back into a [Stage], or null when blank/unrecognized. */
        fun fromName(name: String?): Stage? {
            if (name.isNullOrBlank()) return null
            return entries.firstOrNull { it.name == name }
        }
    }
}
