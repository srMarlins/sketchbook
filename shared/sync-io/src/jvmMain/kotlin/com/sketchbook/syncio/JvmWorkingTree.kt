package com.sketchbook.syncio

import com.sketchbook.core.BlobHash
import com.sketchbook.sync.FileStat
import com.sketchbook.sync.WorkingTree
import kotlinx.io.RawSource
import kotlinx.io.asSource
import java.nio.channels.FileChannel
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence
import kotlin.time.Instant

/**
 * Filesystem-backed [WorkingTree]. Walks [root] for snapshottable files (skips Live's auto
 * folders + dotfiles), reports each as a project-relative path, hashes via [Hasher].
 *
 * **What's snapshottable.** Per design §4.2 the working tree is the set of files the snapshot
 * pipeline uploads. We mirror the catalog scanner's exclusion list:
 *  - Skip `Backup/`, `Samples/` (Live auto-recorded), and `Ableton Project Info/` subtrees.
 *  - Skip dotfiles and any `.als.bak` autosave.
 *
 * Sample files the user *does* want synced (their User Library, drum hits) live elsewhere — a
 * project-local `Samples` subdirectory is by Live's convention regenerable, so we don't sync it.
 */
class JvmWorkingTree(private val root: Path) : WorkingTree {

    override fun list(): List<String> =
        Files.walk(root, FileVisitOption.FOLLOW_LINKS).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() }
                .filter { p -> p.none { c -> c.fileName?.toString() in SKIP_DIRS } }
                .filter { !it.fileName.toString().startsWith(".") }
                .filter { !it.fileName.toString().endsWith(".als.bak", ignoreCase = true) }
                .map { root.relativize(it).toString().replace('\\', '/') }
                .toList()
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

    private companion object {
        val SKIP_DIRS: Set<String> = setOf("Backup", "Samples", "Ableton Project Info")
    }
}

/**
 * True when [path] exists and another process holds it in a sharing mode that blocks our
 * exclusive WRITE+lock acquisition. False when the file doesn't exist, when we can acquire
 * the lock immediately, or when probing fails for benign reasons (we fail open — better to
 * try-and-fail-loudly than refuse-to-try).
 *
 * On Windows, Live's open .als typically refuses our `FileChannel.open(WRITE)` with
 * `AccessDeniedException`. On POSIX, `tryLock()` returning null is the canonical signal.
 */
internal fun isInUse(path: Path): Boolean {
    if (!Files.exists(path)) return false
    val channel = try {
        FileChannel.open(path, StandardOpenOption.WRITE)
    } catch (_: java.nio.file.AccessDeniedException) {
        // Windows sharing mode prevents WRITE access — Live has it.
        return true
    } catch (_: Throwable) {
        // FileSystemException etc. opening — fail open so a flaky probe can't gate real work.
        return false
    }
    return channel.use { ch ->
        // Once we hold a writable channel: tryLock returning null OR throwing means somebody
        // else holds an overlapping lock (in-process or out-of-process). Treat both as busy —
        // we can't safely overwrite a locked file regardless of where the lock came from.
        try {
            val lock = ch.tryLock()
            if (lock == null) {
                true
            } else {
                lock.release()
                false
            }
        } catch (_: Throwable) {
            true
        }
    }
}

/** Iterate path components; matches the scanner's predicate so the working tree and the
 *  catalog row see the exact same set of files. */
private fun Path.none(predicate: (Path) -> Boolean): Boolean {
    var p: Path? = this
    while (p != null) {
        if (predicate(p)) return false
        p = p.parent
    }
    return true
}
