package com.sketchbook.desktop.repo

import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.Snapshot
import com.sketchbook.core.SnapshotRev
import com.sketchbook.repo.SnapshotRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

class InMemorySnapshotRepository : SnapshotRepository {
    private val byUuid = mutableMapOf<ProjectUuid, MutableStateFlow<List<Snapshot>>>()

    override fun observeHistory(uuid: ProjectUuid): Flow<List<Snapshot>> =
        byUuid.getOrPut(uuid) { MutableStateFlow(emptyList()) }

    override suspend fun recordSnapshot(snapshot: Snapshot, manifestPath: String, manifestHash: String): Result<Unit> {
        val flow = byUuid.getOrPut(snapshot.projectUuid) { MutableStateFlow(emptyList()) }
        if (flow.value.any { it.rev == snapshot.rev }) return Result.success(Unit)
        flow.value = (flow.value + snapshot).sortedByDescending { it.rev.value }
        return Result.success(Unit)
    }

    override suspend fun materializeAt(uuid: ProjectUuid, rev: SnapshotRev): Result<Unit> {
        // Stub: real impl invokes the sync engine's materializer (PR-22).
        return Result.success(Unit)
    }
}
