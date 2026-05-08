package com.sketchbook.repo

import com.sketchbook.core.Manifest
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Persistence + observability for non-project trees. Pairs the cumulative `tree_snapshots`
 * table (parallel to `snapshots`) with the append-only `tree_journal` event log (parallel to
 * `journal_entries` for projects). Lives in `shared/repository` so the sync engine, the
 * materializer, and future timeline UIs all have a single facade for non-project history.
 *
 * **Why both tables behind one interface:** every cloud-observed manifest writes a snapshot
 * row *and* a `snapshot` event. Materialize + merge + conflict events do not write a snapshot
 * row, just an event. Co-locating the two writes keeps callers from having to manage two
 * dependencies (and two transactions) for what's conceptually one fact.
 *
 * Project trees stay on the legacy [SnapshotRepository] / [JournalRepository] path — those
 * tables key on `project_uuid` / `project_id` and feed downstream features (`project_identity`,
 * `repair_acks`, `journal_entries`) that don't apply to non-project kinds.
 */
interface TreeJournal {
    /**
     * Persist a newly observed manifest: insert into `tree_snapshots` (idempotent on
     * `(tree_id, rev)`) and append a [TreeJournalEvent.Snapshot] event. Called by the pull
     * poller for non-project kinds. [manifestPath] is the cloud key of the manifest as
     * returned by `CloudBackend.listManifests`. Throws on encode / SQL failure;
     * `CancellationException` propagates.
     */
    suspend fun recordSnapshot(
        manifest: Manifest,
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        manifestPath: String,
    ): TreeJournalEntry

    /**
     * Append an event without writing a snapshot row. Throws on encode / SQL failure;
     * `CancellationException` propagates.
     */
    suspend fun appendEvent(entry: TreeJournalEntry): TreeJournalEntry

    /** Live tail of journal events for [treeId], newest first, capped at [limit]. */
    fun observeRecent(
        treeId: TrackedTreeId,
        limit: Int = 100,
    ): Flow<List<TreeJournalEntry>>

    /** Live snapshot history for [treeId], newest revision first. */
    fun observeSnapshots(treeId: TrackedTreeId): Flow<List<TreeSnapshotRow>>
}

/**
 * One row in `tree_journal`. [event] is serialized into the `payload_json` column with its
 * [TreeJournalEvent.typeKey] mirrored into `event_kind` so SQL filters work without parsing
 * JSON (same pattern as [JournalEntry] / [ActionRecord]).
 */
data class TreeJournalEntry(
    val treeId: TrackedTreeId,
    val kind: TrackedTreeKind,
    val timestamp: Instant,
    val hostId: String,
    val event: TreeJournalEvent,
    val rev: SnapshotRev? = null,
    /** Machine-local sequence id; assigned by the implementation on append. */
    val sequence: Long? = null,
)

/**
 * One row in `tree_snapshots`. Domain shape used by the (future) timeline UI for non-project
 * trees. Mirrors the `snapshots` row's projection that [SnapshotRepository.observeHistory]
 * returns for projects.
 */
data class TreeSnapshotRow(
    val treeId: TrackedTreeId,
    val rev: SnapshotRev,
    val parentRev: SnapshotRev?,
    val timestamp: Instant,
    val hostId: String,
    val snapshotKind: SnapshotKind,
    val label: String?,
    val fileCount: Int,
    val totalBytes: Long,
    val newBytes: Long,
    val manifestPath: String,
)

/**
 * Sync events for a non-project tree. Variants match the wire-stable strings in the design
 * doc's `tree_journal.event_kind` column: `'snapshot' | 'merge' | 'materialize' | 'conflict'`.
 *
 * [typeKey] is the canonical event-kind string. Following [ActionRecord]'s precedent we declare
 * it explicitly per variant so it survives R8 / future obfuscation (`::class.simpleName` would
 * not).
 */
@Serializable
sealed interface TreeJournalEvent {
    val typeKey: String

    /** A new manifest was observed by the puller (or just produced locally). */
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(
        val rev: Long,
        val parentRev: Long? = null,
        val fileCount: Int,
        val totalBytes: Long,
        val newBytes: Long,
        val manifestPath: String,
    ) : TreeJournalEvent {
        override val typeKey: String get() = TYPE_KEY

        companion object {
            const val TYPE_KEY: String = "snapshot"
        }
    }

    /** Two divergent HEADs were merged into a new revision (Merge conflict mode). */
    @Serializable
    @SerialName("merge")
    data class Merge(
        val localRev: Long,
        val remoteRev: Long,
        val mergedRev: Long,
        val tombstonesPropagated: Int,
    ) : TreeJournalEvent {
        override val typeKey: String get() = TYPE_KEY

        companion object {
            const val TYPE_KEY: String = "merge"
        }
    }

    /** A snapshot was materialized to disk for [rev]. */
    @Serializable
    @SerialName("materialize")
    data class Materialize(
        val rev: Long,
        val filesWritten: Int,
        val filesDeleted: Int,
    ) : TreeJournalEvent {
        override val typeKey: String get() = TYPE_KEY

        companion object {
            const val TYPE_KEY: String = "materialize"
        }
    }

    /** A push attempt CAS-failed. Surfaces in the journal even when the resolver retries. */
    @Serializable
    @SerialName("conflict")
    data class Conflict(
        val ourRev: Long,
        val theirRev: Long,
        val resolution: String,
    ) : TreeJournalEvent {
        override val typeKey: String get() = TYPE_KEY

        companion object {
            const val TYPE_KEY: String = "conflict"
        }
    }

    /**
     * Sentinel for events serialized by a newer binary using a discriminator this build doesn't
     * recognize. Reached via the polymorphic default-deserializer registered on the journal's
     * [Json]: `Json.ignoreUnknownKeys = true` only skips unknown *fields*, not unknown *types*, so
     * without this fallback an older binary would throw [kotlinx.serialization.SerializationException]
     * the moment it reads a journal row written by a newer client.
     *
     * Surfaces in the UI as a generic "future event" row; consumers should match on the known
     * variants and fall through to ignore [Unknown].
     */
    @Serializable
    @SerialName("__unknown")
    data object Unknown : TreeJournalEvent {
        override val typeKey: String get() = TYPE_KEY

        const val TYPE_KEY: String = "__unknown"
    }
}
