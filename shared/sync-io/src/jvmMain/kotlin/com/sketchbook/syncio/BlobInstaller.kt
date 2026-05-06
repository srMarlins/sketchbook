package com.sketchbook.syncio

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Places a content-addressed blob at a target path on disk. Hardlinks within a volume (the
 * common case — blob cache shares a drive with the project tree), falls back to copy across
 * volumes.
 *
 * **Idempotent.** If [target] already points to the same content as [blob], do nothing.
 *
 * Renamed from `Materializer` in PR-F: `Materializer` now refers to the higher-level
 * manifest-orchestration class in [ManifestMaterializer]; this is the per-blob primitive.
 */
object BlobInstaller {

    /** Outcome of a single [install] call. */
    enum class Outcome { Hardlinked, Copied, AlreadyPresent }

    fun install(blob: Path, target: Path): Outcome {
        require(Files.isRegularFile(blob)) { "blob $blob is not a regular file" }

        if (Files.exists(target)) {
            // Same inode → already linked from this blob; nothing to do.
            if (sameInode(blob, target)) return Outcome.AlreadyPresent
            // Different content at target — caller's job to decide; refuse to clobber.
            throw FileAlreadyExistsException(target.toString())
        }

        Files.createDirectories(target.parent)

        return try {
            Files.createLink(target, blob)
            Outcome.Hardlinked
        } catch (cross: java.nio.file.FileSystemException) {
            // Different volume, ACL refusal, or filesystem without hardlink support — fall back.
            Files.copy(blob, target, StandardCopyOption.COPY_ATTRIBUTES)
            Outcome.Copied
        }
    }

    /** True iff both paths point at the same physical file (same inode on POSIX, same fileKey on Windows). */
    fun sameInode(a: Path, b: Path): Boolean {
        return try {
            Files.isSameFile(a, b)
        } catch (_: java.io.IOException) {
            false
        }
    }
}
