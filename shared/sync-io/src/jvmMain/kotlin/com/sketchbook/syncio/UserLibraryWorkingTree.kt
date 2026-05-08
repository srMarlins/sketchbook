package com.sketchbook.syncio

import com.sketchbook.core.BlobHash
import com.sketchbook.sync.FileStat
import com.sketchbook.sync.WorkingTree
import kotlinx.io.RawSource
import kotlinx.io.asSource
import java.io.IOException
import java.nio.file.FileSystemLoopException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet
import kotlin.time.Instant

/**
 * Filesystem-backed [WorkingTree] for the Ableton User Library. Walks [root] for the user-
 * content surface (.adg, .adv, .alc, .als, .amxd, .adp, .agr, audio samples) and skips
 * Live's auto-generated metadata + OS junk per [UserLibrarySkipSet].
 *
 * Distinct from [JvmWorkingTree] (project trees) because:
 * - Different skip-set: project trees skip `Backup/`, `Samples/`, `Ableton Project Info/`;
 *   the UL only skips `Ableton Project Info/` plus OS junk.
 * - Different policy downstream: UL trees use `ConflictMode.Merge` (LWW with tombstones)
 *   while project trees use `BranchFork`.
 *
 * Both implementations share [Hasher] for the BLAKE3 digest so blob dedup works across
 * project + UL trees on the same machine.
 */
class UserLibraryWorkingTree(
    private val root: Path,
) : WorkingTree {
    override fun list(): List<String> {
        if (!Files.exists(root)) return emptyList()
        // Some users intentionally symlink racks into User Library/, so we keep FOLLOW_LINKS — but
        // walkFileTree's visitor lets us treat a FileSystemLoopException as "skip this branch"
        // instead of letting it abort the entire walk like Files.walk's stream would.
        val collected = mutableListOf<String>()
        Files.walkFileTree(
            root,
            EnumSet.of(FileVisitOption.FOLLOW_LINKS),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (attrs.isRegularFile) {
                        val rel = root.relativize(file).toString().replace('\\', '/')
                        val components = rel.split('/')
                        if (!UserLibrarySkipSet.isSkipped(components)) collected += rel
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult =
                    if (exc is FileSystemLoopException) {
                        // Cycle detected via FOLLOW_LINKS — skip this branch and keep going.
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        throw exc
                    }
            },
        )
        return collected
    }

    override fun stat(relativePath: String): FileStat {
        val abs = root.resolve(relativePath)
        val attrs = Files.readAttributes(abs, BasicFileAttributes::class.java)
        return FileStat(
            size = attrs.size(),
            mtime = Instant.fromEpochMilliseconds(attrs.lastModifiedTime().toMillis()),
        )
    }

    override fun read(relativePath: String): RawSource {
        val abs = root.resolve(relativePath)
        return Files.newInputStream(abs).asSource()
    }

    override fun hash(relativePath: String): BlobHash {
        val abs = root.resolve(relativePath)
        return Hasher.hash(abs)
    }
}
