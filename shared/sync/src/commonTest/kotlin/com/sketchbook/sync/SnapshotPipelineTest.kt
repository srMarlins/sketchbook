package com.sketchbook.sync

import com.sketchbook.cloud.Generation
import com.sketchbook.core.Manifest
import com.sketchbook.core.ManifestFile
import com.sketchbook.core.ManifestStats
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class SnapshotPipelineTest {

    private val uuid = ProjectUuid("01H-test-uuid")
    private val now = Instant.parse("2026-05-05T12:00:00Z")

    private fun pipeline(cloud: com.sketchbook.cloud.CloudBackend) = SnapshotPipeline(
        cloud = cloud,
        hostId = "host-a",
        hostName = "DesktopA",
        clock = FixedClock(now),
    )

    @Test
    fun firstSyncUploadsAllBlobsAndWritesManifest() = runTest {
        val cloud = FakeCloudBackend()
        val tree = FakeWorkingTree(
            mapOf(
                "Project.als" to FakeWorkingTree.FileBlob("v1".encodeToByteArray(), now),
                "Samples/loop.wav" to FakeWorkingTree.FileBlob("audio".encodeToByteArray(), now),
            ),
        )

        val events = pipeline(cloud).run(
            PipelineInput(
                uuid = uuid,
                tree = tree,
                lastKnownManifest = null,
                expectedHeadGeneration = Generation.ZERO,
            ),
        ).toList()

        val saved = events.filterIsInstance<SnapshotProgress.Saved>().single()
        assertEquals(SnapshotKind.Auto, saved.kind)
        assertEquals(SnapshotRev(1), saved.rev)
        assertEquals(2, cloud.blobsCount())
        assertEquals(1, cloud.manifestsFor(uuid).size)
        assertEquals(2, cloud.manifestsFor(uuid).single().files.size)
    }

    @Test
    fun unchangedFilesAreNotReuploaded() = runTest {
        val cloud = FakeCloudBackend()
        val mtime0 = now
        val unchangedHash = FakeWorkingTree.hashOf("v1".encodeToByteArray())
        val parent = Manifest(
            projectUuid = uuid,
            rev = SnapshotRev(1),
            timestamp = now,
            hostId = "host-a",
            hostName = "DesktopA",
            kind = SnapshotKind.Auto,
            files = mapOf(
                "Project.als" to ManifestFile(unchangedHash, "v1".encodeToByteArray().size.toLong(), mtime0),
            ),
            stats = ManifestStats(1, 2, 2),
        )
        val parentGen = cloud.seedManifest(uuid, parent)

        val tree = FakeWorkingTree(
            mapOf("Project.als" to FakeWorkingTree.FileBlob("v1".encodeToByteArray(), mtime0)),
        )

        val events = pipeline(cloud).run(
            PipelineInput(uuid, tree, parent, parentGen),
        ).toList()

        val saved = events.filterIsInstance<SnapshotProgress.Saved>().single()
        assertEquals(SnapshotRev(2), saved.rev)
        // Parent already had the blob; we shouldn't have uploaded a new one.
        assertEquals(0, cloud.blobsCount())
    }

    @Test
    fun dedupSkipsUploadWhenBlobAlreadyInCloud() = runTest {
        val cloud = FakeCloudBackend()
        val bytes = "shared".encodeToByteArray()
        val hash = FakeWorkingTree.hashOf(bytes)
        // Pretend another project already pushed this blob.
        cloud.putBlob(hash, FakeWorkingTree(mapOf("x" to FakeWorkingTree.FileBlob(bytes, now))).read("x"), bytes.size.toLong())
        val before = cloud.blobsCount()

        val tree = FakeWorkingTree(mapOf("Project.als" to FakeWorkingTree.FileBlob(bytes, now)))
        val events = pipeline(cloud).run(
            PipelineInput(uuid, tree, null, Generation.ZERO),
        ).toList()

        assertNotNull(events.filterIsInstance<SnapshotProgress.Saved>().singleOrNull())
        assertEquals(before, cloud.blobsCount(), "no new blob should be uploaded")
    }

    @Test
    fun divergenceWritesBranchManifestWithAutoForkLabel() = runTest {
        val cloud = FakeCloudBackend()
        val mtime0 = now
        val parent = Manifest(
            projectUuid = uuid,
            rev = SnapshotRev(1),
            timestamp = now,
            hostId = "host-a",
            hostName = "DesktopA",
            kind = SnapshotKind.Auto,
            files = emptyMap(),
            stats = ManifestStats(0, 0, 0),
        )
        val parentGen = cloud.seedManifest(uuid, parent)

        // Another host advances HEAD to rev 2 first.
        val intruder = Manifest(
            projectUuid = uuid,
            rev = SnapshotRev(2),
            parentRev = SnapshotRev(1),
            timestamp = now,
            hostId = "host-b",
            hostName = "MacStudio",
            kind = SnapshotKind.Auto,
            files = emptyMap(),
            stats = ManifestStats(0, 0, 0),
        )
        cloud.seedManifest(uuid, intruder)

        // Our pipeline still thinks parent generation is `parentGen` → CAS will fail.
        val tree = FakeWorkingTree(
            mapOf("Project.als" to FakeWorkingTree.FileBlob("local".encodeToByteArray(), mtime0)),
        )
        val events = pipeline(cloud).run(
            PipelineInput(uuid, tree, parent, parentGen),
        ).toList()

        val saved = events.filterIsInstance<SnapshotProgress.Saved>().single()
        assertEquals(SnapshotKind.Branch, saved.kind)
        assertEquals(SnapshotRev(3), saved.rev)
        val label = saved.branchLabel
        assertTrue(label != null && label.startsWith("auto-fork: DesktopA-"))
        // 3 manifests total: original + intruder + our branch.
        assertEquals(3, cloud.manifestsFor(uuid).size)
    }

    @Test
    fun selfContainedUsesPrivateBlobScopeAndSkipsSharedDedup() = runTest {
        val cloud = FakeCloudBackend()
        val bytes = "shared".encodeToByteArray()
        val hash = FakeWorkingTree.hashOf(bytes)
        // A different project already populated the SHARED pool with this blob.
        cloud.putBlob(hash, FakeWorkingTree(mapOf("x" to FakeWorkingTree.FileBlob(bytes, now))).read("x"), bytes.size.toLong())
        val sharedBefore = cloud.sharedBlobsCount()

        val tree = FakeWorkingTree(mapOf("Project.als" to FakeWorkingTree.FileBlob(bytes, now)))
        val events = pipeline(cloud).run(
            PipelineInput(
                uuid = uuid,
                tree = tree,
                lastKnownManifest = null,
                expectedHeadGeneration = Generation.ZERO,
                selfContained = true,
            ),
        ).toList()

        assertNotNull(events.filterIsInstance<SnapshotProgress.Saved>().singleOrNull())
        // Shared pool unchanged — the project did NOT dedup against it.
        assertEquals(sharedBefore, cloud.sharedBlobsCount(), "shared pool should be untouched")
        // Private pool got the upload.
        assertEquals(1, cloud.privateBlobsCount(uuid))
        // Manifest carries the flag for downstream readers (PullPoller, sync_state hydrate).
        assertEquals(true, cloud.manifestsFor(uuid).single().selfContained)
    }

    @Test
    fun selfContainedDedupesWithinProjectAcrossRuns() = runTest {
        val cloud = FakeCloudBackend()
        val bytes = "v1".encodeToByteArray()
        val tree = FakeWorkingTree(mapOf("Project.als" to FakeWorkingTree.FileBlob(bytes, now)))

        val firstEvents = pipeline(cloud).run(
            PipelineInput(uuid, tree, null, Generation.ZERO, selfContained = true),
        ).toList()
        assertNotNull(firstEvents.filterIsInstance<SnapshotProgress.Saved>().firstOrNull())
        val privateAfterFirst = cloud.privateBlobsCount(uuid)
        assertEquals(1, privateAfterFirst)

        // Re-sync the same project: same hash, should NOT re-upload (private-pool dedup).
        val parent = cloud.manifestsFor(uuid).single()
        pipeline(cloud).run(
            PipelineInput(uuid, tree, parent, Generation("1"), selfContained = true),
        ).toList()
        assertEquals(privateAfterFirst, cloud.privateBlobsCount(uuid))
    }

    @Test
    fun userLibraryKindSkipsLeaseStep() = runTest {
        val cloud = FakeCloudBackend()
        val tree = FakeWorkingTree(
            mapOf("Defaults/Default.als" to FakeWorkingTree.FileBlob("template".encodeToByteArray(), now)),
        )
        val treeId = TrackedTreeId("tt-userlib")

        val events = pipeline(cloud).run(
            PipelineInput(
                uuid = uuid,
                tree = tree,
                lastKnownManifest = null,
                expectedHeadGeneration = Generation.ZERO,
                treeId = treeId,
                kind = TrackedTreeKind.UserLibrary,
            ),
        ).toList()

        assertNotNull(events.filterIsInstance<SnapshotProgress.Saved>().singleOrNull())
        // No LeaseAcquired emitted — the lease step was skipped entirely.
        assertFalse(events.any { it is SnapshotProgress.LeaseAcquired || it is SnapshotProgress.LeaseHeld })
    }

    @Test
    fun mergeConflictResolvesByMergingWithRemote() = runTest {
        val cloud = FakeCloudBackend()
        val treeId = TrackedTreeId("tt-userlib-merge")
        val kind = TrackedTreeKind.UserLibrary

        // Step 1: write an initial manifest at rev 1 with file "a".
        val first = pipeline(cloud).run(
            PipelineInput(
                uuid = uuid,
                tree = FakeWorkingTree(mapOf("a" to FakeWorkingTree.FileBlob("a-bytes".encodeToByteArray(), now))),
                lastKnownManifest = null,
                expectedHeadGeneration = Generation.ZERO,
                treeId = treeId,
                kind = kind,
            ),
        ).toList()
        val saved1 = first.filterIsInstance<SnapshotProgress.Saved>().single()
        assertEquals(SnapshotRev(1), saved1.rev)
        val rev1 = cloud.manifestsForTree(treeId, kind).single { it.rev == SnapshotRev(1) }
        // Track parent generation of rev 1 — the local pipeline has this as expectedHead.
        val rev1Gen = cloud.listManifests(treeId, kind, sinceRev = null).single { it.rev == 1L }.generation

        // Step 2: another machine seeds a winning rev 2 with file "b".
        val intruderHash = FakeWorkingTree.hashOf("b-bytes".encodeToByteArray())
        val intruderManifest = Manifest(
            projectUuid = uuid,
            rev = SnapshotRev(2),
            parentRev = SnapshotRev(1),
            timestamp = now,
            hostId = "host-b",
            hostName = "MacStudio",
            kind = SnapshotKind.Auto,
            files = mapOf("b" to ManifestFile(intruderHash, "b-bytes".encodeToByteArray().size.toLong(), now)),
            stats = ManifestStats(1, "b-bytes".encodeToByteArray().size.toLong(), 0),
        )
        cloud.seedTreeManifest(treeId, kind, intruderManifest)

        // Step 3: local pipeline thinks parent generation is rev1Gen → CAS will fail → merge resolves.
        // Local tree adds file "c" on top of "a".
        val tree = FakeWorkingTree(
            mapOf(
                "a" to FakeWorkingTree.FileBlob("a-bytes".encodeToByteArray(), now),
                "c" to FakeWorkingTree.FileBlob("c-bytes".encodeToByteArray(), now),
            ),
        )
        val events = pipeline(cloud).run(
            PipelineInput(
                uuid = uuid,
                tree = tree,
                lastKnownManifest = rev1,
                expectedHeadGeneration = rev1Gen,
                treeId = treeId,
                kind = kind,
            ),
        ).toList()

        val saved = events.filterIsInstance<SnapshotProgress.Saved>().single()
        assertEquals(SnapshotKind.Auto, saved.kind)
        assertEquals(SnapshotRev(3), saved.rev)
        val merged = cloud.manifestsForTree(treeId, kind).single { it.rev == SnapshotRev(3) }
        assertEquals(setOf("a", "b", "c"), merged.files.keys)
        assertEquals(SnapshotRev(2), merged.parentRev)
    }

    @Test
    fun mergeRetriesExhaustWhenRemoteKeepsAdvancing() = runTest {
        val treeId = TrackedTreeId("tt-userlib-thrash")
        val kind = TrackedTreeKind.UserLibrary
        // A backend that keeps reporting CAS conflict — every time we try to write merged HEAD,
        // the world is "newer" than what we just merged against.
        val baseCloud = FakeCloudBackend()
        // Seed parent rev 1 + intruder rev 2.
        val parent = Manifest(
            projectUuid = uuid,
            rev = SnapshotRev(1), parentRev = null, timestamp = now,
            hostId = "host-a", hostName = "DesktopA",
            kind = SnapshotKind.Auto,
            files = emptyMap(),
            stats = ManifestStats(0, 0, 0),
        )
        val parentGen = baseCloud.seedTreeManifest(treeId, kind, parent)
        baseCloud.seedTreeManifest(
            treeId,
            kind,
            parent.copy(rev = SnapshotRev(2), parentRev = SnapshotRev(1)),
        )

        // Wrap in a backend that always rejects the next appendManifestHead with Conflict and
        // bumps rev each time, so the retry loop never converges.
        val thrashing = ThrashingCloudBackend(baseCloud, treeId, kind)

        val tree = FakeWorkingTree(
            mapOf("Project.als" to FakeWorkingTree.FileBlob("local".encodeToByteArray(), now)),
        )
        val events = pipeline(thrashing).run(
            PipelineInput(
                uuid = uuid,
                tree = tree,
                lastKnownManifest = parent,
                expectedHeadGeneration = parentGen,
                treeId = treeId,
                kind = kind,
            ),
        ).toList()

        val failed = events.filterIsInstance<SnapshotProgress.Failed>().single()
        assertTrue(
            failed.reason.contains("retries exhausted"),
            "expected retries-exhausted failure, got: ${failed.reason}",
        )
    }

    @Test
    fun heldLeaseAbortsWithoutUpload() = runTest {
        val cloud = FakeCloudBackend()
        cloud.forceLock(
            uuid,
            com.sketchbook.cloud.LeaseLock(
                ownerHostId = "host-b",
                ownerHostName = "MacStudio",
                acquiredAt = now,
                expiresAt = now,
            ),
        )

        val tree = FakeWorkingTree(
            mapOf("Project.als" to FakeWorkingTree.FileBlob("v1".encodeToByteArray(), now)),
        )
        val events = pipeline(cloud).run(
            PipelineInput(uuid, tree, null, Generation.ZERO),
        ).toList()

        assertTrue(events.any { it is SnapshotProgress.LeaseHeld })
        assertTrue(events.last() is SnapshotProgress.Failed)
        assertEquals(0, cloud.blobsCount())
        assertEquals(0, cloud.manifestsFor(uuid).size)
    }
}
