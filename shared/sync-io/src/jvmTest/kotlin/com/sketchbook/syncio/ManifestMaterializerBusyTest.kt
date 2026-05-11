package com.sketchbook.syncio

import com.sketchbook.catalog.CatalogDb
import com.sketchbook.cloud.BlobScope
import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.Generation
import com.sketchbook.cloud.ManifestRef
import com.sketchbook.core.BlobHash
import com.sketchbook.core.Manifest
import com.sketchbook.core.ManifestFile
import com.sketchbook.core.ManifestStats
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SketchbookError
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.repo.BlobCacheSettings
import com.sketchbook.sync.JvmBlobCache
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ManifestMaterializerBusyTest {
    private val tmp = createTempDirectory("manifest-busy-")
    private val cacheRoot = tmp.resolve("cache").also { Files.createDirectories(it) }
    private val projectRoot = tmp.resolve("project").also { Files.createDirectories(it) }
    private val uuid = ProjectUuid("p-busy")
    private val rev = SnapshotRev(11)

    @AfterTest fun cleanup() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun failsFastWhenDestinationFileIsHeldByAnotherChannel() =
        runTest {
            // Pre-populate the destination .als so isInUse can probe it. Bytes preserved so we
            // can assert non-mutation on busy failure.
            val alsPath = projectRoot.resolve("Project.als")
            val originalBytes = "original-bytes".toByteArray()
            Files.write(alsPath, originalBytes)

            val files = mapOf("Project.als" to "new-bytes".toByteArray())
            val manifest = manifestFor(files)
            val cloud =
                CountingCloud(
                    manifest,
                    files.entries.associate { (rel, b) ->
                        manifest.files[rel]!!.hash to b
                    },
                )
            val handle = CatalogDb.openInMemory()
            val cache = JvmBlobCache(handle.catalog, cacheRoot, cloud, cacheSettings = BlobCacheSettings.Default)
            val mat = ManifestMaterializer(cloud, cache) { projectRoot }

            // Hold an exclusive lock on the .als for the duration of materialize.
            val ch = FileChannel.open(alsPath, StandardOpenOption.READ, StandardOpenOption.WRITE)
            val lock = ch.lock()
            try {
                val r = mat.materialize(uuid, rev)
                assertTrue(r.isFailure, "expected busy failure")
                val cause = r.exceptionOrNull()
                assertNotNull(cause)
                assertTrue(cause is WorkingTreeBusyException, "expected WorkingTreeBusyException, got ${cause::class}")
                assertTrue(cause.busyPaths.contains("Project.als"), "expected Project.als in busyPaths, got ${cause.busyPaths}")

                // No blob fetch on busy preflight (saves bandwidth on the no-op pull retry tick).
                assertEquals(0, cloud.blobFetchCount, "preflight must run before any blob fetch")

                // No leftover temps anywhere under projectRoot.
                val temps =
                    Files.walk(projectRoot).use { stream ->
                        stream.filter { it.fileName?.toString()?.contains(".materialize-") == true }.toList()
                    }
                assertTrue(temps.isEmpty(), "leftover temps: $temps")

                // Length is observable while the exclusive lock is held; full read isn't (Windows
                // blocks read I/O against a locked region). Assert length here, full content below.
                assertEquals(originalBytes.size.toLong(), Files.size(alsPath))
            } finally {
                lock.release()
                ch.close()
            }

            // After lock release: original content was untouched by the busy attempt above.
            assertEquals(originalBytes.toList(), Files.readAllBytes(alsPath).toList())

            // And the same materialize call now succeeds.
            val ok = mat.materialize(uuid, rev)
            assertTrue(ok.isSuccess, "expected success after lock release: ${ok.exceptionOrNull()}")
            assertEquals("new-bytes", Files.readString(alsPath))
        }

    private fun manifestFor(files: Map<String, ByteArray>): Manifest {
        val mfiles =
            files.entries.associate { (rel, bytes) ->
                val hex = rel.encodeToByteArray().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val padded = (hex + "0".repeat(64)).substring(0, 64)
                rel to
                    ManifestFile(
                        hash = BlobHash("b3:$padded"),
                        size = bytes.size.toLong(),
                        mtime = Instant.parse("2026-05-05T12:00:00Z"),
                    )
            }
        return Manifest(
            projectUuid = uuid,
            rev = rev,
            parentRev = null,
            timestamp = Instant.parse("2026-05-05T12:00:00Z"),
            hostId = "host-a",
            hostName = "DesktopA",
            kind = SnapshotKind.Auto,
            files = mfiles,
            stats =
                ManifestStats(
                    fileCount = mfiles.size,
                    totalBytes = mfiles.values.sumOf { it.size },
                    newBytes = 0,
                ),
        )
    }
}

/** Counts blob fetches so the busy test can assert the preflight short-circuits before fetch. */
private class CountingCloud(
    private val manifest: Manifest,
    private val bytesByHash: Map<BlobHash, ByteArray>,
) : CloudBackend {
    var blobFetchCount: Int = 0
        private set

    override suspend fun headBlob(
        hash: BlobHash,
        scope: BlobScope,
    ): Boolean = true

    override suspend fun putBlob(
        hash: BlobHash,
        source: RawSource,
        size: Long,
        scope: BlobScope,
    ) = error("not used")

    override suspend fun getBlob(
        hash: BlobHash,
        scope: BlobScope,
    ): RawSource {
        blobFetchCount++
        val bytes = bytesByHash[hash] ?: ByteArray(0)
        val buf = Buffer()
        buf.write(bytes)
        return buf
    }

    override suspend fun readManifest(ref: ManifestRef): Manifest = manifest

    override suspend fun readManifest(
        uuid: ProjectUuid,
        rev: SnapshotRev,
    ): Manifest = manifest

    override suspend fun listManifests(
        uuid: ProjectUuid,
        sinceRev: SnapshotRev?,
    ) = emptyList<ManifestRef>()

    override suspend fun appendManifestHead(
        uuid: ProjectUuid,
        expectedHead: Generation?,
        manifest: Manifest,
    ) = Result.failure<Generation>(SketchbookError.Conflict("not used"))
}
