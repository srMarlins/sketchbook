package com.sketchbook.desktop.repo

import com.sketchbook.core.ProjectId
import com.sketchbook.repo.MissingSampleFinding
import com.sketchbook.repo.RepairFindings
import com.sketchbook.repo.RepairRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class InMemoryRepairRepository : RepairRepository {
    private val findings = MutableStateFlow(
        RepairFindings(
            macImports = emptyList(),
            missingSamples = emptyList(),
            missingSamplesTotal = 0,
            missingSamplesTruncated = false,
        ),
    )

    override fun observeFindings(projectId: ProjectId?, limit: Int): Flow<RepairFindings> = findings

    override suspend fun acknowledgeMacImport(projectId: ProjectId): Result<Unit> {
        findings.update { f -> f.copy(macImports = f.macImports.filterNot { it.projectId == projectId }) }
        return Result.success(Unit)
    }

    override suspend fun dismissMissingSample(projectId: ProjectId, missingPath: String): Result<Unit> {
        findings.update { f ->
            val keep = f.missingSamples.filterNot { it.projectId == projectId && it.missingPath == missingPath }
            f.copy(missingSamples = keep, missingSamplesTotal = (f.missingSamplesTotal - 1).coerceAtLeast(0))
        }
        return Result.success(Unit)
    }
}
