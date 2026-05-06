package com.sketchbook.desktop.repo

import com.sketchbook.core.ProjectUuid
import com.sketchbook.repo.BlobCacheSettings
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.repo.Settings
import com.sketchbook.repo.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory settings store. Persistence to disk lands when v1.1 wires `java.util.prefs.Preferences`
 * (or, for credentials, OS keychain). Today every restart resets to defaults — fine for the dev
 * loop; production will hold creds across launches once the keychain bridge ships.
 */
class InMemorySettingsRepository(
    initial: Settings = Settings(
        libraryRoots = emptyList(),
        cloudConfigured = false,
        selfContainedProjects = emptySet(),
    ),
) : SettingsRepository {
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
        state.update {
            it.copy(
                cloudCredentialJson = serviceAccountJson,
                cloudConfigured = serviceAccountJson != null,
            )
        }
        return Result.success(Unit)
    }

    override suspend fun setCloudBucket(bucket: String?): Result<Unit> {
        state.update { it.copy(cloudBucket = bucket?.takeIf { name -> name.isNotBlank() }) }
        return Result.success(Unit)
    }

    override suspend fun setSelfContained(uuid: ProjectUuid, value: Boolean): Result<Unit> {
        state.update { s ->
            val updated = if (value) s.selfContainedProjects + uuid else s.selfContainedProjects - uuid
            s.copy(selfContainedProjects = updated)
        }
        return Result.success(Unit)
    }

    override suspend fun setCacheSettings(settings: BlobCacheSettings): Result<Unit> {
        state.update { it.copy(cacheSettings = settings) }
        return Result.success(Unit)
    }
}
