package com.sketchbook.sync

import com.sketchbook.cloud.BlobScope
import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.Generation
import com.sketchbook.cloud.LeaseAcquireResult
import com.sketchbook.cloud.LeaseLock
import com.sketchbook.core.BlobHash
import com.sketchbook.core.Manifest
import com.sketchbook.core.ManifestFile
import com.sketchbook.core.ManifestStats
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SketchbookError
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
import com.sketchbook.core.UserId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Orchestrates the §4.2 save-to-snapshot pipeline.
 *
 * Inputs are intentionally narrow so this stays testable in commonTest with an in-memory
 * [CloudBackend] + [WorkingTree]. The JVM application (PR-18) wires up the real watcher → tree
 * walker → repository chain on top.
 *
 * Returned [Flow] emits live progress and ends with either [SnapshotProgress.Saved] (success or
 * branch-fork) or [SnapshotProgress.Failed].
 */
class SnapshotPipeline(
    private val cloud: CloudBackend,
    private val ownerUserId: UserId = UserId.DEFAULT,
    private val hostId: String,
    private val hostName: String,
    private val clock: Clock = Clock.System,
    private val leaseTtl: Duration = 15.minutes,
) {
    fun run(input: PipelineInput): Flow<SnapshotProgress> =
        flow {
            val uuid = input.uuid
            val treeId = input.treeId
            val kind = input.kind
            val policy =
                KindPolicy.forKind(kind) ?: run {
                    emit(SnapshotProgress.Failed(uuid, "no policy for kind '${kind.wireName}'"))
                    return@flow
                }
            val parentRev = input.lastKnownManifest?.rev
            val parentFiles = input.lastKnownManifest?.files ?: emptyMap()
            val parentExpectedHead = input.expectedHeadGeneration
            val blobScope: BlobScope = if (input.selfContained) BlobScope.Private(uuid) else BlobScope.Shared

            // 1) Lease — gated by policy. Kinds with `leaseRequired = false` (UserLibrary) rely on
            // CAS HEAD + merge-on-conflict instead of a coarse-grained lock.
            var leaseGen: Generation? = null
            if (policy.leaseRequired) {
                val leaseInstant = clock.now()
                val lock =
                    LeaseLock(
                        ownerUserId = ownerUserId,
                        ownerHostId = hostId,
                        ownerHostName = hostName,
                        acquiredAt = leaseInstant,
                        expiresAt = leaseInstant + leaseTtl,
                    )
                when (val r = cloud.acquireLock(treeId, kind, lock)) {
                    is LeaseAcquireResult.Acquired -> {
                        emit(SnapshotProgress.LeaseAcquired(uuid))
                        leaseGen = r.generation
                    }

                    is LeaseAcquireResult.Held -> {
                        emit(SnapshotProgress.LeaseHeld(uuid, r.held.ownerHostName))
                        emit(SnapshotProgress.Failed(uuid, "lock held by ${r.held.ownerHostName}"))
                        return@flow
                    }
                }
            }

            try {
                // 2) Walk + diff.
                val rels = input.tree.list()
                val unchanged = mutableMapOf<String, ManifestFile>()
                val toUpload = mutableMapOf<String, ManifestFile>()
                rels.forEachIndexed { i, rel ->
                    emit(SnapshotProgress.Hashing(uuid, i + 1, rels.size))
                    val stat = input.tree.stat(rel)
                    val parent = parentFiles[rel]
                    if (parent != null && parent.size == stat.size && parent.mtime == stat.mtime) {
                        unchanged[rel] = parent
                        return@forEachIndexed
                    }
                    val hash = input.tree.hash(rel)
                    val entry = ManifestFile(hash = hash, size = stat.size, mtime = stat.mtime)
                    if (parent != null && parent.hash == hash) {
                        unchanged[rel] = entry
                    } else {
                        toUpload[rel] = entry
                    }
                }

                // 3) Upload deduped: HEAD-then-PUT each unique blob. `actuallyUploadedBytes` tracks
                // only the bytes that left this host — when `headBlob` returns true we count it as
                // reused, not new. The figure flows into the manifest's stats.newBytes.
                var bytesDone = 0L
                val totalUploadBytes = toUpload.values.sumOf { it.size }
                // Uploads never carry tombstones (they're materialize-side deletes, not
                // upload-side new bytes), so the hash is always non-null here. Forcing the
                // cast at the boundary keeps the rest of the upload loop typed against
                // `BlobHash`, not `BlobHash?` — same shape as before the v=2 nullable hash.
                val uniqueByHash: Map<BlobHash, List<ManifestFile>> =
                    toUpload.values.groupBy { requireNotNull(it.hash) { "upload entry has no hash" } }
                var actuallyUploadedBytes = 0L
                for ((hash, entries) in uniqueByHash) {
                    val first = entries.first()
                    val anyRel = toUpload.entries.first { it.value.hash == hash }.key
                    if (cloud.headBlob(hash, blobScope)) {
                        bytesDone += first.size
                        emit(SnapshotProgress.Uploading(uuid, hash, bytesDone, totalUploadBytes))
                        continue
                    }
                    cloud.putBlob(hash, input.tree.read(anyRel), first.size, blobScope)
                    actuallyUploadedBytes += first.size
                    bytesDone += first.size
                    emit(SnapshotProgress.Uploading(uuid, hash, bytesDone, totalUploadBytes))
                }

                // 4) Compose manifest. New rev = parentRev+1, or 1 if no parent.
                val newRev = parentRev?.next() ?: SnapshotRev(1)
                emit(SnapshotProgress.WritingManifest(uuid, newRev))

                // Tombstones for relpaths present in [parentFiles] but absent on disk —
                // produces a Merge-mode-friendly delete signal so a stale add on another host
                // doesn't resurrect a file the user just removed (design § "Manifest delete
                // semantics"). For Project trees this is harmless: BranchFork resolution
                // doesn't merge, so the tombstone is just metadata; the file stays gone in
                // any subsequent rev. For UserLibrary trees it's load-bearing.
                val tombstones = LinkedHashMap<String, ManifestFile>()
                val present = unchanged.keys + toUpload.keys
                for ((rel, parentEntry) in parentFiles) {
                    if (rel in present) continue
                    if (parentEntry.deleted) continue // already a tombstone — don't keep extending its mtime.
                    tombstones[rel] =
                        ManifestFile(
                            hash = null,
                            size = 0,
                            mtime = clock.now(),
                            deleted = true,
                        )
                }

                val files = LinkedHashMap<String, ManifestFile>(unchanged.size + toUpload.size + tombstones.size)
                files.putAll(unchanged)
                files.putAll(toUpload)
                files.putAll(tombstones)
                // Live (non-tombstone) entries drive the user-facing stats.
                val live = files.values.filterNot { it.deleted }
                val totalBytes = live.sumOf { it.size }
                val newBytes = actuallyUploadedBytes
                // Caller can promote this snapshot to a Named entry by setting [PipelineInput.kind]
                // (with optional [PipelineInput.label]). The Z3 quick-capture hotkey uses that to
                // force a labeled timeline row regardless of dirty flag. Default stays Auto so the
                // existing watcher-driven path is unchanged.
                val auto =
                    Manifest(
                        ownerUserId = ownerUserId,
                        treeId = treeId,
                        kind = kind,
                        rev = newRev,
                        parentRev = parentRev,
                        timestamp = clock.now(),
                        hostId = hostId,
                        hostName = hostName,
                        snapshotKind = input.snapshotKind,
                        label = input.label,
                        files = files,
                        stats =
                            ManifestStats(
                                fileCount = live.size,
                                totalBytes = totalBytes,
                                newBytes = newBytes,
                            ),
                        selfContained = input.selfContained,
                    )

                // 5) CAS HEAD. Conflict resolution dispatches on KindPolicy.conflictMode.
                //
                // Lease fence: between `acquireLock` and here, the user (or peer host) may
                // have force-taken our lease. The HEAD CAS itself only checks the manifest's
                // expectedHead; without a separate lease check, a writer with a force-taken
                // lease could still land its HEAD swap. Refresh the lease right before the
                // CAS — if it's no longer ours, bail with a clear failure rather than
                // racing past the takeover.
                if (leaseGen != null) {
                    val leaseInstant = clock.now()
                    val refreshed =
                        LeaseLock(
                            ownerUserId = ownerUserId,
                            ownerHostId = hostId,
                            ownerHostName = hostName,
                            acquiredAt = leaseInstant,
                            expiresAt = leaseInstant + leaseTtl,
                        )
                    when (val r = cloud.refreshLock(treeId, kind, refreshed, leaseGen)) {
                        is com.sketchbook.cloud.LeaseRefreshResult.Refreshed -> {
                            leaseGen = r.generation
                        }

                        com.sketchbook.cloud.LeaseRefreshResult.Stale -> {
                            // Force-taken by someone else; we no longer own the lease and
                            // must not advance HEAD. Drop our lease tracking so the finally
                            // block's release call doesn't try to delete a generation we no
                            // longer own.
                            leaseGen = null
                            emit(SnapshotProgress.Failed(uuid, "lease lost mid-pipeline; aborted before HEAD CAS"))
                            return@flow
                        }
                    }
                }
                val casResult = cloud.appendManifestHead(treeId, kind, parentExpectedHead, auto)
                val saved =
                    casResult.fold(
                        onSuccess = { SnapshotProgress.Saved(uuid, newRev, input.snapshotKind, input.label) },
                        onFailure = { null },
                    ) ?: resolveCasFailure(
                        uuid = uuid,
                        treeId = treeId,
                        kind = kind,
                        policy = policy,
                        local = auto,
                        parentRev = parentRev,
                        err = casResult.exceptionOrNull(),
                    )
                emit(saved)
            } finally {
                // 6) Release lease (best-effort; pipeline outcome already emitted). Skipped when
                // policy.leaseRequired = false — `leaseGen` stays null and we never acquired one.
                val gen = leaseGen
                if (gen != null) {
                    @Suppress("ThrowingExceptionFromFinally")
                    // The throw is the canonical CancellationException rethrow — losing it
                    // would silently swallow coroutine cancellation. Detekt's rule guards
                    // against accidental loss; this rethrow is deliberate.
                    try {
                        cloud.releaseLock(treeId, kind, gen)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Throwable) {
                        // Best-effort release; pipeline outcome already emitted.
                    }
                }
            }
        }

    private suspend fun resolveCasFailure(
        uuid: ProjectUuid,
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        policy: KindPolicy,
        local: Manifest,
        parentRev: SnapshotRev?,
        err: Throwable?,
    ): SnapshotProgress {
        if (err !is SketchbookError.Conflict) {
            return SnapshotProgress.Failed(uuid, "head write failed: ${err?.message}")
        }
        return when (policy.conflictMode) {
            is ConflictMode.BranchFork -> resolveBranchFork(uuid, treeId, kind, local, parentRev)
            is ConflictMode.Merge -> resolveMerge(uuid, treeId, kind, local)
        }
    }

    private suspend fun resolveBranchFork(
        uuid: ProjectUuid,
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        local: Manifest,
        parentRev: SnapshotRev?,
    ): SnapshotProgress {
        val refs = cloud.listManifests(treeId, kind, sinceRev = parentRev)
        val latest =
            refs.maxByOrNull { it.rev }
                ?: return SnapshotProgress.Failed(uuid, "conflict but cannot find new HEAD")
        val branchRev = SnapshotRev(latest.rev + 1)
        val ts = clock.now()
        val label = "auto-fork: $hostName-${ts.toEpochMilliseconds()}"
        val branch =
            local.copy(
                rev = branchRev,
                parentRev = parentRev,
                snapshotKind = SnapshotKind.Branch,
                label = label,
                timestamp = ts,
            )
        return cloud.appendManifestHead(treeId, kind, latest.generation, branch).fold(
            onSuccess = { SnapshotProgress.Saved(uuid, branchRev, SnapshotKind.Branch, label) },
            onFailure = { e -> SnapshotProgress.Failed(uuid, "branch write failed: ${e.message}") },
        )
    }

    /**
     * [ConflictMode.Merge] resolution: re-fetch the winning HEAD's manifest, run [mergeManifests],
     * re-CAS at the next rev. Up to [MERGE_MAX_RETRIES] attempts before bailing — protects against
     * hot-spinning when both machines save constantly.
     */
    private suspend fun resolveMerge(
        uuid: ProjectUuid,
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        local: Manifest,
    ): SnapshotProgress {
        var current: Manifest = local
        // Always re-list against the original parent — using `current.parentRev` would advance
        // each iteration to the previous merge's parent and miss the now-latest HEAD when the
        // re-CAS fails again.
        val originalParentRev = local.parentRev
        var failure: SnapshotProgress? = null
        var attempt = 0
        while (attempt < MERGE_MAX_RETRIES && failure == null) {
            attempt += 1
            val outcome = mergeAttempt(uuid, treeId, kind, current, originalParentRev)
            when (outcome) {
                is MergeAttempt.Done -> return outcome.progress
                is MergeAttempt.Retry -> current = outcome.merged
                is MergeAttempt.Fail -> failure = outcome.progress
            }
        }
        return failure ?: SnapshotProgress.Failed(
            uuid,
            "merge: CAS retries exhausted after $MERGE_MAX_RETRIES",
        )
    }

    private suspend fun mergeAttempt(
        uuid: ProjectUuid,
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        current: Manifest,
        originalParentRev: SnapshotRev?,
    ): MergeAttempt {
        val refs = cloud.listManifests(treeId, kind, sinceRev = originalParentRev)
        val latest =
            refs.maxByOrNull { it.rev }
                ?: return MergeAttempt.Fail(SnapshotProgress.Failed(uuid, "merge: conflict but cannot find new HEAD"))
        val winning =
            try {
                cloud.readManifest(treeId, kind, SnapshotRev(latest.rev))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                return MergeAttempt.Fail(SnapshotProgress.Failed(uuid, "merge: read winning manifest failed: ${e.message}"))
            }
        val merged = mergeManifests(local = current, remote = winning, clock = clock)
        val result = cloud.appendManifestHead(treeId, kind, latest.generation, merged)
        if (result.isSuccess) {
            return MergeAttempt.Done(SnapshotProgress.Saved(uuid, merged.rev, SnapshotKind.Auto, branchLabel = null))
        }
        val err = result.exceptionOrNull()
        return if (err is SketchbookError.Conflict) {
            MergeAttempt.Retry(merged)
        } else {
            MergeAttempt.Fail(SnapshotProgress.Failed(uuid, "merge write failed: ${err?.message}"))
        }
    }

    private sealed interface MergeAttempt {
        data class Done(
            val progress: SnapshotProgress,
        ) : MergeAttempt

        data class Retry(
            val merged: Manifest,
        ) : MergeAttempt

        data class Fail(
            val progress: SnapshotProgress,
        ) : MergeAttempt
    }

    private companion object {
        const val MERGE_MAX_RETRIES: Int = 3
    }
}

