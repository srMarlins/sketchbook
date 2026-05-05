package com.sketchbook.desktop.repo

import com.sketchbook.core.ProjectUuid
import com.sketchbook.repo.LockRepository
import com.sketchbook.repo.LockStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class InMemoryLockRepository : LockRepository {
    private val byUuid = mutableMapOf<ProjectUuid, MutableStateFlow<LockStatus>>()

    override fun observe(uuid: ProjectUuid): Flow<LockStatus> =
        byUuid.getOrPut(uuid) { MutableStateFlow(LockStatus.Free) }

    override suspend fun forceTake(uuid: ProjectUuid): Result<Unit> {
        byUuid.getOrPut(uuid) { MutableStateFlow(LockStatus.Free) }
            .update { LockStatus.Free }
        return Result.success(Unit)
    }
}
