package com.sketchbook.sync

import com.sketchbook.core.BlobHash
import com.sketchbook.core.Manifest
import com.sketchbook.core.ManifestFile
import com.sketchbook.core.ManifestStats
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import kotlin.time.Clock

/**
 * Pure-functional 3-way-style merge for manifests under [ConflictMode.Merge].
 *
 * Strategy:
 * - File set = union of relpaths across [local] and [remote] (tombstones included).
 * - Per relpath conflict: later `mtime` wins. Tie-break by [Manifest.hostId] lexicographic so the
 *   merge is deterministic across both machines (each side computes the same merged manifest from
 *   the same inputs) — the merging host's id is *not* an input here, by design.
 * - `rev = max(local.rev, remote.rev) + 1`; kind set to [SnapshotKind.Auto]. The merged manifest is
 *   the *next* rev we'll attempt to CAS-write.
 * - `parentRev = remote.rev` is **decorative under Merge mode**: it records the manifest that beat
 *   us in CAS, not a true DAG ancestor. A chain of merges only remembers the most recent "loser"
 *   side. Real ancestry is a v1.2 timeline concern; consumers should treat `parentRev` as a hint,
 *   not authoritative lineage.
 * - Stats recomputed from the merged file map. Tombstones are excluded from `file_count` /
 *   `total_bytes`; `new_bytes` is carried from [local] since that reflects bytes this host
 *   actually uploaded — remote's bytes were already in the cloud.
 *
 * Determinism note: `tombstones` participate in the union and the LWW pick, so a delete on one
 * host beats a stale add on the other (provided the delete's tombstone has the later mtime —
 * which is the case when the producer emits tombstones at observation time).
 *
 * Returns a [MergeOutcome] carrying both the merged manifest and a list of [MergeConflict]
 * entries — one per relpath where the LWW pick discarded the loser's distinct bytes. Without
 * surfacing those, two hosts adding different content at the same path silently lose one
 * side's bytes; the caller is expected to journal them so the data-loss is observable.
 */
fun mergeManifests(
    local: Manifest,
    remote: Manifest,
    clock: Clock,
): MergeOutcome {
    val mergedFiles = LinkedHashMap<String, ManifestFile>()
    val conflicts = mutableListOf<MergeConflict>()
    val rels = (local.files.keys + remote.files.keys).toSortedSet()
    for (rel in rels) {
        val l = local.files[rel]
        val r = remote.files[rel]
        val pick = pickWinnerWithConflict(rel, l, r, local.hostId, remote.hostId)
        mergedFiles[rel] = pick.winner
        pick.conflict?.let { conflicts += it }
    }

    val live = mergedFiles.values.filterNot { it.deleted }
    val newRev = SnapshotRev(maxOf(local.rev.value, remote.rev.value) + 1)

    val manifest =
        local.copy(
            rev = newRev,
            parentRev = remote.rev,
            timestamp = clock.now(),
            snapshotKind = SnapshotKind.Auto,
            label = null,
            files = mergedFiles,
            stats =
                ManifestStats(
                    fileCount = live.size,
                    totalBytes = live.sumOf { it.size },
                    newBytes = local.stats.newBytes,
                ),
        )
    return MergeOutcome(manifest = manifest, conflicts = conflicts.toList())
}

/** Result of [mergeManifests]: the merged manifest plus per-relpath data-loss reports. */
data class MergeOutcome(
    val manifest: Manifest,
    val conflicts: List<MergeConflict>,
)

/**
 * Per-relpath conflict where the merge picked one side over another and the loser had distinct
 * non-tombstone bytes — the loser's content is uploaded but no longer referenced by the
 * manifest. Surfaces in the journal so the user can see "we lost a delta from <host>" rather
 * than just observing missing bytes.
 */
data class MergeConflict(
    val relpath: String,
    val localHash: BlobHash?,
    val remoteHash: BlobHash?,
    val pickedSide: Side,
) {
    enum class Side { Local, Remote }
}

private data class PickResult(
    val winner: ManifestFile,
    val conflict: MergeConflict?,
)

private fun pickWinnerWithConflict(
    relpath: String,
    local: ManifestFile?,
    remote: ManifestFile?,
    localHost: String,
    remoteHost: String,
): PickResult {
    require(local != null || remote != null) {
        "pickWinner called with both sides null — relpath should have been excluded from the union"
    }
    if (local == null) return PickResult(remote!!, conflict = null)
    if (remote == null) return PickResult(local, conflict = null)
    val cmp = local.mtime.compareTo(remote.mtime)
    val pickedSide =
        when {
            cmp > 0 -> MergeConflict.Side.Local

            cmp < 0 -> MergeConflict.Side.Remote

            // Identical mtime: deterministic tie-break by host id lexicographic. Both machines
            // computing the same merge from the same inputs land on the same manifest.
            localHost <= remoteHost -> MergeConflict.Side.Local

            else -> MergeConflict.Side.Remote
        }
    val winner = if (pickedSide == MergeConflict.Side.Local) local else remote
    // Only flag as a conflict when the loser had distinct non-tombstone bytes — same content
    // on both sides is a no-op, and a tombstone losing to live content is the design (LWW
    // resurrects the live side).
    val loser = if (pickedSide == MergeConflict.Side.Local) remote else local
    val conflict =
        if (!loser.deleted && loser.hash != winner.hash) {
            MergeConflict(
                relpath = relpath,
                localHash = local.hash,
                remoteHash = remote.hash,
                pickedSide = pickedSide,
            )
        } else {
            null
        }
    return PickResult(winner, conflict)
}
