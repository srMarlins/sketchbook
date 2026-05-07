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

    suspend fun setCloudBucket(bucket: String?): Result<Unit>

    suspend fun setSelfContained(
        uuid: ProjectUuid,
        value: Boolean,
    ): Result<Unit>

    suspend fun setCacheSettings(settings: BlobCacheSettings): Result<Unit>

    /**
     * Marks onboarding complete with the given [skipFlags]. Atomic: `firstRunCompletedAt` and
     * `onboardingSkipped` are emitted together so observers never see one without the other.
     * After this call, `LaunchGate.resolve()` returns `MainApp`.
     */
    suspend fun markFirstRunComplete(skipFlags: OnboardingSkipFlags): Result<Unit>

    /**
     * Sticky-dismisses the soft re-prompt banner for [kind]. After dismissal, the corresponding
     * banner does not reappear; the user can still re-discover the deferred setting via Settings.
     */
    suspend fun dismissOnboardingPrompt(kind: OnboardingPromptKind): Result<Unit>

    /**
     * Replaces the user-configured plugin install directories. Paths are normalized (absolute,
     * distinct, blanks dropped) on write. Empty list = use platform defaults (the JVM presence
     * probe falls back to `defaultInstalledDirs()`).
     */
    suspend fun setPluginFolders(folders: List<String>): Result<Unit>

    /**
     * Marks the cloud-storage migration ([com.sketchbook.repo.Settings.cloudMigrationComplete])
     * as done so subsequent launches skip the migrator. Called once by the migrator itself
     * after it has relocated all v=1 manifests to the v=2 layout and built the registry.
     *
     * Default impl is a no-op so legacy test fakes keep compiling; real impls
     * ([com.sketchbook.desktop.repo.PreferencesSettingsRepository]) persist the flag.
     */
    suspend fun markCloudMigrationComplete(): Result<Unit> = Result.success(Unit)

    /**
     * Sets the per-host User Library root path. Null = use OS default
     * (`~/Music/Ableton/User Library` on Mac, `Documents/Ableton/User Library` on Windows).
     */
    suspend fun setUserLibraryRoot(path: String?): Result<Unit> = Result.success(Unit)

    /** Toggles whether the User Library tree is actively synced on this machine. */
    suspend fun setUserLibrarySyncEnabled(enabled: Boolean): Result<Unit> = Result.success(Unit)

    /**
     * Dev-only escape hatch. Resets `firstRunCompletedAt` and `onboardingSkipped` to defaults so
     * a returning user re-triggers onboarding on next launch. Triggered by `--reset-first-run`
     * CLI flag in the desktop app. Does NOT touch library roots, plugin folders, or other
     * settings — only the onboarding gate state.
     */
    suspend fun resetFirstRun(): Result<Unit>
}

enum class OnboardingPromptKind { Samples }

data class Settings(
    val libraryRoots: List<LibraryRoot>,
    val selfContainedProjects: Set<ProjectUuid>,
    val cacheSettings: BlobCacheSettings = BlobCacheSettings.Default,
    /** GCS bucket name for uploads. Null when unconfigured. */
    val cloudBucket: String? = null,
    /** Wall-clock instant the user finished onboarding (or skipped to defaults). Null until then. */
    val firstRunCompletedAt: kotlin.time.Instant? = null,
    /** Sticky flags for soft re-prompt banners on Home after onboarding completes. */
    val onboardingSkipped: OnboardingSkipFlags = OnboardingSkipFlags(),
    /**
     * User-configurable plugin install directories. Empty = use platform defaults
     * (the JVM probe falls back to `defaultInstalledDirs()` when this list is empty).
     */
    val pluginFolders: List<String> = emptyList(),
    /**
     * True once the cloud-storage migration (commit 10) has run on this machine. Defaults
     * to false so a fresh install on a populated bucket detects v=1 paths and prompts the
     * user. The migrator flips it true on completion; cleared by `--reset-first-run`.
     */
    val cloudMigrationComplete: Boolean = false,
    /**
     * Per-host User Library root override. Null = use the OS-default path. Set when the
     * user picks a non-standard location during onboarding.
     */
    val userLibraryRoot: String? = null,
    /**
     * Master switch for User Library sync on this machine. Off by default; flipped on by
     * the post-migration setup step once the skip-set spike confirms.
     */
    val userLibrarySyncEnabled: Boolean = false,
)

/**
 * Sticky flags driving soft re-prompt banners on Home after onboarding completes — e.g. if the
 * user skipped picking a samples root, surface a dismissible nudge until they either configure
 * one or dismiss the prompt.
 */
data class OnboardingSkipFlags(
    val samplesSkipped: Boolean = false,
    val samplesPromptDismissed: Boolean = false,
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
        val Default: BlobCacheSettings =
            BlobCacheSettings(
                maxSizeBytes = 20L * 1024 * 1024 * 1024,
                lruEnabled = true,
            )
    }
}

sealed interface LibraryRoot {
    val path: String

    data class Projects(
        override val path: String,
    ) : LibraryRoot

    data class UserSamples(
        override val path: String,
    ) : LibraryRoot

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
