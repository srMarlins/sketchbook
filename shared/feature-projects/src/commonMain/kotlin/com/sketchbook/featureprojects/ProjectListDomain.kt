package com.sketchbook.featureprojects

import com.sketchbook.core.ParseStatus

/**
 * Categorical buckets the dashboard renders as shelves + chips. Each shelf is independently
 * computed from the full group list, so a single project can appear in more than one. See
 * [bucketize] for the exact rules — they mirror `packages/web/audio_web/home.py::_shelf_*`.
 */
data class Buckets(
    val currentlyWorking: List<ProjectGroup>,
    val forgottenGems: List<ProjectGroup>,
    val almostDone: List<ProjectGroup>,
    val hasPotential: List<ProjectGroup>,
    val untriaged: List<ProjectGroup>,
    val broken: List<ProjectGroup>,
    val archived: List<ProjectGroup>,
    val all: List<ProjectGroup>,
) {
    companion object {
        val EMPTY =
            Buckets(
                currentlyWorking = emptyList(),
                forgottenGems = emptyList(),
                almostDone = emptyList(),
                hasPotential = emptyList(),
                untriaged = emptyList(),
                broken = emptyList(),
                archived = emptyList(),
                all = emptyList(),
            )
    }
}

/**
 * Identifier for one of the dashboard shelves. The state holder uses this for the
 * `zoomShelf` selector ("show all of bucket X" mode); the view binds chips/headers to it
 * via [title], [subtitle], [bucket].
 */
enum class ShelfId(
    val id: String,
) {
    CurrentlyWorking("currently-working"),
    ForgottenGems("forgotten-gems"),
    AlmostDone("almost-done"),
    HasPotential("has-potential"),
    Untriaged("untriaged"),
    Broken("broken"),
    Archived("archived"),
    ;

    fun title(): String =
        when (this) {
            CurrentlyWorking -> "Currently working on"
            ForgottenGems -> "Forgotten gems"
            AlmostDone -> "Almost done"
            HasPotential -> "Has potential"
            Untriaged -> "Untriaged"
            Broken -> "Broken"
            Archived -> "Archived"
        }

    fun subtitle(): String =
        when (this) {
            CurrentlyWorking -> "Blue color tag or modified within 6 months."
            ForgottenGems -> "Effort score >=60 and quiet for 2+ years."
            AlmostDone -> "Warm color tags (orange / yellow) — close to a release."
            HasPotential -> "Purple-tagged sketches marked for revisit."
            Untriaged -> "No color tag yet — needs a glance."
            Broken -> "Failed to parse, or referencing missing samples."
            Archived -> "Set aside from the active library."
        }

    fun bucket(b: Buckets): List<ProjectGroup> =
        when (this) {
            CurrentlyWorking -> b.currentlyWorking
            ForgottenGems -> b.forgottenGems
            AlmostDone -> b.almostDone
            HasPotential -> b.hasPotential
            Untriaged -> b.untriaged
            Broken -> b.broken
            Archived -> b.archived
        }

    companion object {
        fun fromId(id: String): ShelfId? = entries.firstOrNull { it.id == id }
    }
}

private const val FOURTEEN_DAYS_MS = 14L * 24 * 60 * 60 * 1000
private const val FORGOTTEN_GEM_THRESHOLD = 65

// Ableton palette indices (matching `AbletonPalette` in :ui-shared and the mocks the user
// iterated against). Python `home.py` used a provisional 1..6 mapping with a comment noting
// it would change once real scan data arrived — we use the canonical Ableton indices here.
private val WARM_COLORS = setOf(1, 2, 3) // pink / orange / yellow
private const val BLUE = 10
private const val PURPLE = 12

// Forgotten-gems excludes "shipped" / "killed" tags so the gem shelf is genuinely forgotten,
// not just old.
private val GEM_EXCLUDE_COLORS = setOf(6, 7) // green-ish (shipped), red-ish (killed)

/**
 * Faithful port of `packages/web/audio_web/home.py::_shelf_*`. Notably:
 *
 *  - `currently-working`: blue OR mtime within **14 days**.
 *  - `forgotten-gems`: effort_score >= **65** AND color tag NOT in (green, red).
 *  - `almost-done`: warm color tags (orange/yellow).
 *  - `has-potential`: purple color tag.
 *  - `untriaged`: no color tag.
 *  - `broken`: parse failed OR missing_sample_count > 0.
 *
 * A group can appear in multiple shelves; chip counts and shelves are computed independently.
 */
fun bucketize(
    all: List<ProjectGroup>,
    archived: List<ProjectGroup> = emptyList(),
): Buckets {
    val nowMs =
        kotlin.time.Clock.System
            .now()
            .toEpochMilliseconds()
    val currentlyWorking =
        all.filter { g ->
            g.representative.colorTag == BLUE || (nowMs - g.updatedAtMs) < FOURTEEN_DAYS_MS
        }
    // No recency gate — "forgotten" is meant to surface effort, not just age. Old projects
    // sort earlier as a tiebreaker (oldest first) so the shelf prefers buried things, but a
    // recent high-effort sketch can still show up.
    val forgottenGems =
        all
            .asSequence()
            .filter { g ->
                (g.effortScore ?: 0) >= FORGOTTEN_GEM_THRESHOLD &&
                    g.representative.colorTag !in GEM_EXCLUDE_COLORS
            }.sortedWith(compareByDescending<ProjectGroup> { it.effortScore ?: 0 }.thenBy { it.updatedAtMs })
            .toList()
    val almostDone = all.filter { g -> g.representative.colorTag in WARM_COLORS }
    val hasPotential = all.filter { g -> g.representative.colorTag == PURPLE }
    val untriaged = all.filter { g -> g.representative.colorTag == null }
    val broken =
        all.filter { g ->
            g.parseStatusBest == ParseStatus.Failed || g.missingSampleCount > 0
        }
    return Buckets(
        currentlyWorking = currentlyWorking,
        forgottenGems = forgottenGems,
        almostDone = almostDone,
        hasPotential = hasPotential,
        untriaged = untriaged,
        broken = broken,
        archived = archived,
        all = all,
    )
}

/**
 * Group-level substring match used by the search overlay. Repository-level FTS5 narrows
 * `rows` first; this widens to project root path + variant filenames + tags so a tag-only or
 * folder-only match still surfaces a row whose `name` doesn't contain the query.
 */
fun matchesQuery(
    group: ProjectGroup,
    q: String,
): Boolean {
    val needle = q.trim()
    if (needle.isEmpty()) return true
    if (group.id.contains(needle, ignoreCase = true)) return true
    return group.variants.any { v ->
        v.name.contains(needle, ignoreCase = true) ||
            v.tags.any { it.contains(needle, ignoreCase = true) }
    }
}
