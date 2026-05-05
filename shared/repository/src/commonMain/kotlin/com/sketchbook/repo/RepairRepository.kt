package com.sketchbook.repo

import com.sketchbook.core.ProjectId
import kotlinx.coroutines.flow.Flow

/**
 * Repair findings surface — backs the "needs attention" feature. Mirrors the v0.1 Python
 * `/api/repair/findings` payload: Mac-imported projects (Mac-style paths inside `.als` and/or
 * missing `Ableton Project Info/`) and missing-sample references with optional auto-match
 * candidates.
 *
 * Truncation: missing-sample lists can hit 100k+ entries on large libraries; impls cap to the
 * caller-supplied limit and report `total` separately so the UI can show "N more not shown".
 */
interface RepairRepository {

    fun observeFindings(projectId: ProjectId? = null, limit: Int = 1000): Flow<RepairFindings>

    /** Mark a Mac-import finding as repaired (drops it from subsequent flow emissions). */
    suspend fun acknowledgeMacImport(projectId: ProjectId): Result<Unit>

    /** Drop a missing-sample finding from the queue without changing on-disk state. */
    suspend fun dismissMissingSample(projectId: ProjectId, missingPath: String): Result<Unit>
}

data class RepairFindings(
    val macImports: List<MacImportFinding>,
    val missingSamples: List<MissingSampleFinding>,
    val missingSamplesTotal: Int,
    val missingSamplesTruncated: Boolean,
)

data class MacImportFinding(
    val projectId: ProjectId,
    val path: String,
    val name: String,
    val parentDir: String,
    val macPathsCount: Int,
    val projectInfoMissing: Boolean,
)

data class MissingSampleFinding(
    val projectId: ProjectId,
    val projectPath: String,
    val projectName: String,
    val missingPath: String,
    val autoMatch: SampleCandidate? = null,
    val candidates: List<SampleCandidate> = emptyList(),
)

data class SampleCandidate(
    val path: String,
    val filename: String,
    val sizeBytes: Long,
)
