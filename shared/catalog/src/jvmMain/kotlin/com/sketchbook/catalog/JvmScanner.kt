package com.sketchbook.catalog

import com.sketchbook.als.AlsParser
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.EffortScore
import com.sketchbook.core.PluginFormat
import com.sketchbook.core.ProjectMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence
import kotlin.system.measureTimeMillis

/**
 * Walks a library root, parses every `.als`, upserts catalog rows. Progress as a cold Flow.
 *
 * **Parallel parse, serialized write.** Parsing is CPU-bound (gunzip + StAX) and benefits from
 * fanning out across cores; SQLite writes must serialize. The flow uses
 * [flatMapMerge] with bounded concurrency to drive parses on `Dispatchers.Default` and drains
 * results into [BATCH_SIZE]-sized transactional writes. On a 1,628-project library this
 * collapses ~1,628 fsync'd transactions (default `synchronous=FULL` would mean ~1,628 fsyncs)
 * down to ~17, while keeping every CPU core warm during the gzip pass.
 *
 * **Per-file pipeline.** For each `.als`:
 *   1. `AlsParser.parse` → `ProjectMetadata`. Failures land in a `parse_status='failed'` row
 *      with the exception message in `parse_error` (so the Broken shelf surfaces them).
 *   2. `SampleResolver.resolve` against the `.als`'s parent directory → which sample refs are
 *      missing on disk. The per-sample `is_missing` flag persists on `project_samples` rows so
 *      the detail panel can list them; the count surfaces on `ProjectRow.missingSampleCount`.
 *   3. `EffortScore.compute(meta, fileSize)` → 0..100 score plus breakdown. Persists to
 *      `projects.effort_score` / `effort_breakdown`.
 */
