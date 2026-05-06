package com.sketchbook.featuresettings

import com.sketchbook.core.ProjectUuid
import com.sketchbook.repo.BlobCacheSettings
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.repo.Settings
import com.sketchbook.repo.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings pane. Reads `SettingsRepository.observe()` and routes user intents
 * (add/remove root, set cloud credential, toggle self-contained) back through the repo.
 *
 * Side-effect intents (file picker / credential dialog) come from the desktop shell (PR-18) —
 * by the time those reach `dispatch`, the user has already committed a path string or JSON blob.
 */
class SettingsStateHolder(
    private val repository: SettingsRepository,
    private val scope: CoroutineScope,
) {

    private val _effects = MutableSharedFlow<Effect>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    val state: StateFlow<State> = repository.observe()
        .map { settings ->
            State(
                libraryRoots = settings.libraryRoots,
                cloudConfigured = settings.cloudConfigured,
                cloudBucket = settings.cloudBucket,
                cloudReady = settings.cloudReady,
                selfContainedProjects = settings.selfContainedProjects,
                cacheSettings = settings.cacheSettings,
                loading = false,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, State(loading = true))

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.AddRoot -> launchWithEffect(intent.root.path) {
                repository.upsertRoot(intent.root)
            }
            is Intent.RemoveRoot -> launchWithEffect(intent.root.path) {
                repository.removeRoot(intent.root)
            }
            is Intent.SetCloudCredential -> launchWithEffect("cloud") {
                repository.setCloudCredential(intent.serviceAccountJson)
            }
            is Intent.SetCloudBucket -> launchWithEffect("cloud-bucket") {
                repository.setCloudBucket(intent.bucket)
            }
            is Intent.ToggleSelfContained -> launchWithEffect(intent.uuid.value) {
                repository.setSelfContained(intent.uuid, intent.value)
            }
            is Intent.SetCacheSize -> launchWithEffect("cache-size") {
                repository.setCacheSettings(intent.settings)
            }
            is Intent.SetCacheLru -> launchWithEffect("cache-lru") {
                val current = state.value.cacheSettings
                repository.setCacheSettings(current.copy(lruEnabled = intent.enabled))
            }
        }
    }

    private inline fun launchWithEffect(key: String, crossinline block: suspend () -> Result<*>) {
        scope.launch {
            val r = block()
            if (r.isSuccess) _effects.tryEmit(Effect.Saved(key))
            else _effects.tryEmit(Effect.Failed(key, r.exceptionOrNull()?.message ?: "save failed"))
        }
    }

    data class State(
        val libraryRoots: List<LibraryRoot> = emptyList(),
        val cloudConfigured: Boolean = false,
        val cloudBucket: String? = null,
        val cloudReady: Boolean = false,
        val selfContainedProjects: Set<ProjectUuid> = emptySet(),
        val cacheSettings: BlobCacheSettings = BlobCacheSettings.Default,
        val loading: Boolean = false,
    )

    sealed interface Intent {
        data class AddRoot(val root: LibraryRoot) : Intent
        data class RemoveRoot(val root: LibraryRoot) : Intent
        data class SetCloudCredential(val serviceAccountJson: String?) : Intent
        data class SetCloudBucket(val bucket: String?) : Intent
        data class ToggleSelfContained(val uuid: ProjectUuid, val value: Boolean) : Intent
        data class SetCacheSize(val settings: BlobCacheSettings) : Intent
        data class SetCacheLru(val enabled: Boolean) : Intent
    }

    sealed interface Effect {
        data class Saved(val key: String) : Effect
        data class Failed(val key: String, val reason: String) : Effect
    }
}
