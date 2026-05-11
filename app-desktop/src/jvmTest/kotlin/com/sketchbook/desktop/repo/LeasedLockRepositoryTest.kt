package com.sketchbook.desktop.repo

import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.SyncStateStore
import com.sketchbook.cloud.metadata.InMemoryMetadataStore
import com.sketchbook.cloud.metadata.MetadataStore
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectUuid
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.LockStatus
import com.sketchbook.repo.impl.InMemoryJournalRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LeasedLockRepositoryTest {
    private val uuid = ProjectUuid("01H-test")

    @Test
    fun forceTakeOnEmptyAcquiresAndFlipsToOurs() =
        runTest {
            val metadataStore = InMemoryMetadataStore()
            val store = SyncStateStore(CatalogDb.openInMemory().catalog)
            val repo = newRepo(metadataStore, store)
            val r = repo.forceTake(uuid)
            assertTrue(r.isSuccess, "forceTake failed: ${r.exceptionOrNull()}")
            // The lock-doc listener emits the doc shape; await the first emission whose
            // status is Ours. Other emissions may have been Free before the listener saw the
            // post-forceTake write.
            val status: LockStatus = awaitOurs(repo)
            assertTrue(status is LockStatus.Ours, "expected Ours, got $status")
        }

    @Test
    fun forceTakeAppendsForceTakeLockJournalEntry() =
        runTest {
            val handle = CatalogDb.openInMemory()
            val catalog = handle.catalog
            catalog.catalogQueries.insertOrReplaceProject(
                path = "/tmp/p.als",
                name = "p",
                parent_dir = "/tmp",
                tempo = null,
                time_sig_num = null,
                time_sig_den = null,
                key = null,
                track_count = 0,
                audio_tracks = 0,
                midi_tracks = 0,
                return_tracks = 0,
                live_version = null,
                last_modified = 0.0,
                last_scanned = 0.0,
                parse_status = "ok",
                parse_error = null,
                mac_paths_count = null,
                effort_score = null,
                effort_breakdown = null,
                file_size_bytes = 0,
            )
            val projectId = catalog.catalogQueries.selectProjectIdByPath("/tmp/p.als").executeAsOne()
            catalog.catalogQueries.insertProjectIdentityIfAbsent(
                project_id = projectId,
                uuid = uuid.value,
                created_at = "2026-05-06T00:00:00Z",
            )
            val syncStore = SyncStateStore(catalog)
            val journal = InMemoryJournalRepository()
            val metadataStore = InMemoryMetadataStore()
            val repo = newRepo(metadataStore, syncStore, journal = journal)

            val r = repo.forceTake(uuid)
            assertTrue(r.isSuccess)
            val entries = journal.observeRecent().first()
            assertEquals(1, entries.size)
            assertTrue(entries.single().action is ActionRecord.ForceTakeLock)
            assertEquals(ProjectId(projectId), entries.single().projectId)
        }

    @Test
    fun forceTakeWithoutCloudFails() =
        runTest {
            val store = SyncStateStore(CatalogDb.openInMemory().catalog)
            val repo =
                LeasedLockRepository(
                    metadataStore = { null },
                    userIdFlow = MutableStateFlow("test-user"),
                    syncStateStore = store,
                    hostId = "host-a",
                    hostName = "DesktopA",
                    scope = backgroundScope,
                )
            val r = repo.forceTake(uuid)
            assertTrue(r.isFailure)
        }

    @Test
    fun forceTakeWithoutUserFails() =
        runTest {
            val store = SyncStateStore(CatalogDb.openInMemory().catalog)
            val repo =
                LeasedLockRepository(
                    metadataStore = { InMemoryMetadataStore() },
                    userIdFlow = MutableStateFlow(null),
                    syncStateStore = store,
                    hostId = "host-a",
                    hostName = "DesktopA",
                    scope = backgroundScope,
                )
            val r = repo.forceTake(uuid)
            assertTrue(r.isFailure)
        }

    @Test
    fun signedOutUidEmissionStartsListenerOnSubsequentSignIn() =
        runTest {
            val metadataStore = InMemoryMetadataStore()
            val syncStore = SyncStateStore(CatalogDb.openInMemory().catalog)
            val userIdFlow = MutableStateFlow<String?>(null)
            val repo =
                LeasedLockRepository(
                    metadataStore = { metadataStore },
                    userIdFlow = userIdFlow,
                    syncStateStore = syncStore,
                    hostId = "host-a",
                    hostName = "DesktopA",
                    scope = backgroundScope,
                )

            // Pre-sign-in observe() — listener can't start (uid is null) but the status flow
            // still exists. Should yield Free.
            assertEquals(LockStatus.Free, repo.observe(uuid).first())

            // Sign in. The init-block observer's drop(1) skips the initial null replay, then
            // emits "user-a" — which fires resetState, clearing the cache; the next observe
            // call rebuilds the PerUuid with a real listener bound to user-a's path. Without
            // K5's reset behavior the listener never starts after sign-in.
            userIdFlow.value = "user-a"

            // A forceTake exercises the post-sign-in path end-to-end; the listener now sees
            // the doc and the status flips to Ours.
            assertTrue(repo.forceTake(uuid).isSuccess)
            assertTrue(awaitOurs(repo) is LockStatus.Ours)
        }

    @Test
    fun uidTransitionTearsDownPerUuidJobsAndResetsStatus() =
        runTest {
            val metadataStore = InMemoryMetadataStore()
            val syncStore = SyncStateStore(CatalogDb.openInMemory().catalog)
            val userIdFlow = MutableStateFlow<String?>("user-a")
            val repo =
                LeasedLockRepository(
                    metadataStore = { metadataStore },
                    userIdFlow = userIdFlow,
                    syncStateStore = syncStore,
                    hostId = "host-a",
                    hostName = "DesktopA",
                    scope = backgroundScope,
                )

            // Acquire as user-a — listener subscription + status flow are now bound to user-a's
            // Firestore path.
            assertTrue(repo.forceTake(uuid).isSuccess)
            assertTrue(awaitOurs(repo) is LockStatus.Ours)

            // Switch UIDs. The init-block observer should cancel the prior listener and clear
            // the cache so the next observe(uuid) restarts a fresh listener against user-b's
            // path — which has no doc — and emits Free.
            userIdFlow.value = "user-b"
            val nextStatus =
                repo
                    .observe(uuid)
                    .firstOrNull { it is LockStatus.Free }
                    ?: error("listener never re-emitted Free after UID change")
            assertTrue(nextStatus is LockStatus.Free)
        }

    private fun kotlinx.coroutines.test.TestScope.newRepo(
        metadataStore: MetadataStore,
        syncStateStore: SyncStateStore,
        journal: InMemoryJournalRepository? = null,
    ): LeasedLockRepository =
        LeasedLockRepository(
            metadataStore = { metadataStore },
            userIdFlow = MutableStateFlow("test-user"),
            syncStateStore = syncStateStore,
            hostId = "host-a",
            hostName = "DesktopA",
            scope = backgroundScope,
            journal = journal,
        )

    /**
     * The lock-doc listener emits asynchronously; await up to a few yields for the post-
     * forceTake Ours emission. The cancellable [firstOrNull] under a timeout pattern would
     * suffice for production, but in a TestScope `runTest`'s virtual scheduler is enough.
     */
    private suspend fun awaitOurs(repo: LeasedLockRepository): LockStatus {
        // Drop the initial Free emission and wait for the next non-Free status.
        return repo
            .observe(uuid)
            .firstOrNull { it is LockStatus.Ours }
            ?: error("listener never emitted Ours")
    }
}
