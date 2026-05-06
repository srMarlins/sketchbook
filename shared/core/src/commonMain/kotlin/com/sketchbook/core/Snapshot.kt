package com.sketchbook.core

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Snapshot kind. See design doc §3.1 / §4.5.
 *
 * - [Auto]: emitted on every Ableton save (A1 — every save preserved).
 * - [Named]: coalesced human-readable timeline entry (A2 — auto-snapshots promoted on idle/timer).
 * - [Branch]: created by auto-fork conflict resolution.
 */
@Serializable
enum class SnapshotKind {
    @SerialName("auto") Auto,
    @SerialName("named") Named,
    @SerialName("branch") Branch,
}

/**
 * One row in a project's history. Materialized from a manifest on the cloud + local sync state.
 */
data class Snapshot(
    val projectUuid: ProjectUuid,
    val rev: SnapshotRev,
    val parentRev: SnapshotRev?,
    val timestamp: Instant,
    val hostId: String,
    val hostName: String,
    val kind: SnapshotKind,
    val label: String?,
    val selfContained: Boolean,
    val fileCount: Int,
    val totalBytes: Long,
    /** Bytes actually uploaded to the cloud for this snapshot (HEAD-deduplicated). */
    val newBytes: Long = 0L,
)
