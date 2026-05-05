package com.sketchbook.repo

import com.sketchbook.core.ProjectUuid
import kotlinx.coroutines.flow.Flow

/**
 * App-level settings: typed library roots, cloud credentials, per-project self-contained toggle.
 * Persistence is the impl's job — v1 desktop uses `java.util.prefs.Preferences` (rotated to OS
 * keychain in v1.1).
 *
 * Library roots are typed: `Projects` / `UserSamples` are sync targets; `External` is alias-only
 * (Splice / Factory / personal sample drives) — never synced, but referenced via
 * `<alias>://<rel>` paths in manifests so a project that imports a Splice loop on Mac plays back
 * on Windows after the alias map is configured per-host.
 */
interface SettingsRepository {

    fun observe(): Flow<Settings>

    suspend fun upsertRoot(root: LibraryRoot): Result<Unit>
    suspend fun removeRoot(root: LibraryRoot): Result<Unit>

    suspend fun setCloudCredential(serviceAccountJson: String?): Result<Unit>

    suspend fun setSelfContained(uuid: ProjectUuid, value: Boolean): Result<Unit>

    suspend fun setCacheSettings(settings: BlobCacheSettings): Result<Unit>
}

data class Settings(
    val libraryRoots: List<LibraryRoot>,
    val cloudConfigured: Boolean,
    val selfContainedProjects: Set<ProjectUuid>,
    val cacheSettings: BlobCacheSettings = BlobCacheSettings.Default,
)

/**
 * Local blob cache policy. The sync engine consults this on every download to decide whether to
 * GC older entries; the UI surfaces it as a settings section so power users can size their disk
 * footprint.
 */
data class BlobCacheSettings(
    /** Hard upper bound for cache size in bytes. Past this, LRU eviction kicks in. */
    val maxSizeBytes: Long,
    /** When false, never evict — useful for offline-first workflows. */
    val lruEnabled: Boolean,
) {
    companion object {
        /** 20 GiB default; large enough for most libraries, small enough not to surprise. */
        val Default: BlobCacheSettings = BlobCacheSettings(
            maxSizeBytes = 20L * 1024 * 1024 * 1024,
            lruEnabled = true,
        )
    }
}

sealed interface LibraryRoot {
    val path: String

    data class Projects(override val path: String) : LibraryRoot
    data class UserSamples(override val path: String) : LibraryRoot

    /**
     * External root (Splice, factory libs, personal sample drives). [alias] is the same string
     * on every machine; [path] differs per host. Manifests reference `<alias>://<rel_path>`.
     */
    data class External(
        override val path: String,
        val alias: String,
        val kind: ExternalKind,
    ) : LibraryRoot
}

enum class ExternalKind { Splice, Factory, Other }
