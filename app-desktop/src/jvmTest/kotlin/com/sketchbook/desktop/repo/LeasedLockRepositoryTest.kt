package com.sketchbook.desktop.repo

import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.SyncStateStore
import com.sketchbook.cloud.BlobScope
import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.Generation
import com.sketchbook.cloud.LeaseAcquireResult
import com.sketchbook.cloud.LeaseLock
import com.sketchbook.cloud.LeaseRefreshResult
import com.sketchbook.cloud.ManifestRef
import com.sketchbook.core.BlobHash
import com.sketchbook.core.Manifest
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SnapshotRev
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.LockStatus
import com.sketchbook.repo.impl.InMemoryJournalRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LeasedLockRepositoryTest {

    private val uuid = ProjectUuid("01H-test")

    @Test
    fun forceTakeOnEmptyAcquiresAndFlipsToOurs() = runTest {
        val cloud = FakeLockCloud()
        val store = SyncStateStore(CatalogDb.openInMemory().catalog)
        val repo = LeasedLockRepository(
            cloud = { cloud },
            syncStateStore = store,
            hostId = "host-a",
            hostName = "DesktopA",
            scope = backgroundScope,
        )
        val r = repo.forceTake(uuid)
        assertTrue(r.isSuccess, "forceTake failed: ${r.exceptionOrNull()}")
        val status = repo.observe(uuid).first()
        assertTrue(status is LockStatus.Ours, "expected Ours, got $status")
    }

    @Test
    fun forceTakeAppendsForceTakeLockJournalEntry() = runTest {
        val cloud = FakeLockCloud()
        // Seed a project + identity so projectIdFor(uuid) resolves to a real ProjectId.
        val handle = CatalogDb.openInMemory()
        val catalog = handle.catalog
        catalog.catalogQueries.insertOrReplaceProject(
            path = "/tmp/p.als", name = "p", parent_dir = "/tmp",
            tempo = null, time_sig_num = null, time_sig_den = null, key = null,
            track_count = 0, audio_tracks = 0, midi_tracks = 0, return_tracks = 0,
            live_version = null, last_modified = 0.0, last_scanned = 0.0,
            parse_status = "ok", parse_error = null, mac_paths_count = null,
            effort_score = null, effort_breakdown = null, file_size_bytes = 0,
        )
        val projectId = catalog.catalogQueries.selectProjectIdByPath("/tmp/p.als").executeAsOne()
        catalog.catalogQueries.insertProjectIdentityIfAbsent(
            project_id = projectId,
            uuid = uuid.value,
            created_at = "2026-05-06T00:00:00Z",
        )
        val store = SyncStateStore(catalog)
        val journal = InMemoryJournalRepository()
        val repo = LeasedLockRepository(
            cloud = { cloud },
            syncStateStore = store,
            hostId = "host-a",
            hostName = "DesktopA",
            scope = backgroundScope,
            journal = journal,
        )
        val r = repo.forceTake(uuid)
        assertTrue(r.isSuccess)
        val entries = journal.observeRecent().first()
        assertEquals(1, entries.size)
        assertTrue(entries.single().action is ActionRecord.ForceTakeLock)
        assertEquals(ProjectId(projectId), entries.single().projectId)
    }

    @Test
    fun forceTakeWithoutCloudFails() = runTest {
        val store = SyncStateStore(CatalogDb.openInMemory().catalog)
        val repo = LeasedLockRepository(
            cloud = { null },
            syncStateStore = store,
            hostId = "host-a",
            hostName = "DesktopA",
            scope = backgroundScope,
        )
        val r = repo.forceTake(uuid)
        assertTrue(r.isFailure)
    }
}

/** Minimal CloudBackend supporting the lock-only paths LeasedLockRepository exercises. */
private class FakeLockCloud : CloudBackend {
    private var lock: Pair<LeaseLock, Generation>? = null
    private var nextGen = 1L

    override suspend fun headBlob(hash: BlobHash, scope: BlobScope) = false
    override suspend fun putBlob(hash: BlobHash, source: RawSource, size: Long, scope: BlobScope) {}
    override suspend fun getBlob(hash: BlobHash, scope: BlobScope): RawSource = error("not used")
    override suspend fun readManifest(uuid: ProjectUuid, rev: SnapshotRev): Manifest = error("not used")
    override suspend fun listManifests(uuid: ProjectUuid, sinceRev: SnapshotRev?) = emptyList<ManifestRef>()
    override suspend fun appendManifestHead(uuid: ProjectUuid, expectedHead: Generation?, manifest: Manifest) = Result.failure<Generation>(error("not used"))
    override suspend fun acquireLock(uuid: ProjectUuid, lock: LeaseLock): LeaseAcquireResult {
        val current = this.lock
        return if (current == null) {
            val gen = Generation((nextGen++).toString())
            this.lock = lock to gen
            LeaseAcquireResult.Acquired(gen)
        } else {
            LeaseAcquireResult.Held(current.first, current.second)
        }
    }
    override suspend fun refreshLock(uuid: ProjectUuid, lock: LeaseLock, expected: Generation): LeaseRefreshResult {
        val current = this.lock ?: return LeaseRefreshResult.Stale
        if (current.second != expected) return LeaseRefreshResult.Stale
        val gen = Generation((nextGen++).toString())
        this.lock = lock to gen
        return LeaseRefreshResult.Refreshed(gen)
    }
    override suspend fun releaseLock(uuid: ProjectUuid, expected: Generation) {
        val current = this.lock ?: return
        if (current.second == expected) this.lock = null
    }
}
