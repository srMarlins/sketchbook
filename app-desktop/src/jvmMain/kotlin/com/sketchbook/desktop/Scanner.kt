package com.sketchbook.desktop

import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectPath
import com.sketchbook.core.ProjectRow
import com.sketchbook.desktop.repo.InMemoryProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock

/**
 * Stop-gap library scanner. Walks a folder tree, finds every `.als` file, and inserts a
 * skeletal [ProjectRow] into the in-memory project repository. Real `.als` parsing arrives
 * later — for now the row carries the file name (sans extension) and the absolute path; the
 * Project Detail screen will load the full metadata when opened.
 *
 * The scanner runs off the IO dispatcher and emits progress via [progress] so the UI can
 * surface a "Scanning…" status. Cancellation isn't wired through; replace this with the real
 * SQLDelight-backed indexer when [SqlProjectRepository] lands.
 */
class LibraryScanner(
    private val repository: InMemoryProjectRepository,
    private val scope: CoroutineScope,
) {

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()
    private val nextId = AtomicLong(1L)

    fun scan(rootPath: String) {
        val root = Paths.get(rootPath)
        if (!Files.isDirectory(root)) {
            _progress.value = Progress.Failed("Not a directory: $rootPath")
            return
        }
        scope.launch {
            _progress.value = Progress.Scanning(0, 0)
            val rows = withContext(Dispatchers.IO) { walk(root) }
            repository.addRows(rows)
            _progress.value = Progress.Done(rows.size)
            // Hold "Done" on screen briefly so the user actually sees it before it disappears.
            kotlinx.coroutines.delay(3500)
            if (_progress.value is Progress.Done) {
                _progress.value = Progress.Idle
            }
        }
    }

    private fun walk(root: Path): List<ProjectRow> {
        val now = Clock.System.now()
        val out = mutableListOf<ProjectRow>()
        var visited = 0
        var found = 0
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                // Skip Live's autosave folder and similar machine-generated trees outright —
                // they don't represent user-named variants and pollute every shelf.
                val name = dir.fileName?.toString().orEmpty()
                if (name.equals("Backup", ignoreCase = true)) return FileVisitResult.SKIP_SUBTREE
                if (name == "Ableton Project Info") return FileVisitResult.SKIP_SUBTREE
                if (name == "Samples") return FileVisitResult.SKIP_SUBTREE
                if (name.startsWith(".")) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                visited++
                if (visited % 50 == 0) {
                    _progress.value = Progress.Scanning(visited, found)
                }
                val name = file.fileName?.toString() ?: return FileVisitResult.CONTINUE
                // .als.bak is Live's autosave; the user never opens it directly. Drop it.
                if (name.endsWith(".als.bak", ignoreCase = true)) return FileVisitResult.CONTINUE
                if (!name.endsWith(".als", ignoreCase = true)) return FileVisitResult.CONTINUE
                if (name.startsWith(".")) return FileVisitResult.CONTINUE

                found++
                val display = name.removeSuffix(".als").removeSuffix(".ALS")
                val abs = file.toAbsolutePath().toString().replace('\\', '/')
                val mtime = runCatching { attrs.lastModifiedTime().toMillis() }
                    .getOrDefault(now.toEpochMilliseconds())
                val sizeBytes = runCatching { attrs.size() }.getOrDefault(0L)
                // EffortScore takes a partial signal (file size) when ProjectMetadata is null —
                // matches the file_size_kb log term in compute_effort. Real score replaces this
                // when the streaming parser lands.
                val effort = com.sketchbook.featureprojects.EffortScore.scoreOf(meta = null, fileSizeBytes = sizeBytes)
                out += ProjectRow(
                    id = ProjectId(nextId.getAndIncrement()),
                    name = display,
                    path = ProjectPath(abs),
                    tempo = null,
                    trackCount = 0,
                    lastSavedLiveVersion = null,
                    updatedAt = kotlin.time.Instant.fromEpochMilliseconds(mtime),
                    tags = emptyList(),
                    colorTag = null,
                    effortScore = effort,
                    fileSizeBytes = sizeBytes,
                )
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult =
                FileVisitResult.CONTINUE
        })
        return out
    }

    sealed interface Progress {
        data object Idle : Progress
        data class Scanning(val filesVisited: Int, val projectsFound: Int) : Progress
        data class Done(val rowsAdded: Int) : Progress
        data class Failed(val message: String) : Progress
    }
}
