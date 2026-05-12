package com.sketchbook.featureneedsattention

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sketchbook.core.AppScope
import com.sketchbook.core.ProjectId
import com.sketchbook.core.runCatchingCancellable
import com.sketchbook.repo.MacImportFinding
import com.sketchbook.repo.MissingSampleFinding
import com.sketchbook.repo.RepairRepository
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Repair surface — Mac-imported and Missing-sample findings, with bulk action support and
 * in-flight ("repairing…" / "applying…") feedback.
 *
 * State derivation lives here so the screen is purely a renderer:
 *  - [State.macEntries] sorts mac-imports by stripped base name (so `pencils_02/03/04` cluster)
 *    and pre-computes a project-boundary flag the screen renders as a small spacer between
 *    clusters.
 *  - [State.missingByConfidence] partitions missing samples into auto-match / multi-candidate /
 *    no-candidate buckets, each pre-sorted by project name with the same boundary flag.
 *  - [State.pendingMacRepairs] / [State.pendingMissingApplies] are auto-cleaned: when the
 *    upstream findings flow re-emits without a row (the SQL ack arrives a few seconds after the
 *    mutator returns), the corresponding pending entry drops out automatically. This avoids a
 *    "flash repairing… then back to idle" gap during the SQL re-emit window.
 */
