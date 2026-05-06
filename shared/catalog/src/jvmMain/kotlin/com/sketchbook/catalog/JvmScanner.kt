package com.sketchbook.catalog

import com.sketchbook.als.AlsParser
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.EffortScore
import com.sketchbook.core.PluginFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence
import kotlin.system.measureTimeMillis

/**
 * Walks a library root, parses every `.als`, upserts catalog rows. Progress as a cold Flow.
 *
 * **Sequential by design** for v1: SQLite WAL handles concurrent readers fine but writers must
 * serialize. Parallelism would be valuable for the parse step; deferred until measured to be a
 * bottleneck (the parser is fast; I/O is the time sink, and JDK's `Files.walk` already issues
 * directory reads in order).
 *
 * **Per-file pipeline.** For each `.als`:
 *   1. `AlsParser.parse` → `ProjectMetadata`. Failures land in a `parse_status='failed'` row
 *      with the exception message in `parse_error` (so the Broken shelf surfaces them).
 *   2. `SampleResolver.resolve` against the `.als`'s parent directory → which sample refs are
 *      missing on disk. The per-sample `is_missing` flag persists on `project_samples` rows so
 *      the detail panel can list them; the count surfaces on `ProjectRow.missingSampleCount`.
 *   3. `EffortScore.compute(meta, fileSize)` → 0..100 score plus breakdown JSON. Persists to
 *      `projects.effort_score` / `effort_breakdown`.
 */
