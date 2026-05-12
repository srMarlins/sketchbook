package com.sketchbook.liveit

import com.sketchbook.cloud.BlobScope
import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.metadata.DocPath
import com.sketchbook.cloud.metadata.MetadataStore
import com.sketchbook.cloud.metadata.TreeDoc
import com.sketchbook.core.Manifest
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.UserId
import kotlinx.io.buffered
import kotlinx.io.readAtMostTo
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private const val STREAM_BUFFER_BYTES = 64 * 1024

/**
 * Cloud → disk materialization helper shared by [LiveTestPullKt] and the two-client scenarios.
 *
 * Reads the tree doc, fetches the head manifest, streams each blob to [destDir]. We
 * deliberately do NOT go through `PullPoller` / `SnapshotRepository.materializeAt` here —
 * those carry SQLDelight + sync-state baggage that the prod app needs but the live tests
 * don't, and the failure modes are easier to attribute when this path is straight-line.
 *
 * **Overwrite mode** controls collision behaviour:
 *  - [OverwriteMode.RejectExisting] (default) — fail fast if any file already exists. Used
 *    by `liveTestPull` where dest must be empty so byte-equality assertions are meaningful.
 *  - [OverwriteMode.ReplaceExisting] — replace files in-place. Used by the two-client
 *    materialize-after-edit scenario where the receiver's workDir already holds the previous
 *    rev's bytes and we want to overwrite them with the new rev.
 */
object LiveCloudIo {
    enum class OverwriteMode {
        RejectExisting,
        ReplaceExisting,
    }

    suspend fun readTreeDoc(
        metadataStore: MetadataStore,
        userId: UserId,
        uuid: ProjectUuid,
    ): TreeDoc? = metadataStore.getDoc(DocPath.tree(userId.value, uuid.value), TreeDoc.serializer())

    suspend fun readHeadManifest(
        metadataStore: MetadataStore,
        cloud: CloudBackend,
        userId: UserId,
        uuid: ProjectUuid,
    ): Manifest {
        val tree =
            readTreeDoc(metadataStore, userId, uuid)
                ?: error("no tree doc at users/${userId.value}/trees/${uuid.value}")
        check(tree.head_rev > 0L) { "tree.head_rev=${tree.head_rev}; nothing to materialize." }
        return cloud.readManifest(uuid, SnapshotRev(tree.head_rev))
    }

    suspend fun materialize(
        cloud: CloudBackend,
        manifest: Manifest,
        destDir: Path,
        uuid: ProjectUuid,
        overwriteMode: OverwriteMode = OverwriteMode.RejectExisting,
        onProgress: ((done: Int, total: Int, rel: String) -> Unit)? = null,
    ) {
        Files.createDirectories(destDir)
        val scope: BlobScope = if (manifest.selfContained) BlobScope.Private(uuid) else BlobScope.Shared
        val total = manifest.files.size
        var done = 0
        for ((rel, mf) in manifest.files) {
            val out = destDir.resolve(rel)
            Files.createDirectories(out.parent)
            writeBlobToFile(cloud, mf.hash, scope, out, mf.size, overwriteMode, rel)
            done++
            onProgress?.invoke(done, total, rel)
        }
    }

    private suspend fun writeBlobToFile(
        cloud: CloudBackend,
        hash: com.sketchbook.core.BlobHash,
        scope: BlobScope,
        out: Path,
        expectedSize: Long,
        overwriteMode: OverwriteMode,
        rel: String,
    ) {
        val options =
            when (overwriteMode) {
                OverwriteMode.RejectExisting -> {
                    arrayOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                }

                OverwriteMode.ReplaceExisting -> {
                    arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                }
            }
        val src = cloud.getBlob(hash, scope)
        var written = 0L
        try {
            Files.newOutputStream(out, *options).use { os ->
                val buf = ByteArray(STREAM_BUFFER_BYTES)
                val buffered = src.buffered()
                while (true) {
                    val read = buffered.readAtMostTo(buf, 0, buf.size)
                    if (read <= 0) break
                    os.write(buf, 0, read)
                    written += read
                }
            }
        } finally {
            runCatching { src.close() }
        }
        check(written == expectedSize) {
            "size mismatch for $rel: manifest=$expectedSize actual=$written"
        }
    }
}
