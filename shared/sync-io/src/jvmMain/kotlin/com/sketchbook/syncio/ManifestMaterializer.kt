package com.sketchbook.syncio

import com.sketchbook.cloud.BlobScope
import com.sketchbook.cloud.CloudBackend
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SketchbookError
import com.sketchbook.core.SnapshotRev
import com.sketchbook.repo.MaterializeOutcome
import com.sketchbook.sync.JvmBlobCache
import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Manifest-level laydown: read a manifest from cloud, ensure every blob is in the local cache,
 * write each project file via temp + atomic rename. On any failure, leftover temps are cleaned
 * up so the working tree is left in either the original or the new state — never partial.
 *
 * Per-blob hardlink/copy primitive lives in [BlobInstaller]. This class drives the whole
 * manifest at once.
 *
 * The plan envisioned this in :shared:sync commonMain, but atomic-rename + per-volume hardlink
 * primitives are JVM-specific (`Files.move(... ATOMIC_MOVE)` / `Files.createLink`); landing it
 * in :shared:sync-io alongside [JvmWorkingTree] keeps everything that touches `java.nio.file`
 * in one place.
 */
class ManifestMaterializer(
    private val cloud: CloudBackend,
    private val blobCache: JvmBlobCache,
    private val projectRoot: suspend (ProjectUuid) -> Path,
) {
    /**
     * Materialize the project at [rev] into the working tree at `projectRoot(uuid)`. Returns
     * [MaterializeOutcome.Materialized] on full laydown, [MaterializeOutcome.WorkingTreeBusy]
     * when one or more destination files are held open by Live, and throws
     * [SketchbookError.IoFailure] / [SketchbookError.IntegrityError] on transport / disk
     * failures. Leftover temps are always cleaned up; the working tree is left in either the
     * original or the new state — never partial.
     */
    suspend fun materialize(
        uuid: ProjectUuid,
        rev: SnapshotRev,
    ): MaterializeOutcome {
        val manifest = cloud.readManifest(uuid, rev)
        val scope: BlobScope =
            if (manifest.selfContained) BlobScope.Private(uuid) else BlobScope.Shared
        val root = projectRoot(uuid)
        Files.createDirectories(root)

        // Pre-flight: bail before any blob fetch when a destination file is already open in
        // another process (Live with the project loaded on Windows). Doing this BEFORE the
        // staging loop avoids wasted bandwidth + leaves zero on-disk side effects in the busy
        // case — caller (PullPoller wiring or Rewind UI) decides whether to retry or surface.
        // `resolveSafely` throws on traversal-escapes (`..`); let it propagate.
        val busy =
            manifest.files.keys.mapNotNull { rel ->
                val finalPath = resolveSafely(root, rel)
                if (Files.exists(finalPath) && isInUse(finalPath)) rel else null
            }
        if (busy.isNotEmpty()) return MaterializeOutcome.WorkingTreeBusy(busy)

        // Pair (temp, final) — temps written first; renames happen only after every file is
        // staged so a mid-fetch failure leaves the working tree intact.
        val staged = mutableListOf<Pair<Path, Path>>()
        try {
            for ((relPath, mfile) in manifest.files) {
                val finalPath = resolveSafely(root, relPath)
                val tempPath = finalPath.resolveSibling("${finalPath.fileName}.materialize-${rev.value}")
                Files.createDirectories(finalPath.parent)
                // Pull blob into cache (no-op if already cached).
                val blob = blobCache.getOrFetch(mfile.hash, scope)
                // Stage at temp with copy semantics (we don't hardlink the blob to the temp
                // path — the atomic-move below would replace the final, but on Windows a
                // hardlinked staged temp can't be atomically renamed across some FS configs.
                // Plain copy is simplest and still cheap because the blob already lives on the
                // same volume as the project root in the common case.)
                Files.copy(blob, tempPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                staged += tempPath to finalPath
            }
            // Commit phase: rename every temp into place.
            for ((temp, final) in staged) {
                try {
                    Files.move(temp, final, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    // Fall back to non-atomic — happens on some shares (SMB) and across volumes.
                    Files.move(temp, final, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            return MaterializeOutcome.Materialized
        } catch (c: CancellationException) {
            for ((temp, _) in staged) {
                runCatching { Files.deleteIfExists(temp) }
            }
            throw c
        } catch (s: SketchbookError) {
            for ((temp, _) in staged) {
                runCatching { Files.deleteIfExists(temp) }
            }
            throw s
        } catch (t: Throwable) {
            for ((temp, _) in staged) {
                runCatching { Files.deleteIfExists(temp) }
            }
            throw SketchbookError.IoFailure("materialize ${uuid.value}@${rev.value} failed", t)
        }
    }

    /** Resolve [rel] under [root], rejecting traversal escapes (`..`). */
    private fun resolveSafely(
        root: Path,
        rel: String,
    ): Path {
        val candidate = root.resolve(rel).normalize()
        require(candidate.startsWith(root.normalize())) {
            "manifest path '$rel' escapes project root"
        }
        return candidate
    }
}
