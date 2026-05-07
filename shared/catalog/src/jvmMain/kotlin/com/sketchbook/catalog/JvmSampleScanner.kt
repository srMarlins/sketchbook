package com.sketchbook.catalog

import com.sketchbook.catalog.db.Catalog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence

/**
 * Walks a `LibraryRoot.UserSamples` root and upserts every audio file into the `samples`
 * corpus table. The corpus backs missing-sample auto-match in [SqlRepairRepository] — by
 * filename+size first (high confidence), then filename-only as a fallback.
 *
 * **No hashing.** v1 keys on filename+size, which is good enough for ~99% of Live Sample/loop
 * libraries (renamed/edited samples are rare). Hashing every file would push first-scan time
 * into the tens of minutes on a multi-GB sample folder; that's a regression we're not paying
 * for until a user actually needs it.
 *
 * **Idempotent.** `upsertSample` uses `INSERT OR REPLACE` keyed on `path UNIQUE`, so re-scanning
 * the same root replaces stale rows in place — no separate cleanup pass needed for files that
 * still exist. Files that have been removed since the last scan persist as stale rows; they're
 * harmless (auto-match is path-routed and a stale `path` would just fail the candidate lookup
 * once the user drills down). A future `deleteSamplesUnder` pass can prune them.
 *
 * Audio extensions match Python v0.1's `_AUDIO_EXTS`: `wav/aif/aiff/mp3/flac/ogg/m4a`. Dotfiles
 * are skipped so macOS `._` resource forks don't pollute the corpus.
 */
class JvmSampleScanner(
    private val catalog: Catalog,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Scan [rootPath], upserting every audio file under it into the `samples` table. Returns
     * the number of files inserted/replaced. [onProgress] is called once per [BATCH_SIZE]-row
     * transaction so callers can render a progress bar; the second arg is `null` until the
     * first batch flushes (we don't pre-walk to size the queue — `Files.walk` is the costly
     * part and walking twice would double the I/O).
     */
    suspend fun scan(
        rootPath: String,
        onProgress: (done: Int, total: Int?) -> Unit = { _, _ -> },
    ): Int =
        withContext(ioDispatcher) {
            val root = Paths.get(rootPath)
            if (!Files.isDirectory(root)) return@withContext 0
            val realRoot = runCatching { root.toRealPath() }.getOrDefault(root.toAbsolutePath())

            val files =
                Files.walk(realRoot).use { stream ->
                    stream
                        .asSequence()
                        .filter { it.isRegularFile() }
                        .filter { !it.name.startsWith(".") }
                        .filter { p ->
                            val ext = p.name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
                            ext in AUDIO_EXTS
                        }.filter { p ->
                            val canonical = runCatching { p.toRealPath() }.getOrNull() ?: return@filter false
                            canonical.startsWith(realRoot)
                        }.toList()
                }
            if (files.isEmpty()) {
                onProgress(0, 0)
                return@withContext 0
            }

            var done = 0
            files.chunked(BATCH_SIZE).forEach { batch ->
                catalog.transaction {
                    for (file in batch) {
                        val abs = file.toAbsolutePath().toString()
                        val parent = file.parent?.toString() ?: ""
                        val size = runCatching { Files.size(file) }.getOrDefault(0L)
                        val mtimeSec =
                            runCatching { Files.getLastModifiedTime(file).toMillis() / 1000.0 }
                                .getOrDefault(0.0)
                        catalog.catalogQueries.upsertSample(
                            path = abs,
                            filename = file.fileName.toString(),
                            size_bytes = size,
                            mtime = mtimeSec,
                            parent_dir = parent,
                        )
                    }
                }
                done += batch.size
                onProgress(done, files.size)
            }
            done
        }

    private companion object {
        const val BATCH_SIZE: Int = 200
    }
}

private val AUDIO_EXTS = setOf("wav", "aif", "aiff", "mp3", "flac", "ogg", "m4a")
