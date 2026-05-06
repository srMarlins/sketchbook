package com.sketchbook.integration

import com.sketchbook.cloud.Generation
import com.sketchbook.core.ProjectUuid
import com.sketchbook.integration.fakes.FakeCloudBackend
import com.sketchbook.integration.fakes.FixedClock
import com.sketchbook.sync.PipelineInput
import com.sketchbook.sync.SnapshotPipeline
import com.sketchbook.sync.SnapshotProgress
import com.sketchbook.syncio.JvmWorkingTree
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class SyncRoundTripTest {

    private val tmp: Path = createTempDirectory("sync-")

    @AfterTest fun cleanup() {
        tmp.toFile().deleteRecursively()
    }

    private val now = Instant.parse("2026-05-06T10:00:00Z")
    private val uuid = ProjectUuid("01H-integration-test")

    @Test
    fun aSnapshotsAndBPullsByteForByte() = runTest {
        // Host A's working tree: a synthesized clean project at tmp/hostA/clean Project/.
        Fixtures.writeCleanProject(tmp.resolve("hostA"))
        val hostA = tmp.resolve("hostA")
        val hostBRoot = tmp.resolve("hostB").also { it.toFile().mkdirs() }

        val cloud = FakeCloudBackend()
        val pipelineA = SnapshotPipeline(
            cloud = cloud,
            hostId = "host-a",
            hostName = "DesktopA",
            clock = FixedClock(now),
        )

        // First push.
        val firstEvents = pipelineA.run(
            PipelineInput(
                uuid = uuid,
                tree = JvmWorkingTree(hostA),
                lastKnownManifest = null,
                expectedHeadGeneration = Generation.ZERO,
            ),
        ).toList()
        val saved1 = firstEvents.filterIsInstance<SnapshotProgress.Saved>().single()
        assertEquals(1L, saved1.rev.value)
        val blobsAfterFirst = cloud.blobsCount()
        assertTrue(blobsAfterFirst >= 1, "expected blobs uploaded, got $blobsAfterFirst")

        // Second push from host A with no working-tree changes — no new blobs.
        val parent = cloud.manifestsFor(uuid).single()
        val pipelineA2 = SnapshotPipeline(
            cloud = cloud,
            hostId = "host-a",
            hostName = "DesktopA",
            clock = FixedClock(now),
        )
        pipelineA2.run(
            PipelineInput(
                uuid = uuid,
                tree = JvmWorkingTree(hostA),
                lastKnownManifest = parent,
                expectedHeadGeneration = cloud.headGenerationFor(uuid),
            ),
        ).toList()
        assertEquals(blobsAfterFirst, cloud.blobsCount(), "second push should not upload new blobs")

        // Host B materializes the head manifest by reading from cloud directly. We don't run
        // PullPoller (it's a long-running flow); the materialization step is the byte-for-byte
        // check.
        val head = cloud.manifestsFor(uuid).maxByOrNull { it.rev.value }!!
        for ((rel, mfile) in head.files) {
            val targetPath = hostBRoot.resolve(rel)
            Files.createDirectories(targetPath.parent)
            val bytes = cloud.blobBytes(mfile.hash, head.selfContained, uuid)
            Files.write(targetPath, bytes)
        }

        // Compare every file under host A's snapshottable tree to host B's copy.
        val tree = JvmWorkingTree(hostA)
        for (rel in tree.list()) {
            val a = sha256(hostA.resolve(rel))
            val b = sha256(hostBRoot.resolve(rel))
            assertEquals(a, b, "byte mismatch on $rel")
        }
    }

    private fun sha256(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
