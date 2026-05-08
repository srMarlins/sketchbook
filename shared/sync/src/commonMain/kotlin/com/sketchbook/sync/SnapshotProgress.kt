package com.sketchbook.sync

import com.sketchbook.core.BlobHash
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev

/**
 * Live progress events emitted by [SnapshotPipeline]. The UI picks one of these per redraw to
 * render `Hashing 3/217 files`, `Uploading 4.2 MB / 12.7 MB`, `Done — rev 47`, etc.
 */
sealed interface SnapshotProgress {
    val uuid: ProjectUuid

    data class LeaseAcquired(
        override val uuid: ProjectUuid,
    ) : SnapshotProgress

    data class LeaseHeld(
        override val uuid: ProjectUuid,
        val ownerHostName: String,
    ) : SnapshotProgress

    data class Hashing(
        override val uuid: ProjectUuid,
        val done: Int,
        val total: Int,
    ) : SnapshotProgress

    data class Uploading(
        override val uuid: ProjectUuid,
        val hash: BlobHash,
        val bytesDone: Long,
        val bytesTotal: Long,
    ) : SnapshotProgress

    data class WritingManifest(
        override val uuid: ProjectUuid,
        val rev: SnapshotRev,
    ) : SnapshotProgress

    data class Saved(
        override val uuid: ProjectUuid,
        val rev: SnapshotRev,
        val kind: SnapshotKind,
        val branchLabel: String? = null,
        /**
         * Per-relpath data-loss reports from [ConflictMode.Merge] resolution. Empty for
         * BranchFork mode and for clean Merge runs (no two-sided collisions on distinct
         * bytes). The UI / journal should surface non-empty entries so the user knows their
         * delta was discarded.
         */
        val mergeConflicts: List<MergeConflict> = emptyList(),
    ) : SnapshotProgress

    data class Failed(
        override val uuid: ProjectUuid,
        val reason: String,
    ) : SnapshotProgress
}
