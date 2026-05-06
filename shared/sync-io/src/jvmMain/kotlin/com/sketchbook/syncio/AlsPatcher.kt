package com.sketchbook.syncio

import com.sketchbook.als.AlsRewriter
import com.sketchbook.repo.AlsPatchService
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Disk-side wrapper around [AlsRewriter]. Reads a `.als` file, runs the pure StAX rewrite,
 * atomically replaces the original on success, and skips work when the file is held open by
 * another process (typically Ableton Live).
 *
 * V1 caveat: temp file is named `${fileName}.patcher-tmp`. If a previous run crashed mid-write
 * and left that file behind, [patch] returns [Outcome.Failed] (CREATE_NEW throws
 * `FileAlreadyExistsException`). Cleanup is left to the caller / a future janitor pass.
 */
class AlsPatcher(
    private val busyDetector: (Path) -> Boolean = ::isFileLockedByAnotherProcess,
) : AlsPatchService {
    sealed interface Outcome {
        object Patched : Outcome
        object NoChange : Outcome
        object SkippedBusy : Outcome
        data class Failed(val cause: Throwable) : Outcome
    }

    fun patch(als: Path, mapping: Map<String, String>): Outcome {
        if (mapping.isEmpty()) return Outcome.NoChange
        if (busyDetector(als)) return Outcome.SkippedBusy
        return runCatching {
            val original = Files.readAllBytes(als)
            val rewritten = AlsRewriter.rewriteSamplePaths(original, mapping)
            if (rewritten.contentEquals(original)) return Outcome.NoChange
            val temp = als.resolveSibling("${als.fileName}.patcher-tmp")
            Files.write(temp, rewritten, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            Files.move(temp, als, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Outcome.Patched as Outcome
        }.getOrElse { Outcome.Failed(it) }
    }

    /**
     * [AlsPatchService] adapter — bridges the stringly-typed repository surface to the typed
     * `Path`-based core. Repository code in `commonMain` can't reference `java.nio.file.Path`,
     * so the path round-trips through `String`.
     */
    override suspend fun patch(alsPath: String, mapping: Map<String, String>): AlsPatchService.Outcome =
        when (val o = patch(Paths.get(alsPath), mapping)) {
            Outcome.Patched -> AlsPatchService.Outcome.Patched
            Outcome.NoChange -> AlsPatchService.Outcome.NoChange
            Outcome.SkippedBusy -> AlsPatchService.Outcome.SkippedBusy
            is Outcome.Failed -> AlsPatchService.Outcome.Failed
        }
}

/**
 * Best-effort "is this file held open by another process?" probe.
 *
 * On Windows, opening a file Ableton has open often fails with [java.nio.file.AccessDeniedException]
 * before [FileChannel.tryLock] is ever consulted — treat that as busy. Any other exception (broken
 * symlink, permissions on a stale handle, FS quirks) is conservatively treated as *not* busy so we
 * don't block legitimate operations.
 */
private fun isFileLockedByAnotherProcess(p: Path): Boolean = try {
    FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE).use { ch ->
        ch.tryLock()?.use { } ?: return true
        false
    }
} catch (e: java.nio.file.AccessDeniedException) {
    true
} catch (_: Throwable) {
    false
}
