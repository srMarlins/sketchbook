package com.sketchbook.sync

import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.Snapshot
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Auto → Named promotion job. Per design §4.5: every 5 min, look at idle projects and promote
 * the most recent `auto` to `named` with a `"checkpoint <ts>"` label, gated by:
 *
 *  - At least [namedGap] elapsed since this project's last `named` snapshot.
 *  - Project idle: most recent snapshot is at least [idleAfter] old.
 *
 * The repository write surface is intentionally a callback so this stays in commonMain without
 * tying [SnapshotRepository] to coalesce semantics.
 */
class CoalesceJob(
    private val clock: Clock = Clock.System,
    private val namedGap: Duration = 30.minutes,
    private val idleAfter: Duration = 10.minutes,
) {

    /**
     * Run a single coalesce pass over [history]. Returns the promotion to apply (or null if
     * nothing qualifies). The caller persists the result via their repository.
     */
    fun evaluate(uuid: ProjectUuid, history: List<Snapshot>, now: Instant = clock.now()): Promotion? {
        if (history.isEmpty()) return null
        val newest = history.maxByOrNull { it.rev.value } ?: return null
        if (now - newest.timestamp < idleAfter) return null

        val newestNamed = history.filter { it.kind == SnapshotKind.Named }.maxByOrNull { it.rev.value }
        if (newestNamed != null && now - newestNamed.timestamp < namedGap) return null

        val targetAuto = history.filter { it.kind == SnapshotKind.Auto }.maxByOrNull { it.rev.value }
            ?: return null
        if (newestNamed != null && targetAuto.rev <= newestNamed.rev) return null

        val label = "checkpoint ${formatTimestamp(now)}"
        return Promotion(uuid = uuid, rev = targetAuto.rev, label = label)
    }

    private fun formatTimestamp(now: Instant): String {
        // Avoid platform-specific formatters; ISO-ish minute precision is fine for the label.
        return now.toString().substringBefore('T') + " " +
            now.toString().substringAfter('T').substringBefore(':') + ":" +
            now.toString().substringAfter(':').substringBefore(':')
    }

    data class Promotion(val uuid: ProjectUuid, val rev: SnapshotRev, val label: String)
}
