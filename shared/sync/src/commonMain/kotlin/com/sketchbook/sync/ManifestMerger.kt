package com.sketchbook.sync

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
 */
fun mergeManifests(
    local: Manifest,
    remote: Manifest,
    clock: Clock,
): Manifest {
    val mergedFiles = LinkedHashMap<String, ManifestFile>()
    val rels = (local.files.keys + remote.files.keys).toSortedSet()
    for (rel in rels) {
        val l = local.files[rel]
        val r = remote.files[rel]
        mergedFiles[rel] = pickWinner(l, r, local.hostId, remote.hostId)
    }

    val live = mergedFiles.values.filterNot { it.deleted }
    val newRev = SnapshotRev(maxOf(local.rev.value, remote.rev.value) + 1)

    return local.copy(
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
}

private fun pickWinner(
    local: ManifestFile?,
    remote: ManifestFile?,
    localHost: String,
    remoteHost: String,
): ManifestFile {
    require(local != null || remote != null) {
        "pickWinner called with both sides null — relpath should have been excluded from the union"
    }
    if (local == null) return remote!!
    if (remote == null) return local
    val cmp = local.mtime.compareTo(remote.mtime)
    return when {
        cmp > 0 -> local

        cmp < 0 -> remote

        // Identical mtime: deterministic tie-break by host id lexicographic. Both machines
        // computing the same merge from the same inputs land on the same manifest.
        localHost <= remoteHost -> local

        else -> remote
    }
}
