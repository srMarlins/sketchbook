package com.sketchbook.sync

import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.SyncStateStore
import com.sketchbook.cloud.metadata.DocPath
import com.sketchbook.cloud.metadata.InMemoryMetadataStore
import com.sketchbook.cloud.metadata.TreeDoc
import com.sketchbook.core.Manifest
import com.sketchbook.core.ManifestStats
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.SnapshotRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Listener-driven SyncCoordinator integration: when the TreeDoc's head_rev advances on the
 * Firestore side, pollOnce fires and sync_state.cloud_head_rev catches up.
 *
 * Uses the InMemoryMetadataStore + FakeCloudBackend + a real catalog DB for the assertion
 * surface; runs in [runTest]'s virtual scheduler so we can yield enough for the listener to
 * observe.
 */
class SyncCoordinatorTest {
    private val uid = "test-user"
    private val uuid = ProjectUuid("01H-coordinator-test")
    private val now = Instant.parse("2026-05-10T12:00:00Z")

    @Test
    fun headRevAdvanceTriggersPollAndCloudHeadRevCatchesUp() =
        runTest {
            val handle = CatalogDb.openInMemory()
            val store = SyncStateStore(handle.catalog)
            val metadataStore = InMemoryMetadataStore()
            val cloud = FakeCloudBackend()
            val snapshots = RecordingSnapshotRepository()
            val poller = PullPoller(cloud, snapshots)
            val userIdFlow: MutableStateFlow<String?> = MutableStateFlow(uid)

            // Seed the manifest in cloud + the matching tree doc with head_rev = 1.
            val manifest =
                Manifest(
                    projectUuid = uuid,
                    rev = SnapshotRev(1),
                    parentRev = null,
                    timestamp = now,
                    hostId = "host-b",
                    hostName = "MacStudio",
                    kind = SnapshotKind.Auto,
                    files = emptyMap(),
                    stats = ManifestStats(0, 0, 0),
                )
            cloud.seedManifest(uuid, manifest)
            metadataStore.setDoc(
                DocPath.tree(uid, uuid.value),
                TreeDoc(
                    owner_user_id = uid,
                    display_name = "test",
                    created_at = now,
                    created_by_host = "host-b",
                    head_rev = 1,
                    head_gen = "1",
                    head_updated_at = now,
                    head_updated_by_host = "host-b",
                ),
                TreeDoc.serializer(),
            )

            val coordinator =
                SyncCoordinator(
                    userId = userIdFlow.asStateFlow(),
                    metadataStore = metadataStore,
                    pollerProvider = { poller },
                    syncStateStore = store,
                    scope = backgroundScope,
                )
            coordinator.start()
            // Yield enough that the collectLatest + listener subscription completes.
            delay(10)

            val state = store.stateOf(uuid)
            assertTrue(state != null, "sync_state should have been written")
            assertEquals(1L, state.cloudHeadRev)
            assertEquals(listOf(SnapshotRev(1)), snapshots.recorded.map { it.rev })
        }

    @Test
    fun staleHeadRevSkipsPolling() =
        runTest {
            val handle = CatalogDb.openInMemory()
            val store = SyncStateStore(handle.catalog)
            // Pre-seed sync_state so cloud_head_rev == 5 already.
            store.markCloudHead(uuid, 5L)
            val metadataStore = InMemoryMetadataStore()
            val cloud = FakeCloudBackend()
            val snapshots = RecordingSnapshotRepository()
            val poller = PullPoller(cloud, snapshots)
            val userIdFlow: MutableStateFlow<String?> = MutableStateFlow(uid)

            metadataStore.setDoc(
                DocPath.tree(uid, uuid.value),
                TreeDoc(
                    owner_user_id = uid,
                    display_name = "test",
                    created_at = now,
                    created_by_host = "host-b",
                    // Doc reports rev <= local — should be a no-op.
                    head_rev = 3,
                    head_gen = "3",
                    head_updated_at = now,
                    head_updated_by_host = "host-b",
                ),
                TreeDoc.serializer(),
            )

            val coordinator =
                SyncCoordinator(
                    userId = userIdFlow.asStateFlow(),
                    metadataStore = metadataStore,
                    pollerProvider = { poller },
                    syncStateStore = store,
                    scope = backgroundScope,
                )
            coordinator.start()
            delay(10)

            assertEquals(emptyList(), snapshots.recorded)
            assertEquals(5L, store.stateOf(uuid)?.cloudHeadRev)
        }

    @Test
    fun signedOutUidDoesNotListen() =
        runTest {
            val handle = CatalogDb.openInMemory()
            val store = SyncStateStore(handle.catalog)
            val metadataStore = InMemoryMetadataStore()
            val cloud = FakeCloudBackend()
            val snapshots = RecordingSnapshotRepository()
            val poller = PullPoller(cloud, snapshots)
            val userIdFlow: MutableStateFlow<String?> = MutableStateFlow(null)

            // Seed a tree doc as if a previous session had observed it.
            metadataStore.setDoc(
                DocPath.tree("other-user", uuid.value),
                TreeDoc(
                    owner_user_id = "other-user",
                    display_name = "x",
                    created_at = now,
                    created_by_host = "h",
                    head_rev = 7,
                    head_gen = "7",
                    head_updated_at = now,
                    head_updated_by_host = "h",
                ),
                TreeDoc.serializer(),
            )

            val coordinator =
                SyncCoordinator(
                    userId = userIdFlow.asStateFlow(),
                    metadataStore = metadataStore,
                    pollerProvider = { poller },
                    syncStateStore = store,
                    scope = backgroundScope,
                )
            coordinator.start()
            delay(10)

            assertEquals(emptyList(), snapshots.recorded)
        }
}

private class RecordingSnapshotRepository : SnapshotRepository {
    val recorded = mutableListOf<com.sketchbook.core.Snapshot>()

    override fun observeHistory(uuid: ProjectUuid): Flow<List<com.sketchbook.core.Snapshot>> =
        MutableStateFlow(emptyList())

    override suspend fun recordSnapshot(
        snapshot: com.sketchbook.core.Snapshot,
        manifestPath: String,
        manifestHash: String,
    ): Result<Unit> {
        recorded += snapshot
        return Result.success(Unit)
    }

    override suspend fun setSnapshotLabel(
        uuid: ProjectUuid,
        rev: SnapshotRev,
        label: String?,
    ): Result<JournalEntry> = Result.failure(NotImplementedError())

    override suspend fun materializeAt(
        uuid: ProjectUuid,
        rev: SnapshotRev,
    ): Result<Unit> = Result.success(Unit)
}
