package com.sketchbook.syncio

import com.sketchbook.catalog.CatalogDb
import com.sketchbook.cloud.BlobScope
import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.Generation
import com.sketchbook.cloud.LeaseAcquireResult
import com.sketchbook.cloud.LeaseLock
import com.sketchbook.cloud.LeaseRefreshResult
import com.sketchbook.cloud.ManifestRef
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
import com.sketchbook.repo.BlobCacheSettings
import com.sketchbook.sync.JvmBlobCache
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ManifestMaterializerTest {
    private val tmp = createTempDirectory("manifest-mat-")
    private val cacheRoot = tmp.resolve("cache").also { Files.createDirectories(it) }
    private val projectRoot = tmp.resolve("project").also { Files.createDirectories(it) }
    private val uuid = ProjectUuid("p-uuid")
    private val rev = SnapshotRev(7)

    @AfterTest fun cleanup() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun happyPathWritesAllFiles() =
        runTest {
            val files =
                mapOf(
                    "Project.als" to "als-bytes".toByteArray(),
                    "Samples/k.wav" to "wav-bytes".toByteArray(),
                )
            val manifest = manifestFor(files)
            val cloud =
                FakeMaterializerCloud(
                    manifest,
                    files.entries.associate { (rel, b) ->
                        manifest.files[rel]!!.hash!! to b
                    },
                )
            val handle = CatalogDb.openInMemory()
            val cache = JvmBlobCache(handle.catalog, cacheRoot, cloud, cacheSettings = BlobCacheSettings.Default)
            val mat = ManifestMaterializer(cloud, cache) { projectRoot }

            val r = mat.materialize(uuid, rev)
            assertTrue(r.isSuccess, "materialize failed: ${r.exceptionOrNull()}")

            assertEquals("als-bytes", Files.readString(projectRoot.resolve("Project.als")))
            assertEquals("wav-bytes", Files.readString(projectRoot.resolve("Samples/k.wav")))
        }

    @Test
    fun overwritesExistingFinalsAtomically() =
        runTest {
            val pre = projectRoot.resolve("Project.als")
            Files.writeString(pre, "old-bytes")
            val files = mapOf("Project.als" to "new-bytes".toByteArray())
            val manifest = manifestFor(files)
            val cloud =
                FakeMaterializerCloud(
                    manifest,
                    files.entries.associate { (rel, b) ->
                        manifest.files[rel]!!.hash!! to b
                    },
                )
            val handle = CatalogDb.openInMemory()
            val cache = JvmBlobCache(handle.catalog, cacheRoot, cloud, cacheSettings = BlobCacheSettings.Default)
            val mat = ManifestMaterializer(cloud, cache) { projectRoot }

            val r = mat.materialize(uuid, rev)
            assertTrue(r.isSuccess)
            assertEquals("new-bytes", Files.readString(pre))
        }

    @Test
    fun midFetchFailureLeavesNoTempsAndOriginalsIntact() =
        runTest {
            val pre = projectRoot.resolve("Project.als")
            Files.writeString(pre, "old-bytes")
            val files =
                mapOf(
                    "Project.als" to "new-bytes".toByteArray(),
                    "Samples/k.wav" to "wav-bytes".toByteArray(),
                )
            val manifest = manifestFor(files)
            val failHash = manifest.files["Samples/k.wav"]!!.hash
            val cloud =
                FakeMaterializerCloud(
                    manifest,
                    files.entries.associate { (rel, b) -> manifest.files[rel]!!.hash!! to b },
                    failOnHash = failHash,
                )
            val handle = CatalogDb.openInMemory()
            val cache = JvmBlobCache(handle.catalog, cacheRoot, cloud, cacheSettings = BlobCacheSettings.Default)
            val mat = ManifestMaterializer(cloud, cache) { projectRoot }

            val r = mat.materialize(uuid, rev)
            assertTrue(r.isFailure)
            // Original is untouched (no rename happened).
            assertEquals("old-bytes", Files.readString(pre))
            // No leftover temp files anywhere under projectRoot.
            val temps =
                Files.walk(projectRoot).use { stream ->
                    stream.filter { it.fileName?.toString()?.contains(".materialize-") == true }.toList()
                }
            assertTrue(temps.isEmpty(), "leftover temps: $temps")
        }

    @Test
    fun rejectsManifestPathThatEscapesProjectRoot() =
        runTest {
            val files = mapOf("../escape.als" to "evil".toByteArray())
            val manifest = manifestFor(files)
            val cloud =
                FakeMaterializerCloud(
                    manifest,
                    files.entries.associate { (rel, b) ->
                        manifest.files[rel]!!.hash!! to b
                    },
                )
            val handle = CatalogDb.openInMemory()
            val cache = JvmBlobCache(handle.catalog, cacheRoot, cloud, cacheSettings = BlobCacheSettings.Default)
            val mat = ManifestMaterializer(cloud, cache) { projectRoot }

            val r = mat.materialize(uuid, rev)
            assertTrue(r.isFailure)
            // No file written outside the project root.
            assertFalse(Files.exists(projectRoot.parent.resolve("escape.als")))
        }

    @Test
    fun tombstoneEntryDeletesMaterializedFile() =
        runTest {
            // Pre-existing file under the project root that the manifest now marks deleted.
            val pre = projectRoot.resolve("Removed.als")
            Files.writeString(pre, "old-bytes")

            val live =
                mapOf(
                    "Project.als" to "als-bytes".toByteArray(),
                )
            val liveManifest = manifestFor(live)
            val withTombstone =
                liveManifest.copy(
                    files =
                        liveManifest.files +
                            (
                                "Removed.als" to
                                    ManifestFile(
                                        hash = null,
                                        size = 0,
                                        mtime = Instant.parse("2026-05-05T12:30:00Z"),
                                        deleted = true,
                                    )
                            ),
                )
            val cloud =
                FakeMaterializerCloud(
                    withTombstone,
                    live.entries.associate { (rel, b) ->
                        withTombstone.files[rel]!!.hash!! to b
                    },
                )
            val handle = CatalogDb.openInMemory()
            val cache = JvmBlobCache(handle.catalog, cacheRoot, cloud, cacheSettings = BlobCacheSettings.Default)
            val mat = ManifestMaterializer(cloud, cache) { projectRoot }

            val r = mat.materialize(uuid, rev)
            assertTrue(r.isSuccess, "materialize failed: ${r.exceptionOrNull()}")

            // Live file got laid down.
            assertEquals("als-bytes", Files.readString(projectRoot.resolve("Project.als")))
            // Tombstoned file is gone.
            assertFalse(Files.exists(pre))
        }

    private fun manifestFor(files: Map<String, ByteArray>): Manifest {
        // Synthesize a 64-hex BlobHash from each rel-path so distinct paths get distinct hashes.
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
            treeId = TrackedTreeId(uuid.value),
            kind = TrackedTreeKind.Project,
            rev = rev,
            parentRev = null,
            timestamp = Instant.parse("2026-05-05T12:00:00Z"),
            hostId = "host-a",
            hostName = "DesktopA",
            snapshotKind = SnapshotKind.Auto,
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

/** Minimal CloudBackend covering only the calls ManifestMaterializer + JvmBlobCache make. */
private class FakeMaterializerCloud(
    private val manifest: Manifest,
    private val bytesByHash: Map<BlobHash, ByteArray>,
    private val failOnHash: BlobHash? = null,
) : CloudBackend {
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
        if (hash == failOnHash) throw RuntimeException("simulated fetch failure")
        val bytes = bytesByHash[hash] ?: ByteArray(0)
        val buf = Buffer()
        buf.write(bytes)
        return buf
    }

    override suspend fun readManifest(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        rev: SnapshotRev,
    ): Manifest = manifest

    override suspend fun listManifests(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        sinceRev: SnapshotRev?,
    ) = emptyList<ManifestRef>()

    override suspend fun appendManifestHead(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        expectedHead: Generation?,
        manifest: Manifest,
    ) = Result.failure<Generation>(SketchbookError.Conflict("not used"))

    override suspend fun acquireLock(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        lock: LeaseLock,
    ) = LeaseAcquireResult.Acquired(Generation("1"))

    override suspend fun refreshLock(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        lock: LeaseLock,
        expected: Generation,
    ) = LeaseRefreshResult.Refreshed(Generation("1"))

    override suspend fun releaseLock(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
        expected: Generation,
    ) {}

    override suspend fun readDoc(key: com.sketchbook.core.CloudDocKey) = null

    override suspend fun writeDoc(
        key: com.sketchbook.core.CloudDocKey,
        expected: Generation?,
        bytes: ByteArray,
    ) = Result.failure<Generation>(SketchbookError.Conflict("not used"))

    override suspend fun listDocs(prefix: com.sketchbook.core.CloudDocKey.Prefix) = emptyList<com.sketchbook.cloud.CloudDocRef>()
}
