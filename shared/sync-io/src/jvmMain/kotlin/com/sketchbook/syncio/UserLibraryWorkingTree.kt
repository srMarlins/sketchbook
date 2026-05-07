package com.sketchbook.syncio

import com.sketchbook.core.BlobHash
import com.sketchbook.sync.FileStat
import com.sketchbook.sync.WorkingTree
import kotlinx.io.RawSource
import kotlinx.io.asSource
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence
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
    override fun list(): List<String> =
        if (!Files.exists(root)) {
            emptyList()
        } else {
            Files.walk(root, FileVisitOption.FOLLOW_LINKS).use { stream ->
                stream
                    .asSequence()
                    .filter { it.isRegularFile() }
                    .mapNotNull { p ->
                        val rel = root.relativize(p).toString().replace('\\', '/')
                        val components = rel.split('/')
                        if (UserLibrarySkipSet.isSkipped(components)) null else rel
                    }.toList()
            }
        }

    override fun stat(relativePath: String): FileStat {
        val abs = root.resolve(relativePath)
        val attrs = Files.readAttributes(abs, java.nio.file.attribute.BasicFileAttributes::class.java)
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
