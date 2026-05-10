package com.sketchbook.sync

import com.sketchbook.cloud.Generation
import com.sketchbook.cloud.metadata.DocPath
import com.sketchbook.cloud.metadata.InMemoryMetadataStore
import com.sketchbook.cloud.metadata.LockDoc
import com.sketchbook.core.Manifest
import com.sketchbook.core.UserId
import com.sketchbook.core.ManifestFile
import com.sketchbook.core.ManifestStats
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class SnapshotPipelineTest {
    private val uuid = ProjectUuid("01H-test-uuid")
    private val now = Instant.parse("2026-05-05T12:00:00Z")

    private val userId = UserId("test-user")

    private fun pipeline(
        cloud: FakeCloudBackend,
        metadataStore: InMemoryMetadataStore = InMemoryMetadataStore(clock = FixedClock(now)),
    ) = SnapshotPipeline(
        cloud = cloud,
        metadataStore = metadataStore,
        ownerUserId = userId,
        hostId = "host-a",
        hostName = "DesktopA",
        clock = FixedClock(now),
    )

    @Test
    fun firstSyncUploadsAllBlobsAndWritesManifest() =
        runTest {
            val cloud = FakeCloudBackend()
            val tree =
                FakeWorkingTree(
                    mapOf(
                        "Project.als" to FakeWorkingTree.FileBlob("v1".encodeToByteArray(), now),
                        "Samples/loop.wav" to FakeWorkingTree.FileBlob("audio".encodeToByteArray(), now),
                    ),
                )

            val events =
                pipeline(cloud)
                    .run(
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
            assertEquals(
                2,
                cloud
                    .manifestsFor(uuid)
                    .single()
                    .files.size,
            )
        }

    @Test
    fun unchangedFilesAreNotReuploaded() =
        runTest {
            val cloud = FakeCloudBackend()
            val mtime0 = now
            val unchangedHash = FakeWorkingTree.hashOf("v1".encodeToByteArray())
            val parent =
                Manifest(
                    projectUuid = uuid,
                    rev = SnapshotRev(1),
                    timestamp = now,
                    hostId = "host-a",
                    hostName = "DesktopA",
                    kind = SnapshotKind.Auto,
                    files =
                        mapOf(
                            "Project.als" to ManifestFile(unchangedHash, "v1".encodeToByteArray().size.toLong(), mtime0),
                        ),
                    stats = ManifestStats(1, 2, 2),
                )
            val parentGen = cloud.seedManifest(uuid, parent)

            val tree =
                FakeWorkingTree(
                    mapOf("Project.als" to FakeWorkingTree.FileBlob("v1".encodeToByteArray(), mtime0)),
                )

            val events =
                pipeline(cloud)
                    .run(
                        PipelineInput(uuid, tree, parent, parentGen),
                    ).toList()

            val saved = events.filterIsInstance<SnapshotProgress.Saved>().single()
            assertEquals(SnapshotRev(2), saved.rev)
            // Parent already had the blob; we shouldn't have uploaded a new one.
            assertEquals(0, cloud.blobsCount())
        }

    @Test
    fun dedupSkipsUploadWhenBlobAlreadyInCloud() =
        runTest {
            val cloud = FakeCloudBackend()
            val bytes = "shared".encodeToByteArray()
            val hash = FakeWorkingTree.hashOf(bytes)
            // Pretend another project already pushed this blob.
            cloud.putBlob(hash, FakeWorkingTree(mapOf("x" to FakeWorkingTree.FileBlob(bytes, now))).read("x"), bytes.size.toLong())
            val before = cloud.blobsCount()

            val tree = FakeWorkingTree(mapOf("Project.als" to FakeWorkingTree.FileBlob(bytes, now)))
            val events =
                pipeline(cloud)
                    .run(
                        PipelineInput(uuid, tree, null, Generation.ZERO),
                    ).toList()

            assertNotNull(events.filterIsInstance<SnapshotProgress.Saved>().singleOrNull())
            assertEquals(before, cloud.blobsCount(), "no new blob should be uploaded")
        }

    @Test
    fun divergenceWritesBranchManifestWithAutoForkLabel() =
        runTest {
            val cloud = FakeCloudBackend()
            val mtime0 = now
            val parent =
                Manifest(
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
            val intruder =
                Manifest(
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
            val tree =
                FakeWorkingTree(
                    mapOf("Project.als" to FakeWorkingTree.FileBlob("local".encodeToByteArray(), mtime0)),
                )
            val events =
                pipeline(cloud)
                    .run(
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
    fun selfContainedUsesPrivateBlobScopeAndSkipsSharedDedup() =
        runTest {
            val cloud = FakeCloudBackend()
            val bytes = "shared".encodeToByteArray()
            val hash = FakeWorkingTree.hashOf(bytes)
            // A different project already populated the SHARED pool with this blob.
            cloud.putBlob(hash, FakeWorkingTree(mapOf("x" to FakeWorkingTree.FileBlob(bytes, now))).read("x"), bytes.size.toLong())
            val sharedBefore = cloud.sharedBlobsCount()

            val tree = FakeWorkingTree(mapOf("Project.als" to FakeWorkingTree.FileBlob(bytes, now)))
            val events =
                pipeline(cloud)
                    .run(
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
    fun selfContainedDedupesWithinProjectAcrossRuns() =
        runTest {
            val cloud = FakeCloudBackend()
            val bytes = "v1".encodeToByteArray()
            val tree = FakeWorkingTree(mapOf("Project.als" to FakeWorkingTree.FileBlob(bytes, now)))

            val firstEvents =
                pipeline(cloud)
                    .run(
                        PipelineInput(uuid, tree, null, Generation.ZERO, selfContained = true),
                    ).toList()
            assertNotNull(firstEvents.filterIsInstance<SnapshotProgress.Saved>().firstOrNull())
            val privateAfterFirst = cloud.privateBlobsCount(uuid)
            assertEquals(1, privateAfterFirst)

            // Re-sync the same project: same hash, should NOT re-upload (private-pool dedup).
            val parent = cloud.manifestsFor(uuid).single()
            pipeline(cloud)
                .run(
                    PipelineInput(uuid, tree, parent, Generation("1"), selfContained = true),
                ).toList()
            assertEquals(privateAfterFirst, cloud.privateBlobsCount(uuid))
        }

    @Test
    fun heldLeaseAbortsWithoutUpload() =
        runTest {
            val cloud = FakeCloudBackend()
            val metadataStore = InMemoryMetadataStore(clock = FixedClock(now))
            // Seed an active lease held by another host.
            metadataStore.setDoc(
                DocPath.lock(userId.value, uuid.value),
                LockDoc(
                    holder = "host-b",
                    holderName = "MacStudio",
                    acquiredAt = now,
                    expiresAt = now + kotlin.time.Duration.parse("1h"),
                ),
                LockDoc.serializer(),
            )

            val tree =
                FakeWorkingTree(
                    mapOf("Project.als" to FakeWorkingTree.FileBlob("v1".encodeToByteArray(), now)),
                )
            val events =
                pipeline(cloud, metadataStore)
                    .run(
                        PipelineInput(uuid, tree, null, Generation.ZERO),
                    ).toList()

            assertTrue(events.any { it is SnapshotProgress.LeaseHeld })
            assertTrue(events.last() is SnapshotProgress.Failed)
            assertEquals(0, cloud.blobsCount())
            assertEquals(0, cloud.manifestsFor(uuid).size)
        }
}
