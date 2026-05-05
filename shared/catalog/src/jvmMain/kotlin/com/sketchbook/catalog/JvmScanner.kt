package com.sketchbook.catalog

import com.sketchbook.als.AlsParser
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.PluginFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.nio.file.FileVisitOption
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
 */
class JvmScanner(
    private val catalog: Catalog,
    private val fts: CatalogFts,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    fun scan(root: Path): Flow<ScanProgress> = flow {
        val started = System.currentTimeMillis()
        val alsFiles = Files.walk(root, FileVisitOption.FOLLOW_LINKS).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() && it.name.endsWith(".als", ignoreCase = true) }
                // Skip Backup directories — Live writes versioned dupes there.
                .filter { p -> p.none { it.fileName?.toString() == "Backup" } }
                .toList()
        }
        emit(ScanProgress.Started(total = alsFiles.size))

        var done = 0
        val durationMillis = measureTimeMillis {
            for (file in alsFiles) {
                done++
                try {
                    val md = AlsParser.parse(file)
                    val now = System.currentTimeMillis() / 1000.0
                    val parent = file.parent?.toString() ?: ""
                    val name = file.fileName.toString().removeSuffix(".als")
                    catalog.transactionWithResult<Long> {
                        catalog.catalogQueries.insertOrReplaceProject(
                            path = file.toAbsolutePath().toString(),
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
                            last_modified = Files.getLastModifiedTime(file).toMillis() / 1000.0,
                            last_scanned = now,
                            parse_status = "ok",
                            mac_paths_count = md.macPathsCount.toLong(),
                        )
                        val id = catalog.catalogQueries.selectProjectIdByPath(path = file.toAbsolutePath().toString()).executeAsOne()

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
                            catalog.catalogQueries.insertProjectSample(
                                project_id = id,
                                sample_path = s.rawPath,
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
                                path = file.toAbsolutePath().toString(),
                                name = name,
                            )
                        )
                    }
                } catch (t: Throwable) {
                    emit(
                        ScanProgress.ProjectFailed(
                            total = alsFiles.size,
                            done = done,
                            path = file.toAbsolutePath().toString(),
                            reason = t.message ?: t::class.simpleName ?: "unknown",
                        )
                    )
                }
            }
        }
        emit(ScanProgress.Finished(total = alsFiles.size, done = done, durationMillis = durationMillis))
    }.flowOn(ioDispatcher)
}

/**
 * Iterates over all path components. Used to filter out files under `Backup/` directories.
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
