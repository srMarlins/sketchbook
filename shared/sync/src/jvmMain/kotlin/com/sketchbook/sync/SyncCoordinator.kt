package com.sketchbook.sync

import com.sketchbook.catalog.SyncStateStore
import com.sketchbook.cloud.metadata.CollectionPath
import com.sketchbook.cloud.metadata.MetadataStore
import com.sketchbook.cloud.metadata.TreeDoc
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SnapshotRev
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Replaces the pre-Phase-3 per-project polling fan-out. Listens to `/users/{uid}/trees`
 * via [MetadataStore.observeCollection] and fires [PullPoller.pollOnce] on each delta where
 * the Firestore-reported `head_rev > sync_state.cloud_head_rev` for that project.
 *
 * **Why a Firestore listener vs polling:** sub-second cross-machine sync. The previous
 * design spent 1000 idle coroutines × 30 s × N hosts on `listManifests` calls just to
 * notice "did anything change?" — Firestore's server-pushed delta carries that signal
 * for free as soon as the writer's CAS lands.
 *
 * **Userspace, not user graph.** Lifetime is app-scope (single instance) rather than
 * per-user. UID transitions are handled by [collectLatest] over [userId] — sign-out
 * cancels the listener; sign-in starts a fresh one. Listener crashes log and stay
 * cancelled until the next UID flip; the alternative ("retry forever") would spin
 * against a known-failure mode (rules denying anonymous access, network down) and surface
 * nothing useful.
 *
 * @param pollerProvider builds the [PullPoller] for the current cloud config (null when
 *   cloud isn't ready). Per-listener-event call so the poller picks up the live cloud
 *   reference after sign-in.
 * @param onPostPull called after a successful `pollOnce` advances `cloud_head_rev`. The
 *   desktop graph wires this to `autoMaterializeAfterPull` so receiving machines apply
 *   the new manifest to the working tree when it's safe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncCoordinator(
    private val userId: Flow<String?>,
    private val metadataStore: MetadataStore,
    private val pollerProvider: () -> PullPoller?,
    private val syncStateStore: SyncStateStore,
    private val onPostPull: suspend (ProjectUuid) -> Unit = {},
    private val scope: CoroutineScope,
) {
    fun start(): Job =
        scope.launch {
            userId.distinctUntilChanged().collectLatest { uid ->
                if (uid == null) return@collectLatest
                // Per-UID cache of the last head_rev we observed for each tree. observeCollection
                // re-emits the FULL list on every change; without this cache, one tree's
                // head_rev advance would trigger an O(N) DB lookup against sync_state for every
                // unchanged sibling. The cache holds the value Firestore last reported — when
                // the next emission matches it for a given treeId, we know nothing changed and
                // can skip the DB call. Lives in the collectLatest scope so UID transitions
                // naturally clear it.
                val lastSeenHead = mutableMapOf<String, Long>()
                try {
                    metadataStore
                        .observeCollection(CollectionPath.trees(uid), TreeDoc.serializer())
                        .collect { entries ->
                            for (entry in entries) {
                                val previousHead = lastSeenHead[entry.id]
                                if (previousHead != null && previousHead == entry.value.head_rev) {
                                    continue
                                }
                                lastSeenHead[entry.id] = entry.value.head_rev
                                handleTreeEntry(entry.id, entry.value)
                            }
                        }
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Throwable) {
                    // Listener pipe broke — typically rules-denied or transient network. Don't
                    // retry-loop forever here; the userId.distinctUntilChanged outer collector
                    // re-emits the same uid only on a true transition, which is the natural
                    // recovery hook (sign-out → sign-in resubscribes). Phase C3 layers a
                    // bounded backoff/retry on top of this for transient blips.
                    System.err.println("[SyncCoordinator] listener crashed for uid=$uid: $e")
                }
            }
        }

    private suspend fun handleTreeEntry(
        treeId: String,
        doc: TreeDoc,
    ) {
        val uuid = ProjectUuid(treeId)
        val state = syncStateStore.stateOf(uuid)
        val localCloudHead = state?.cloudHeadRev ?: 0L
        // Doc reports a rev we haven't pulled yet — fetch the missing manifests.
        if (doc.head_rev <= localCloudHead) return
        val poller = pollerProvider() ?: return
        val pulled =
            poller.pollOnce(
                uuid,
                sinceRev = if (localCloudHead > 0) SnapshotRev(localCloudHead) else null,
            )
        if (pulled.isEmpty()) return
        // Single watermark advance per batch. PullPoller guarantees the returned list is a
        // contiguous successful prefix from sinceRev + 1, so the final rev is the safe
        // watermark — no need to fire the reactive cascade per snapshot (M3).
        syncStateStore.markCloudHead(uuid, pulled.last().rev.value)
        onPostPull(uuid)
    }
}
