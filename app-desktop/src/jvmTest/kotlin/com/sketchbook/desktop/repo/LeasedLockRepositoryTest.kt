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
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SnapshotRev
import com.sketchbook.repo.LockStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.RawSource
import kotlin.test.Test
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
    override suspend fun appendManifestHead(uuid: ProjectUuid, expectedHead: Generation?, manifest: Manifest) =
        Result.failure<Generation>(error("not used"))
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