class JvmScanner(
    private val catalog: Catalog,
    private val fts: CatalogFts,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    fun scan(root: Path): Flow<ScanProgress> = flow {
        val started = System.currentTimeMillis()
        // No FOLLOW_LINKS: a symlink in the user's library to `/` (or a network share, or another
        // user's home) would otherwise be silently traversed and persist absolute paths into the
        // catalog where the MCP server surfaces them to an LLM. Sandbox to the canonical root so
        // even a non-link "junction" can't escape.
        val realRoot = runCatching { root.toRealPath() }.getOrDefault(root.toAbsolutePath())
        // Filter: skip Backup/Samples/Ableton Project Info subtrees (Live's auto-generated
        // sibling folders); skip dotfiles; skip `.als.bak` autosaves; skip anything that
        // canonicalizes outside the root.
        val alsFiles = Files.walk(realRoot).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() && it.name.endsWith(".als", ignoreCase = true) }
                .filter { p -> p.none { it.fileName?.toString() in SKIP_DIRS } }
                .filter { !it.name.endsWith(".als.bak", ignoreCase = true) }
                .filter { !it.name.startsWith(".") }
                .filter { p ->
                    val canonical = runCatching { p.toRealPath() }.getOrNull() ?: return@filter false
                    canonical.startsWith(realRoot)
                }
                .toList()
        }
        emit(ScanProgress.Started(total = alsFiles.size))

        // Incremental skip: read existing (path, last_modified, last_scanned) for the realRoot
        // subtree and short-circuit when the file's on-disk mtime is unchanged since last scan.
        // Saves ~hundreds of ms per unchanged project on a 1,628-project library.
        val knownByPath: Map<String, Double> = runCatching {
            catalog.catalogQueries.selectAllProjects().executeAsList()
                .associate { it.path to it.last_modified }
        }.getOrDefault(emptyMap())

        var done = 0
        val durationMillis = measureTimeMillis {
            for (file in alsFiles) {
                done++
                val abs = file.toAbsolutePath().toString()
                val parent = file.parent?.toString() ?: ""
                val name = file.fileName.toString().removeSuffix(".als")
                val mtimeSec = runCatching { Files.getLastModifiedTime(file).toMillis() / 1000.0 }
                    .getOrDefault(System.currentTimeMillis() / 1000.0)
                val sizeBytes = runCatching { Files.size(file) }.getOrDefault(0L)
                val now = System.currentTimeMillis() / 1000.0

                // Incremental fast-path: if we already have a row with the same mtime, skip the
                // gunzip+StAX parse entirely. ProjectIndexed still emits so the UI knows this
                // file was processed.
                val previousMtime = knownByPath[abs]
                if (previousMtime != null && previousMtime == mtimeSec) {
                    val id = catalog.catalogQueries.selectProjectIdByPath(path = abs).executeAsOne()
                    emit(
                        ScanProgress.ProjectIndexed(
                            total = alsFiles.size,
                            done = done,
                            projectId = id,
                            path = abs,
                            name = name,
                            missingSampleCount = 0,
                            effortScore = null,
                        )
                    )
                    continue
                }

                try {
                    val md = AlsParser.parse(file)
                    val resolution = SampleResolver.resolve(md.sampleRefs, file.parent ?: file)
                    val effort = EffortScore.compute(md, sizeBytes)
                    val effortScore = effort?.score?.toLong()
                    val effortBreakdown = effort?.breakdown?.entries?.joinToString(",") {
                        "${it.key}:${it.value}"
                    }
                    catalog.transactionWithResult<Long> {
                        catalog.catalogQueries.insertOrReplaceProject(
                            path = abs,
                            name = name,
                            parent_dir = parent,
                            tempo = md.tempo,
                            time_sig_num = md.timeSignatureNumerator?.toLong(),
                            time_sig_den = md.timeSignatureDenominator?.toLong(),
                            track_count = md.totalTrackCount.toLong(),
                            audio_tracks = md.audioTrackCount.toLong(),
                            midi_tracks = md.midiTrackCount.toLong(),
                            return_tracks = md.returnTrackCount.toLong(),
                            live_version = md.lastSavedLiveVersion,
                            last_modified = mtimeSec,
                            last_scanned = now,
                            parse_status = "ok",
                            parse_error = null,
                            mac_paths_count = md.macPathsCount.toLong(),
                            effort_score = effortScore,
                            effort_breakdown = effortBreakdown,
                            file_size_bytes = sizeBytes,
                        )
                        val id = catalog.catalogQueries.selectProjectIdByPath(path = abs).executeAsOne()

                        catalog.catalogQueries.deletePluginsForProject(project_id = id)
                        for (p in md.plugins) {
                            catalog.catalogQueries.insertProjectPlugin(
                                project_id = id,
                                plugin_name = p.name,
                                plugin_type = p.format.name.lowercase(),
                                track_name = p.trackName,
                            )
                        }
                        catalog.catalogQueries.deleteSamplesForProject(project_id = id)
                        for (s in md.sampleRefs) {
                            val isMissing = if (SampleResolver.exists(s.rawPath, file.parent ?: file)) 0L else 1L
                            catalog.catalogQueries.insertProjectSampleWithMissing(
                                project_id = id,
                                sample_path = s.rawPath,
                                is_missing = isMissing,
                            )
                        }

                        // FTS5 — SQLDelight typer can't handle MATCH/bm25; do it through the driver.
                        fts.delete(id)
                        fts.upsert(
                            rowid = id,
                            name = name,
                            parentDir = parent,
                            pluginNames = md.plugins.joinToString(" ") { it.name },
                            sampleFilenames = md.sampleRefs.joinToString(" ") {
                                it.rawPath.substringAfterLast('/')
                            },
                            notes = "",
                        )
                        id
                    }.let { id ->
                        emit(
                            ScanProgress.ProjectIndexed(
                                total = alsFiles.size,
                                done = done,
                                projectId = id,
                                path = abs,
                                name = name,
                                missingSampleCount = resolution.missingCount,
                                effortScore = effort?.score,
                            )
                        )
                    }
                } catch (ce: CancellationException) {
                    // A coroutine cancellation is structured-concurrency, not a parse error;
                    // never persist a "failed" row for it and propagate so callers can shut down.
                    throw ce
                } catch (t: Throwable) {
                    // Persist a stub row so the project shows up in the catalog flagged as
                    // broken. Mirrors `upsert_failed_parse` in the Python reference impl.
                    val reason = t.message ?: t::class.simpleName ?: "unknown"
                    catalog.transaction {
                        catalog.catalogQueries.insertOrReplaceProject(
                            path = abs,
                            name = name,
                            parent_dir = parent,
                            tempo = null,
                            time_sig_num = null,
                            time_sig_den = null,
                            track_count = 0L,
                            audio_tracks = 0L,
                            midi_tracks = 0L,
                            return_tracks = 0L,
                            live_version = null,
                            last_modified = mtimeSec,
                            last_scanned = now,
                            parse_status = "failed",
                            parse_error = reason,
                            mac_paths_count = null,
                            effort_score = null,
                            effort_breakdown = null,
                            file_size_bytes = sizeBytes,
                        )
                        val id = catalog.catalogQueries.selectProjectIdByPath(path = abs).executeAsOne()
                        catalog.catalogQueries.deletePluginsForProject(project_id = id)
                        catalog.catalogQueries.deleteSamplesForProject(project_id = id)
                        fts.delete(id)
                        fts.upsert(
                            rowid = id,
                            name = name,
                            parentDir = parent,
                            pluginNames = "",
                            sampleFilenames = "",
                            notes = "",
                        )
                    }
                    emit(
                        ScanProgress.ProjectFailed(
                            total = alsFiles.size,
                            done = done,
                            path = abs,
                            reason = reason,
                        )
                    )
                }
            }
        }
        emit(ScanProgress.Finished(total = alsFiles.size, done = done, durationMillis = durationMillis))
    }.flowOn(ioDispatcher)

    private companion object {
        // Subtrees Live auto-generates that we never want to walk into.
        val SKIP_DIRS = setOf("Backup", "Samples", "Ableton Project Info")
    }
}

/**
 * Iterates over all path components. Used to filter out files under skip-list directories.
 */
private fun Path.none(predicate: (Path) -> Boolean): Boolean {
    var p: Path? = this
    while (p != null) {
        if (predicate(p)) return false
        p = p.parent
    }
    return true
}

@Suppress("unused")
private fun PluginFormat.normalize(): String = name.lowercase()