/**
 * Snapshot pipeline inputs: the project's identity, an on-disk view, and what we currently know
 * about the cloud HEAD.
 *
 * - [lastKnownManifest] enables the unchanged-file diff. `null` on the first sync.
 * - [expectedHeadGeneration] is the CAS token: pass [Generation.ZERO] on first write.
 */
data class PipelineInput(
    val uuid: ProjectUuid,
    val tree: WorkingTree,
    val lastKnownManifest: Manifest?,
    val expectedHeadGeneration: Generation?,
    /**
     * Tree identity used for cloud calls. v=1 wire still keys manifests by `project_uuid`; until
     * the migrator (commit 10) mints registry-backed ids, callers pass `TrackedTreeId(uuid.value)`
     * for [TrackedTreeKind.Project] trees so the legacy paths resolve unchanged.
     */
    val treeId: TrackedTreeId = TrackedTreeId(uuid.value),
    val kind: TrackedTreeKind = TrackedTreeKind.Project,
    /**
     * When true, blob uploads use [BlobScope.Private] keyed by [uuid]: the project's bytes never
     * dedup with any other project. Driven by `sync_state.self_contained` (managed via
     * `SettingsRepository.setSelfContained`).
     */
    val selfContained: Boolean = false,
    /**
     * Snapshot kind to record. Defaults to [SnapshotKind.Auto] so the existing watcher-driven
     * save path is unchanged. The Z3 quick-capture hotkey passes [SnapshotKind.Named] with a
     * user-supplied [label] to force a labeled timeline row. CAS-conflict still re-classifies
     * the resulting manifest as [SnapshotKind.Branch] regardless of this hint — divergence
     * always wins over Named.
     */
    val snapshotKind: SnapshotKind = SnapshotKind.Auto,
    /** Human-readable label attached to the manifest. Pairs with [snapshotKind] = Named. */
    val label: String? = null,
)