@ContributesIntoMap(AppScope::class)
@ViewModelKey
@Inject
class NeedsAttentionViewModel(
    private val repository: RepairRepository,
) : ViewModel() {
    private val _effects =
        MutableSharedFlow<Effect>(
            replay = 0,
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    private val pending = MutableStateFlow(Pending())
    private val search = MutableStateFlow("")

    val state: StateFlow<State> =
        combine(
            repository.observeFindings(),
            pending,
            search,
        ) { findings, p, q ->
            // Filter the upstream lists by the search query before bucketizing/sorting so the
            // project-boundary flags reflect only the visible rows. Empty query short-circuits.
            val visibleMac =
                if (q.isBlank()) {
                    findings.macImports
                } else {
                    findings.macImports.filter { it.matchesSearch(q) }
                }
            val visibleMissing =
                if (q.isBlank()) {
                    findings.missingSamples
                } else {
                    findings.missingSamples.filter { it.matchesSearch(q) }
                }
            val macEntries = macEntries(visibleMac)
            val missingByConfidence = bucketize(visibleMissing)
            // Auto-clean: if a pending row has already left the findings list, drop the pending
            // flag. Avoids the row visually flashing back to idle during the SQL ack/re-emit gap.
            val macIds = findings.macImports.mapTo(HashSet(findings.macImports.size)) { it.projectId }
            val missingKeys =
                findings.missingSamples.mapTo(
                    HashSet(findings.missingSamples.size),
                ) { it.projectId to it.missingPath }
            State(
                macImports = visibleMac,
                macEntries = macEntries,
                missingSamples = visibleMissing,
                missingByConfidence = missingByConfidence,
                missingSamplesTotal = findings.missingSamplesTotal,
                missingSamplesTruncated = findings.missingSamplesTruncated,
                pendingMacRepairs = p.macRepairs.intersect(macIds),
                pendingMissingApplies = p.missingApplies.intersect(missingKeys),
                search = q,
                loading = false,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, State(loading = true))

    private fun MacImportFinding.matchesSearch(q: String): Boolean {
        val needle = q.lowercase()
        return name.lowercase().contains(needle) ||
            path.lowercase().contains(needle) ||
            parentDir.lowercase().contains(needle)
    }

    private fun MissingSampleFinding.matchesSearch(q: String): Boolean {
        val needle = q.lowercase()
        return projectName.lowercase().contains(needle) ||
            missingPath.lowercase().contains(needle) ||
            autoMatch?.path?.lowercase()?.contains(needle) == true ||
            candidates.any { it.path.lowercase().contains(needle) }
    }

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.SetSearch -> {
                search.value = intent.query
            }

            is Intent.AckMacImport -> {
                viewModelScope.launch {
                    val r = repository.acknowledgeMacImport(intent.projectId)
                    emitAck(r.isSuccess, intent.projectId.value.toString(), "ack")
                }
            }

            is Intent.RepairMacPaths -> {
                viewModelScope.launch {
                    pending.update { it.copy(macRepairs = it.macRepairs + intent.projectId) }
                    val r =
                        runCatchingCancellable { repository.applyMacPathRepair(intent.projectId) }
                            .getOrElse { Result.failure(it) }
                    if (r.isSuccess) {
                        // Auto-cleanup will drop pending once the row leaves macImports.
                        _effects.tryEmit(Effect.MatchApplied(intent.projectId.value.toString()))
                    } else {
                        pending.update { it.copy(macRepairs = it.macRepairs - intent.projectId) }
                        _effects.tryEmit(Effect.Failed(intent.projectId.value.toString(), "repair failed"))
                    }
                }
            }

            is Intent.DismissMissingSample -> {
                viewModelScope.launch {
                    val r = repository.dismissMissingSample(intent.projectId, intent.missingPath)
                    emitAck(r.isSuccess, intent.projectId.value.toString(), "dismiss")
                }
            }

            is Intent.ApplyMatch -> {
                viewModelScope.launch {
                    val key = intent.projectId to intent.missingPath
                    pending.update { it.copy(missingApplies = it.missingApplies + key) }
                    val r =
                        runCatchingCancellable {
                            repository.applyMissingSampleMatch(
                                projectId = intent.projectId,
                                missingPath = intent.missingPath,
                                candidatePath = intent.candidatePath,
                            )
                        }.getOrElse { Result.failure(it) }
                    if (r.isSuccess) {
                        _effects.tryEmit(Effect.MatchApplied(intent.projectId.value.toString()))
                    } else {
                        pending.update { it.copy(missingApplies = it.missingApplies - key) }
                        _effects.tryEmit(Effect.Failed(intent.projectId.value.toString(), "apply failed"))
                    }
                }
            }

            is Intent.BulkAck -> {
                viewModelScope.launch {
                    val successes = mutableListOf<String>()
                    val failures = mutableListOf<String>()
                    for (id in intent.projectIds) {
                        val r =
                            runCatchingCancellable { repository.acknowledgeMacImport(id) }
                                .getOrElse { Result.failure(it) }
                        val k = id.value.toString()
                        if (r.isSuccess) successes += k else failures += k
                    }
                    _effects.tryEmit(Effect.BulkAcked(successes, failures))
                }
            }

            is Intent.BulkRepairMacPaths -> {
                viewModelScope.launch {
                    pending.update { it.copy(macRepairs = it.macRepairs + intent.projectIds) }
                    val successes = mutableListOf<String>()
                    val failures = mutableListOf<String>()
                    for (id in intent.projectIds) {
                        val r =
                            runCatchingCancellable { repository.applyMacPathRepair(id) }
                                .getOrElse { Result.failure(it) }
                        val k = id.value.toString()
                        if (r.isSuccess) {
                            successes += k
                        } else {
                            failures += k
                            pending.update { it.copy(macRepairs = it.macRepairs - id) }
                        }
                    }
                    _effects.tryEmit(Effect.BulkRepaired(successes, failures))
                }
            }

            is Intent.BulkApplyAutoMatch -> {
                viewModelScope.launch {
                    val keys = intent.findings.map { it.projectId to it.missingPath }
                    pending.update { it.copy(missingApplies = it.missingApplies + keys) }
                    val successes = mutableListOf<String>()
                    val failures = mutableListOf<String>()
                    for (f in intent.findings) {
                        val key = f.projectId to f.missingPath
                        val resultKey = "${f.projectId.value}|${f.missingPath}"
                        val auto = f.autoMatch
                        if (auto == null) {
                            failures += resultKey
                            pending.update { it.copy(missingApplies = it.missingApplies - key) }
                            continue
                        }
                        val r =
                            runCatchingCancellable {
                                repository.applyMissingSampleMatch(f.projectId, f.missingPath, auto.path)
                            }.getOrElse { Result.failure(it) }
                        if (r.isSuccess) {
                            successes += resultKey
                        } else {
                            failures += resultKey
                            pending.update { it.copy(missingApplies = it.missingApplies - key) }
                        }
                    }
                    _effects.tryEmit(Effect.BulkApplied(successes, failures))
                }
            }

            is Intent.BulkDismiss -> {
                viewModelScope.launch {
                    val successes = mutableListOf<String>()
                    val failures = mutableListOf<String>()
                    for (f in intent.findings) {
                        val r =
                            runCatchingCancellable {
                                repository.dismissMissingSample(f.projectId, f.missingPath)
                            }.getOrElse { Result.failure(it) }
                        val k = "${f.projectId.value}|${f.missingPath}"
                        if (r.isSuccess) successes += k else failures += k
                    }
                    _effects.tryEmit(Effect.BulkDismissed(successes, failures))
                }
            }
        }
    }

    private fun emitAck(
        success: Boolean,
        key: String,
        kind: String,
    ) {
        if (success) {
            _effects.tryEmit(Effect.Acknowledged(key, kind))
        } else {
            _effects.tryEmit(Effect.Failed(key, "$kind failed"))
        }
    }

    /**
     * Pre-sort mac-imports so variations of the same project (`pencils_03/04`,
     * `remember_barcelona_*_mix_*`) cluster together. Sort by stripped base name
     * (case-insensitive), then mac-paths-count desc so the heaviest variation reads first within
     * each cluster. The boundary flag fires when the base name differs from the prior row — the
     * screen renders a small spacer at that point.
     */
    private fun macEntries(macs: List<MacImportFinding>): List<MacEntry> {
        if (macs.isEmpty()) return emptyList()
        val withBase = macs.map { it to baseName(it.name) }
        val sorted =
            withBase.sortedWith(
                compareBy<Pair<MacImportFinding, String>> { it.second.lowercase() }
                    .thenByDescending { it.first.macPathsCount },
            )
        var prev: String? = null
        return sorted.mapIndexed { index, (f, base) ->
            val boundary = index > 0 && !base.equals(prev, ignoreCase = true)
            prev = base
            MacEntry(finding = f, isProjectBoundary = boundary)
        }
    }

    /**
     * Heuristic: strip trailing `_NN` and `_<word>_NN` suffixes (mix/v/take/alt/remix style)
     * iteratively until no match. Used only for clustering; not a stable identifier.
     */
    private fun baseName(name: String): String {
        var n = name
        while (true) {
            val next = n.replace(VARIATION_SUFFIX, "")
            if (next == n) return n
            n = next
        }
    }

    private fun bucketize(missing: List<MissingSampleFinding>): MissingByConfidence {
        if (missing.isEmpty()) return MissingByConfidence()
        val auto = mutableListOf<MissingSampleFinding>()
        val multi = mutableListOf<MissingSampleFinding>()
        val none = mutableListOf<MissingSampleFinding>()
        for (m in missing) {
            when {
                m.autoMatch != null -> auto += m
                m.candidates.isNotEmpty() -> multi += m
                else -> none += m
            }
        }
        return MissingByConfidence(
            autoMatch = entriesByProject(auto),
            multiCandidate = entriesByProject(multi),
            noCandidate = entriesByProject(none),
        )
    }

    private fun entriesByProject(findings: List<MissingSampleFinding>): List<MissingEntry> {
        if (findings.isEmpty()) return emptyList()
        val sorted = findings.sortedBy { it.projectName.lowercase() }
        var prev: String? = null
        return sorted.mapIndexed { index, f ->
            val boundary = index > 0 && prev != f.projectName
            prev = f.projectName
            MissingEntry(finding = f, isProjectBoundary = boundary)
        }
    }

    @Immutable
    data class MacEntry(
        val finding: MacImportFinding,
        val isProjectBoundary: Boolean,
    )

    @Immutable
    data class MissingEntry(
        val finding: MissingSampleFinding,
        val isProjectBoundary: Boolean,
    )

    @Immutable
    data class MissingByConfidence(
        val autoMatch: List<MissingEntry> = emptyList(),
        val multiCandidate: List<MissingEntry> = emptyList(),
        val noCandidate: List<MissingEntry> = emptyList(),
    )

    @Immutable
    data class State(
        val macImports: List<MacImportFinding> = emptyList(),
        val macEntries: List<MacEntry> = emptyList(),
        val missingSamples: List<MissingSampleFinding> = emptyList(),
        val missingByConfidence: MissingByConfidence = MissingByConfidence(),
        val missingSamplesTotal: Int = 0,
        val missingSamplesTruncated: Boolean = false,
        val pendingMacRepairs: Set<ProjectId> = emptySet(),
        val pendingMissingApplies: Set<Pair<ProjectId, String>> = emptySet(),
        val search: String = "",
        val loading: Boolean = false,
    )

    private data class Pending(
        val macRepairs: Set<ProjectId> = emptySet(),
        val missingApplies: Set<Pair<ProjectId, String>> = emptySet(),
    )

    sealed interface Intent {
        data class SetSearch(
            val query: String,
        ) : Intent

        data class AckMacImport(
            val projectId: ProjectId,
        ) : Intent

        data class RepairMacPaths(
            val projectId: ProjectId,
        ) : Intent

        data class DismissMissingSample(
            val projectId: ProjectId,
            val missingPath: String,
        ) : Intent

        data class ApplyMatch(
            val projectId: ProjectId,
            val missingPath: String,
            val candidatePath: String,
        ) : Intent

        data class BulkAck(
            val projectIds: List<ProjectId>,
        ) : Intent

        data class BulkRepairMacPaths(
            val projectIds: List<ProjectId>,
        ) : Intent

        data class BulkApplyAutoMatch(
            val findings: List<MissingSampleFinding>,
        ) : Intent

        data class BulkDismiss(
            val findings: List<MissingSampleFinding>,
        ) : Intent
    }

    sealed interface Effect {
        data class Acknowledged(
            val key: String,
            val kind: String,
        ) : Effect

        data class MatchApplied(
            val key: String,
        ) : Effect

        data class Failed(
            val key: String,
            val reason: String,
        ) : Effect

        data class BulkAcked(
            val successKeys: List<String>,
            val failureKeys: List<String>,
        ) : Effect

        data class BulkRepaired(
            val successKeys: List<String>,
            val failureKeys: List<String>,
        ) : Effect

        data class BulkApplied(
            val successKeys: List<String>,
            val failureKeys: List<String>,
        ) : Effect

        data class BulkDismissed(
            val successKeys: List<String>,
            val failureKeys: List<String>,
        ) : Effect
    }

    private companion object {
        private val VARIATION_SUFFIX = Regex("_(?:[a-z]+_)?\\d+$", RegexOption.IGNORE_CASE)
    }
}
