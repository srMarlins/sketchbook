package com.sketchbook.repo.impl

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.AppScope
import com.sketchbook.core.ProjectId
import com.sketchbook.core.SampleRefEdit
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.AlsPatchService
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.JournalRepository
import com.sketchbook.repo.MacImportFinding
import com.sketchbook.repo.MissingSampleFinding
import com.sketchbook.repo.RepairFindings
import com.sketchbook.repo.RepairRepository
import com.sketchbook.repo.SampleCandidate
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import kotlin.time.Clock

/**
 * Catalog-backed [RepairRepository]. Findings are *derived* on every observe — the catalog is
 * the single source of truth, so there's no separate "findings" table to keep in sync. The
 * repository surface only adds:
 *
 *  - **Persistent acknowledgements** via the `repair_acks` table. Without this, a dismissed
 *    mac-import would re-surface on the next scan because `mac_paths_count` is permanent on
 *    the project row. Acks are scoped per `(kind, project_id, payload)` so dismissing one
 *    missing sample doesn't mute the other 17 misses on the same project.
 *
 *  - **Truncation metadata** so the UI can show "N more not shown" without doing its own
 *    count query — `selectMissingSamples` caps at `limit` and `countMissingSamples` is a
 *    separate aggregate.
 *
 *  - **Auto-match candidates** for missing-sample findings, derived from the `samples` corpus
 *    populated by `JvmSampleScanner` over `LibraryRoot.UserSamples` roots. Filename+size is the
 *    high-confidence path; an exact-1 hit there flips on `autoMatch` and the UI offers a
 *    one-click apply. Filename-only is the fallback (multiple "kick.wav" files are common in
 *    sample libraries — we surface them as `candidates` but never auto-pick).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class SqlRepairRepository(
    private val catalog: Catalog,
    private val ioDispatcher: CoroutineDispatcher,
    private val journal: JournalRepository,
    private val patcher: AlsPatchService,
) : RepairRepository {

    /**
     * Bumped on every ack write so observers re-emit. SQLDelight's hot Flow already covers the
     * underlying selects, but `selectMacImportProjects` references `repair_acks` — that table
     * isn't tracked by the auto-Flow because it isn't the queried table. The trigger covers it.
     */
    private val ackTick = MutableStateFlow(0L)

    override fun observeFindings(projectId: ProjectId?, limit: Int): Flow<RepairFindings> {
        // Mac-import + missing-sample queries combined into a single emission. Re-runs whenever
        // either underlying query updates OR the ack tick fires (a dismissal happened).
        val macFlow = catalog.catalogQueries.selectMacImportProjects()
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.filter { projectId == null || it.id == projectId.value } }
        val missingFlow = catalog.catalogQueries.selectMissingSamples(limit.toLong())
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.filter { projectId == null || it.project_id == projectId.value } }
        return combine(macFlow, missingFlow, ackTick.onStart { emit(0L) }) { mac, miss, _ ->
            val macFindings = mac.map { row ->
                MacImportFinding(
                    projectId = ProjectId(row.id),
                    path = row.path,
                    name = row.name,
                    parentDir = row.parent_dir,
                    macPathsCount = (row.mac_paths_count ?: 0L).toInt(),
                    projectInfoMissing = (row.has_project_info ?: 1L) == 0L,
                )
            }
            val missingFindings = withContext(ioDispatcher) {
                miss.map { row ->
                    val filename = row.sample_path
                        .substringAfterLast('/')
                        .substringAfterLast('\\')
                    val sized = row.sample_size?.takeIf { it > 0 }?.let { size ->
                        catalog.catalogQueries
                            .selectSamplesByFilenameAndSize(filename = filename, size_bytes = size)
                            .executeAsList()
                    } ?: emptyList()
                    val raw = if (sized.isNotEmpty()) {
                        sized
                    } else {
                        catalog.catalogQueries.selectSamplesByFilename(filename).executeAsList()
                    }
                    val candidates = raw.take(5).map {
                        SampleCandidate(path = it.path, filename = it.filename, sizeBytes = it.size_bytes)
                    }
                    // Only flip on the auto-match chip when the corpus uniquely identifies a
                    // file by filename+size — multiple matches are too ambiguous to auto-apply.
                    // (The user can still expand the candidates list and pick one manually.)
                    val autoMatch = candidates.firstOrNull()
                        ?.takeIf { sized.isNotEmpty() && raw.size == 1 }
                    MissingSampleFinding(
                        projectId = ProjectId(row.project_id),
                        projectPath = row.project_path,
                        projectName = row.project_name,
                        missingPath = row.sample_path,
                        autoMatch = autoMatch,
                        candidates = candidates,
                    )
                }
            }
            val total = withContext(ioDispatcher) {
                catalog.catalogQueries.countMissingSamples().executeAsOne().toInt()
            }
            RepairFindings(
                macImports = macFindings,
                missingSamples = missingFindings,
                missingSamplesTotal = total,
                missingSamplesTruncated = total > missingFindings.size,
            )
        }
    }

    override suspend fun acknowledgeMacImport(projectId: ProjectId): Result<Unit> = withContext(ioDispatcher) {
        // Don't use `runCatching` at suspend boundaries: it catches `Throwable` including
        // `CancellationException`, silently breaking structured concurrency. Pattern matches
        // `SqlJournalRepository.append` / `SqlSnapshotRepository.setSnapshotLabel`.
        try {
            catalog.transaction {
                catalog.catalogQueries.insertRepairAck(
                    scope = SCOPE_MAC,
                    project_id = projectId.value,
                    payload = "",
                    acked_at = Clock.System.now().toString(),
                )
            }
            ackTick.value = ackTick.value + 1
            Result.success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun applyMacPathRepair(projectId: ProjectId): Result<Unit> = withContext(ioDispatcher) {
        try {
            val alsPath = catalog.catalogQueries
                .selectProjectById(projectId.value)
                .executeAsOne()
                .path

            // Scan the .als for `<Path Value="..."/>` entries inside `<SampleRef>` that carry
            // a Mac-style `Volume:` prefix. The catalog's `mac_paths_count` flag also fires on
            // bare POSIX `/Users/` prefixes (the parser treats those as Mac-imported too), so
            // a flagged project may have *no* `Volume:`-prefixed paths to actually repair —
            // that's the expected case for a no-op patch.
            val macPaths = runCatching { extractMacStyleSamplePaths(alsPath) }
                .getOrDefault(emptyList())
            val mapping = macPaths
                .associateWith(::macToPosix)
                .filter { (k, v) -> k != v }

            val outcome: AlsPatchService.Outcome = if (mapping.isEmpty()) {
                // Nothing to rewrite — skip the patcher (and the snapshot, since there's
                // nothing to undo) and journal NoChange. Still ack the finding so it drops.
                AlsPatchService.Outcome.NoChange
            } else {
                // Snapshot the pre-patch bytes to a sidecar mirroring W4's pattern. PR-L L7
                // currently only wires Undo for missing-sample matches, but capturing the
                // sidecar here is cheap and keeps the option open without a separate code
                // path. Best-effort: if the snapshot itself fails, we journal Failed and
                // skip the patch — losing the ability to undo silently is the worst outcome.
                val snapshotResult = runCatching {
                    val path = Paths.get(alsPath)
                    val originalBytes = Files.readAllBytes(path)
                    val sidecar = path.resolveSibling("${path.fileName}.patcher-undo")
                    Files.write(
                        sidecar,
                        originalBytes,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE,
                    )
                }

                if (snapshotResult.isFailure) {
                    AlsPatchService.Outcome.Failed
                } else {
                    // Project Mac → POSIX mapping into rich edits with `newOriginalCrc=0L`.
                    // Path change invalidates the CRC (Live recomputes on next save). We don't
                    // have a candidate file to stat in the Mac-path repair case, so size /
                    // mtime / RelativePathType are left untouched.
                    val edits = mapping.map { (mac, posix) ->
                        SampleRefEdit(oldPath = mac, newPath = posix, newOriginalCrc = 0L)
                    }
                    runCatching { patcher.patch(alsPath, edits) }
                        .getOrElse { AlsPatchService.Outcome.Failed }
                }
            }

            catalog.transaction {
                catalog.catalogQueries.insertRepairAck(
                    scope = SCOPE_MAC,
                    project_id = projectId.value,
                    payload = "",
                    acked_at = Clock.System.now().toString(),
                )
            }
            ackTick.value = ackTick.value + 1

            journal.append(
                JournalEntry(
                    timestamp = Clock.System.now(),
                    projectId = projectId,
                    action = ActionRecord.MacPathRepaired(
                        mappingCount = mapping.size,
                        alsOutcome = outcome.name,
                    ),
                ),
            )
            Result.success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun dismissMissingSample(
        projectId: ProjectId,
        missingPath: String,
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            catalog.transaction {
                catalog.catalogQueries.insertRepairAck(
                    scope = SCOPE_MISS,
                    project_id = projectId.value,
                    payload = missingPath,
                    acked_at = Clock.System.now().toString(),
                )
            }
            ackTick.value = ackTick.value + 1
            Result.success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun applyMissingSampleMatch(
        projectId: ProjectId,
        missingPath: String,
        candidatePath: String,
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            // Resolve the on-disk .als path before any catalog mutation so the patcher and the
            // journal entry agree on which file the user actually edited (a concurrent rename
            // would otherwise split the audit trail).
            val alsPath = catalog.catalogQueries
                .selectProjectById(projectId.value)
                .executeAsOne()
                .path

            // PR-W W4 — snapshot the pre-patch bytes to a sidecar before delegating to the
            // patcher. The sidecar (`<als>.patcher-undo`) lives next to the .als so it follows
            // the project around on moves; PR-L L7's Undo pill reads it back via
            // restoreMissingSampleMatch. Best-effort: if the snapshot itself fails (file gone,
            // permissions), we journal an alsOutcome of Failed and skip the patch — losing the
            // ability to undo silently is the worst possible outcome.
            val snapshotResult = runCatching {
                val path = Paths.get(alsPath)
                val originalBytes = Files.readAllBytes(path)
                val sidecar = path.resolveSibling("${path.fileName}.patcher-undo")
                Files.write(
                    sidecar,
                    originalBytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
            }

            // Build a rich [SampleRefEdit]. Stat the candidate so the patched .als carries
            // accurate `OriginalFileSize` + `LastModDate` for the *new* file (Live uses these
            // to short-circuit re-validation on next open). `OriginalCrc=0L` because the path
            // changed → the existing CRC is by definition stale; Live recomputes on next save.
            // `RelativePathType=1` (absolute) since we're writing an absolute candidate path.
            // `runCatching` on the stat calls so a vanished candidate doesn't blow up the apply.
            val candidateStat = runCatching {
                val p = Paths.get(candidatePath)
                Pair(
                    Files.size(p),
                    Files.getLastModifiedTime(p).to(TimeUnit.SECONDS),
                )
            }.getOrNull()
            val edit = SampleRefEdit(
                oldPath = missingPath,
                newPath = candidatePath,
                newRelativePathType = 1,
                newOriginalFileSize = candidateStat?.first,
                newOriginalCrc = 0L,
                newLastModDate = candidateStat?.second,
            )

            // Rewrite the .als; the catalog flip is a tiny in-memory transaction and the patch
            // is the slow + failure-prone step. If the patch raises, we honor the user's pick
            // anyway (catalog still flips below) — the journal records the outcome so a later
            // retry pass / Undo can act on un-rewritten files.
            val outcome = if (snapshotResult.isFailure) {
                AlsPatchService.Outcome.Failed
            } else {
                runCatching {
                    patcher.patch(alsPath, listOf(edit))
                }.getOrElse { AlsPatchService.Outcome.Failed }
            }

            catalog.transaction {
                catalog.catalogQueries.applyMissingSampleMatch(
                    new_path = candidatePath,
                    project_id = projectId.value,
                    old_path = missingPath,
                )
            }
            // Re-emit findings so the row drops out of Needs Attention immediately. The
            // underlying SQLDelight Flow does observe project_samples writes, but bouncing the
            // tick here keeps the timing tight (no relying on whether the asFlow() observer has
            // landed yet).
            ackTick.value = ackTick.value + 1

            journal.append(
                JournalEntry(
                    timestamp = Clock.System.now(),
                    projectId = projectId,
                    action = ActionRecord.MissingSampleMapped(
                        missingPath = missingPath,
                        candidatePath = candidatePath,
                        alsOutcome = outcome.name,
                    ),
                ),
            )
            Result.success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun restoreMissingSampleMatch(
        projectId: ProjectId,
        missingPath: String,
        candidatePath: String,
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            val alsPath = catalog.catalogQueries
                .selectProjectById(projectId.value)
                .executeAsOne()
                .path

            // Look up the sidecar created by applyMissingSampleMatch. Sentinel `NoUndoBytes`
            // covers the case where no sidecar exists (apply happened pre-W4, sidecar was
            // cleaned up, etc.) — the catalog still reverts so the finding re-surfaces.
            val path: Path = Paths.get(alsPath)
            val sidecar = path.resolveSibling("${path.fileName}.patcher-undo")
            val outcomeName = if (Files.notExists(sidecar)) {
                NO_UNDO_BYTES
            } else {
                val bytes = Files.readAllBytes(sidecar)
                val outcome = runCatching { patcher.restore(alsPath, bytes) }
                    .getOrElse { AlsPatchService.Outcome.Failed }
                // Best-effort cleanup; a leftover sidecar is benign (next apply overwrites it).
                runCatching { Files.deleteIfExists(sidecar) }
                outcome.name
            }

            catalog.transaction {
                catalog.catalogQueries.revertMissingSampleMatch(
                    old_path = missingPath,
                    project_id = projectId.value,
                    new_path = candidatePath,
                )
            }
            ackTick.value = ackTick.value + 1

            journal.append(
                JournalEntry(
                    timestamp = Clock.System.now(),
                    projectId = projectId,
                    action = ActionRecord.MissingSampleUnmapped(
                        missingPath = missingPath,
                        candidatePath = candidatePath,
                        alsOutcome = outcomeName,
                    ),
                ),
            )
            Result.success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private companion object {
        const val SCOPE_MAC = "mac_import"
        const val SCOPE_MISS = "missing_sample"
        const val NO_UNDO_BYTES = "NoUndoBytes"
    }
}

/**
 * Cached StAX factory for the Mac-path scan. Building a fresh one per repair would cost a
 * non-trivial amount on bulk pass; configure once, use many. Mirrors the equivalent factory in
 * [com.sketchbook.als.AlsRewriter] (we can't share it because pulling the parser-als module in
 * would create a cycle through `:shared:sync-io`'s patcher adapter).
 */
private val MAC_SCAN_INPUT_FACTORY: XMLInputFactory = XMLInputFactory.newFactory().apply {
    setProperty(XMLInputFactory.IS_COALESCING, true)
    setProperty(XMLInputFactory.SUPPORT_DTD, false)
    setProperty("javax.xml.stream.isSupportingExternalEntities", false)
}

/**
 * Drop the `Volume:` prefix from a Mac-style absolute path (`Macintosh HD:/Users/...` →
 * `/Users/...`). Anything before the first `:` is treated as the volume name. If the path has no
 * `:`, it's returned unchanged — the caller filters those out via `filter { k != v }` so the
 * mapping passed to the patcher is exactly the set of *changed* paths.
 */
private fun macToPosix(macPath: String): String {
    val colon = macPath.indexOf(':')
    return if (colon == -1) macPath else macPath.substring(colon + 1)
}

/**
 * Walk a gzipped `.als` once and collect every `<Path Value="..."/>` value that lives inside a
 * `<SampleRef>` and carries a `:` (i.e., a Mac-style absolute path). StAX-only — no DOM, so heap
 * stays bounded the same way [com.sketchbook.als.AlsParser] does. We scan the file in
 * applyMacPathRepair instead of storing per-finding paths in the catalog because (a) the
 * parser-side counter doesn't distinguish `Volume:`-prefixed paths from POSIX `/Users/` ones,
 * and (b) bulk-storing every Mac path in a separate table for findings that may never be
 * actioned wastes space in the common case.
 */
private fun extractMacStyleSamplePaths(alsPath: String): List<String> {
    val out = LinkedHashSet<String>()
    Files.newInputStream(Paths.get(alsPath)).use { fileIn ->
        GZIPInputStream(BufferedInputStream(fileIn)).use { gzIn ->
            val reader = MAC_SCAN_INPUT_FACTORY.createXMLStreamReader(gzIn, "UTF-8")
            try {
                var depthInsideSampleRef = 0
                while (reader.hasNext()) {
                    when (reader.next()) {
                        XMLStreamConstants.START_ELEMENT -> {
                            val name = reader.localName
                            if (name == "SampleRef") depthInsideSampleRef++
                            if (depthInsideSampleRef > 0 && name == "Path") {
                                val value = (0 until reader.attributeCount)
                                    .firstOrNull { reader.getAttributeLocalName(it) == "Value" }
                                    ?.let { reader.getAttributeValue(it) }
                                if (value != null && ':' in value) out += value
                            }
                        }

                        XMLStreamConstants.END_ELEMENT -> {
                            if (reader.localName == "SampleRef") depthInsideSampleRef--
                        }

                        else -> {}
                    }
                }
            } finally {
                reader.close()
            }
        }
    }
    return out.toList()
}