class JvmScanner(
    private val catalog: Catalog,
    private val fts: CatalogFts,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Max parallel `.als` parses in flight. Capped to [MAX_PARALLEL_PARSE]. */
    parseConcurrency: Int = MAX_PARALLEL_PARSE,
    /** Number of projects per write transaction. */
    private val batchSize: Int = BATCH_SIZE,
) {

    private val parseConcurrency: Int =
        parseConcurrency.coerceIn(1, MAX_PARALLEL_PARSE)

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun scan(root: Path): Flow<ScanProgress> = flow {
        // No FOLLOW_LINKS: a symlink in the user's library to `/` (or a network share, or another
        // user's home) would otherwise be silently traversed and persist absolute paths into the
        // catalog where the MCP server surfaces them to an LLM. Sandbox to the canonical root so
        // even a non-link "junction" can't escape.
        val realRoot = runCatching { root.toRealPath() }.getOrDefault(root.toAbsolutePath())
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
        val total = alsFiles.size

        // Incremental skip: read existing (path, last_modified) once and short-circuit when a
        // file's on-disk mtime is unchanged. Saves the gunzip+StAX cost on unchanged projects
        // (the dominant case for repeat scans on a 1,628-project library).
        val knownByPath: Map<String, Double> = runCatching {
            catalog.catalogQueries.selectAllProjects().executeAsList()
                .associate { it.path to it.last_modified }
        }.getOrDefault(emptyMap())

        var done = 0
        val pending = ArrayList<ParseOutcome>(batchSize)
        val durationMillis = measureTimeMillis {
            val parsed: Flow<ParseOutcome> = alsFiles.asFlow()
                .flatMapMerge(concurrency = parseConcurrency) { file ->
                    flow { emit(parseOne(file, knownByPath)) }
                        .flowOn(parseDispatcher)
                }
                .buffer(parseConcurrency * 2)

            parsed.collect { outcome ->
                pending += outcome
                if (pending.size >= batchSize) {
                    done = flushBatch(pending, total, done)
                }
            }
            if (pending.isNotEmpty()) {
                done = flushBatch(pending, total, done)
            }
        }
        emit(ScanProgress.Finished(total = total, done = done, durationMillis = durationMillis))
    }.flowOn(ioDispatcher)

    /**
     * Drain [batch] in a single SQLite transaction, emit progress events for each project.
     * Returns the new `done` count. Mutates [batch] (clears it on the way out) so the caller
     * can reuse the buffer.
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<ScanProgress>.flushBatch(
        batch: MutableList<ParseOutcome>,
        total: Int,
        startingDone: Int,
    ): Int {
        val snapshot = batch.toList()
        batch.clear()
        val now = System.currentTimeMillis() / 1000.0
        val (idsByPath, indexedReports, failedReports) = withContext(ioDispatcher) {
            val ids = HashMap<String, Long>(snapshot.size)
            val indexed = ArrayList<IndexedReport>()
            val failed = ArrayList<FailedReport>()
            catalog.transaction {
                for (outcome in snapshot) {
                    when (outcome) {
                        is ParseOutcome.Skipped -> {
                            val id = catalog.catalogQueries.selectProjectIdByPath(outcome.abs)
                                .executeAsOne()
                            ids[outcome.abs] = id
                            indexed += IndexedReport(
                                outcome = outcome,
                                projectId = id,
                                missingSampleCount = 0,
                                effortScore = null,
                            )
                        }
                        is ParseOutcome.Ok -> {
                            persistOk(outcome, now)
                            val id = catalog.catalogQueries.selectProjectIdByPath(outcome.abs)
                                .executeAsOne()
                            ids[outcome.abs] = id
                            indexed += IndexedReport(
                                outcome = outcome,
                                projectId = id,
                                missingSampleCount = outcome.resolution.missingCount,
                                effortScore = outcome.effort?.score,
                            )
                        }
                        is ParseOutcome.Failed -> {
                            persistFailed(outcome, now)
                            val id = catalog.catalogQueries.selectProjectIdByPath(outcome.abs)
                                .executeAsOne()
                            ids[outcome.abs] = id
                            failed += FailedReport(outcome = outcome, projectId = id)
                        }
                    }
                }
            }
            Triple(ids, indexed, failed)
        }
        var done = startingDone
        for (report in indexedReports) {
            done++
            emit(
                ScanProgress.ProjectIndexed(
                    total = total,
                    done = done,
                    projectId = report.projectId,
                    path = report.outcome.abs,
                    name = report.outcome.name,
                    missingSampleCount = report.missingSampleCount,
                    effortScore = report.effortScore,
                )
            )
        }
        for (report in failedReports) {
            done++
            emit(
                ScanProgress.ProjectFailed(
                    total = total,
                    done = done,
                    path = report.outcome.abs,
                    reason = report.outcome.reason,
                )
            )
        }
        // Suppress unused warning on idsByPath; kept for future event payloads.
        @Suppress("UNUSED_VARIABLE") val unused = idsByPath
        return done
    }

    /**
     * Parse a single file. Pure CPU + filesystem read — no catalog access. Safe to run on
     * `Dispatchers.Default` from many coroutines in parallel.
     */
    private fun parseOne(file: Path, knownByPath: Map<String, Double>): ParseOutcome {
        val abs = file.toAbsolutePath().toString()
        val parent = file.parent?.toString() ?: ""
        val name = file.fileName.toString().removeSuffix(".als")
        val mtimeSec = runCatching { Files.getLastModifiedTime(file).toMillis() / 1000.0 }
            .getOrDefault(System.currentTimeMillis() / 1000.0)
        val sizeBytes = runCatching { Files.size(file) }.getOrDefault(0L)
        val previousMtime = knownByPath[abs]
        if (previousMtime != null && previousMtime == mtimeSec) {
            return ParseOutcome.Skipped(abs = abs, name = name, parent = parent, sizeBytes = sizeBytes, mtimeSec = mtimeSec)
        }
        return try {
            val md = AlsParser.parse(file)
            val resolution = SampleResolver.resolve(md.sampleRefs, file.parent ?: file)
            val effort = EffortScore.compute(md, sizeBytes)
            ParseOutcome.Ok(
                abs = abs,
                name = name,
                parent = parent,
                sizeBytes = sizeBytes,
                mtimeSec = mtimeSec,
                md = md,
                resolution = resolution,
                effort = effort,
                projectDir = file.parent ?: file,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val reason = t.message ?: t::class.simpleName ?: "unknown"
            ParseOutcome.Failed(abs = abs, name = name, parent = parent, sizeBytes = sizeBytes, mtimeSec = mtimeSec, reason = reason)
        }
    }

    private fun persistOk(o: ParseOutcome.Ok, now: Double) {
        val effortScore = o.effort?.score?.toLong()
        val effortBreakdown = o.effort?.breakdown?.entries?.joinToString(",") {
            "${it.key}:${it.value}"
        }
        catalog.catalogQueries.insertOrReplaceProject(
            path = o.abs,
            name = o.name,
            parent_dir = o.parent,
            tempo = o.md.tempo,
            time_sig_num = o.md.timeSignatureNumerator?.toLong(),
            time_sig_den = o.md.timeSignatureDenominator?.toLong(),
            track_count = o.md.totalTrackCount.toLong(),
            audio_tracks = o.md.audioTrackCount.toLong(),
            midi_tracks = o.md.midiTrackCount.toLong(),
            return_tracks = o.md.returnTrackCount.toLong(),
            live_version = o.md.lastSavedLiveVersion,
            last_modified = o.mtimeSec,
            last_scanned = now,
            parse_status = "ok",
            parse_error = null,
            mac_paths_count = o.md.macPathsCount.toLong(),
            effort_score = effortScore,
            effort_breakdown = effortBreakdown,
            file_size_bytes = o.sizeBytes,
        )
        val id = catalog.catalogQueries.selectProjectIdByPath(path = o.abs).executeAsOne()
        catalog.catalogQueries.deletePluginsForProject(project_id = id)
        for (p in o.md.plugins) {
            catalog.catalogQueries.insertProjectPlugin(
                project_id = id,
                plugin_name = p.name,
                plugin_type = p.format.name.lowercase(),
                track_name = p.trackName,
            )
        }
        catalog.catalogQueries.deleteSamplesForProject(project_id = id)
        for (s in o.md.sampleRefs) {
            val isMissing = if (SampleResolver.exists(s.rawPath, o.projectDir)) 0L else 1L
            catalog.catalogQueries.insertProjectSampleWithMissing(
                project_id = id,
                sample_path = s.rawPath,
                is_missing = isMissing,
            )
        }
        fts.delete(id)
        fts.upsert(
            rowid = id,
            name = o.name,
            parentDir = o.parent,
            pluginNames = o.md.plugins.joinToString(" ") { it.name },
            sampleFilenames = o.md.sampleRefs.joinToString(" ") { it.rawPath.substringAfterLast('/') },
            notes = "",
        )
    }

    private fun persistFailed(o: ParseOutcome.Failed, now: Double) {
        catalog.catalogQueries.insertOrReplaceProject(
            path = o.abs,
            name = o.name,
            parent_dir = o.parent,
            tempo = null,
            time_sig_num = null,
            time_sig_den = null,
            track_count = 0L,
            audio_tracks = 0L,
            midi_tracks = 0L,
            return_tracks = 0L,
            live_version = null,
            last_modified = o.mtimeSec,
            last_scanned = now,
            parse_status = "failed",
            parse_error = o.reason,
            mac_paths_count = null,
            effort_score = null,
            effort_breakdown = null,
            file_size_bytes = o.sizeBytes,
        )
        val id = catalog.catalogQueries.selectProjectIdByPath(path = o.abs).executeAsOne()
        catalog.catalogQueries.deletePluginsForProject(project_id = id)
        catalog.catalogQueries.deleteSamplesForProject(project_id = id)
        fts.delete(id)
        fts.upsert(
            rowid = id,
            name = o.name,
            parentDir = o.parent,
            pluginNames = "",
            sampleFilenames = "",
            notes = "",
        )
    }

    private companion object {
        // Subtrees Live auto-generates that we never want to walk into.
        val SKIP_DIRS = setOf("Backup", "Samples", "Ableton Project Info")

        // Bounded — Live's gunzip/StAX scales modestly past 4 cores; beyond that we starve the
        // disk read queue and pay JIT/cache thrash without speedup.
        const val MAX_PARALLEL_PARSE: Int = 4

        // Sized so each transaction is large enough to amortize fsync but small enough that the
        // UI's progress bar advances at a comfortable cadence.
        const val BATCH_SIZE: Int = 64
    }
}

/** Outcome of a single-file parse. Pure data — no DB access. */
private sealed interface ParseOutcome {
    val abs: String
    val name: String
    val parent: String
    val sizeBytes: Long
    val mtimeSec: Double

    data class Skipped(
        override val abs: String,
        override val name: String,
        override val parent: String,
        override val sizeBytes: Long,
        override val mtimeSec: Double,
    ) : ParseOutcome

    data class Ok(
        override val abs: String,
        override val name: String,
        override val parent: String,
        override val sizeBytes: Long,
        override val mtimeSec: Double,
        val md: ProjectMetadata,
        val resolution: SampleResolver.Resolution,
        val effort: EffortScore.Result?,
        val projectDir: Path,
    ) : ParseOutcome

    data class Failed(
        override val abs: String,
        override val name: String,
        override val parent: String,
        override val sizeBytes: Long,
        override val mtimeSec: Double,
        val reason: String,
    ) : ParseOutcome
}

private data class IndexedReport(
    val outcome: ParseOutcome,
    val projectId: Long,
    val missingSampleCount: Int,
    val effortScore: Int?,
)

private data class FailedReport(
    val outcome: ParseOutcome.Failed,
    val projectId: Long,
)

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
