package com.sketchbook.desktop.repo

import com.sketchbook.core.ProjectUuid
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.repo.Settings
import com.sketchbook.repo.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class InMemorySettingsRepository(initial: Settings = Settings(emptyList(), false, emptySet())) : SettingsRepository {
    private val state = MutableStateFlow(initial)

    override fun observe(): Flow<Settings> = state

    override suspend fun upsertRoot(root: LibraryRoot): Result<Unit> {
        state.update { s ->
            val deduped = s.libraryRoots.filterNot { it.path == root.path }
            s.copy(libraryRoots = deduped + root)
        }
        return Result.success(Unit)
    }

    override suspend fun removeRoot(root: LibraryRoot): Result<Unit> {
        state.update { it.copy(libraryRoots = it.libraryRoots - root) }
        return Result.success(Unit)
    }

    override suspend fun setCloudCredential(serviceAccountJson: String?): Result<Unit> {
        state.update { it.copy(cloudConfigured = serviceAccountJson != null) }
        return Result.success(Unit)
    }

    override suspend fun setSelfContained(uuid: ProjectUuid, value: Boolean): Result<Unit> {
        state.update { s ->
            val updated = if (value) s.selfContainedProjects + uuid else s.selfContainedProjects - uuid
            s.copy(selfContainedProjects = updated)
        }
        return Result.success(Unit)
    }
}
