package com.sketchbook.desktop.repo

import com.sketchbook.core.ProjectUuid
import com.sketchbook.repo.BlobCacheSettings
import com.sketchbook.repo.ExternalKind
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.repo.OnboardingPromptKind
import com.sketchbook.repo.OnboardingSkipFlags
import com.sketchbook.repo.Settings
import com.sketchbook.repo.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Paths
import java.util.prefs.Preferences
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * `java.util.prefs.Preferences`-backed settings store. Keys are versioned (`*_v1`) so the v1.1
 * keychain rotation can migrate without colliding with already-persisted values.
 *
 * **Why prefs**: zero-dep, OS-agnostic, atomic per-key on POSIX + Windows, and perfectly adequate
 * for the small string-y values we store (paths, JSON blobs). When the keychain rotation lands the
 * cloud credential moves out; everything else stays here.
 *
 * **Concurrency**: each setter mutates the prefs node, calls `flush()`, then publishes through the
 * internal `MutableStateFlow`. `upsertRoot` / `removeRoot` are read-modify-write on the roots
 * array, so they take a [Mutex] to serialize concurrent callers.
 *
 * **Serialization**: roots round-trip through [StoredRoot] so `LibraryRoot` (in `:shared:repository`)
 * doesn't need a kotlinx-serialization dep. JSON config: `ignoreUnknownKeys = true` for forward
 * compat with later schema fields, `encodeDefaults = false` to keep the on-disk blobs compact.
 */
class PreferencesSettingsRepository(
    private val node: Preferences,
    private val ioDispatcher: CoroutineDispatcher,
) : SettingsRepository {

    private val mutex = Mutex()
    private val state = MutableStateFlow(read())

    init {
        // One-time legacy cleanup: pre-OAuth builds stored a service-account JSON blob in the
        // prefs node. Drop it on first construction so old credentials don't sit at rest
        // indefinitely once the user upgrades.
        runCatching {
            node.remove(KEY_LEGACY_CLOUD_CREDENTIAL)
            node.flush()
        }
    }

    override fun observe(): Flow<Settings> = state

    override suspend fun upsertRoot(root: LibraryRoot): Result<Unit> = withContext(ioDispatcher) {
        mutex.withLock {
            val current = readRoots().toMutableList()
            current.removeAll { it.path == root.path }
            current += root
            writeRoots(current)
            state.value = state.value.copy(libraryRoots = current.toList())
        }
        Result.success(Unit)
    }

    override suspend fun removeRoot(root: LibraryRoot): Result<Unit> = withContext(ioDispatcher) {
        mutex.withLock {
            val current = readRoots().toMutableList()
            // Match by path — UI passes the full data class, but path is the stable identity.
            val changed = current.removeAll { it.path == root.path }
            if (changed) {
                writeRoots(current)
                state.value = state.value.copy(libraryRoots = current.toList())
            }
        }
        Result.success(Unit)
    }

    override suspend fun setCloudBucket(bucket: String?): Result<Unit> = withContext(ioDispatcher) {
        val normalized = bucket?.takeIf { it.isNotBlank() }
        if (normalized == null) {
            node.remove(KEY_CLOUD_BUCKET)
        } else {
            node.put(KEY_CLOUD_BUCKET, normalized)
        }
        node.flush()
        state.value = state.value.copy(cloudBucket = normalized)
        Result.success(Unit)
    }

    override suspend fun setSelfContained(uuid: ProjectUuid, value: Boolean): Result<Unit> = withContext(ioDispatcher) {
        mutex.withLock {
            val current = readSelfContained().toMutableSet()
            val changed = if (value) current.add(uuid) else current.remove(uuid)
            if (changed) {
                writeSelfContained(current)
                state.value = state.value.copy(selfContainedProjects = current.toSet())
            }
        }
        Result.success(Unit)
    }

    override suspend fun setCacheSettings(settings: BlobCacheSettings): Result<Unit> = withContext(ioDispatcher) {
        node.putLong(KEY_CACHE_MAX_BYTES, settings.maxSizeBytes)
        node.putBoolean(KEY_CACHE_LRU, settings.lruEnabled)
        node.flush()
        state.value = state.value.copy(cacheSettings = settings)
        Result.success(Unit)
    }

    override suspend fun markFirstRunComplete(flags: OnboardingSkipFlags): Result<Unit> = withContext(ioDispatcher) {
        mutex.withLock {
            val now = Clock.System.now()
            // Atomic: write all keys, flush once, then publish a single Settings emission so
            // observers see timestamp + flags together (never one without the other).
            node.put(KEY_FIRST_RUN_COMPLETED_AT, now.toString())
            node.putBoolean(KEY_ONBOARDING_SAMPLES_SKIPPED, flags.samplesSkipped)
            node.putBoolean(KEY_ONBOARDING_SAMPLES_PROMPT_DISMISSED, flags.samplesPromptDismissed)
            node.flush()
            state.value = state.value.copy(
                firstRunCompletedAt = now,
                onboardingSkipped = flags,
            )
        }
        Result.success(Unit)
    }

    override suspend fun dismissOnboardingPrompt(kind: OnboardingPromptKind): Result<Unit> = withContext(ioDispatcher) {
        mutex.withLock {
            val current = state.value.onboardingSkipped
            val updated = when (kind) {
                OnboardingPromptKind.Samples -> current.copy(samplesPromptDismissed = true)
            }
            node.putBoolean(KEY_ONBOARDING_SAMPLES_PROMPT_DISMISSED, updated.samplesPromptDismissed)
            node.flush()
            state.value = state.value.copy(onboardingSkipped = updated)
        }
        Result.success(Unit)
    }

    override suspend fun resetFirstRun(): Result<Unit> = withContext(ioDispatcher) {
        mutex.withLock {
            // Atomic: clear all three onboarding-gate keys, flush once, then publish a single
            // Settings emission so observers see the reset state in one shot. Roots / plugin
            // folders / cloud config are intentionally untouched — this is only for re-triggering
            // the onboarding flow in dev.
            node.remove(KEY_FIRST_RUN_COMPLETED_AT)
            node.remove(KEY_ONBOARDING_SAMPLES_SKIPPED)
            node.remove(KEY_ONBOARDING_SAMPLES_PROMPT_DISMISSED)
            node.flush()
            state.value = state.value.copy(
                firstRunCompletedAt = null,
                onboardingSkipped = OnboardingSkipFlags(),
            )
        }
        Result.success(Unit)
    }

    override suspend fun setPluginFolders(folders: List<String>): Result<Unit> = withContext(ioDispatcher) {
        val normalized = folders
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { Paths.get(it).toAbsolutePath().normalize().toString() }
            .distinct()
            .toList()
        writePluginFolders(normalized)
        state.value = state.value.copy(pluginFolders = normalized)
        Result.success(Unit)
    }

    // ---- read helpers --------------------------------------------------------------------------

    private fun read(): Settings {
        val roots = readRoots()
        val bucket = node.get(KEY_CLOUD_BUCKET, null)?.takeIf { it.isNotBlank() }
        val selfContained = readSelfContained()
        val cache = readCacheSettings()
        val firstRunCompletedAt = node.get(KEY_FIRST_RUN_COMPLETED_AT, null)
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val onboardingSkipped = OnboardingSkipFlags(
            samplesSkipped = node.getBoolean(KEY_ONBOARDING_SAMPLES_SKIPPED, false),
            samplesPromptDismissed = node.getBoolean(KEY_ONBOARDING_SAMPLES_PROMPT_DISMISSED, false),
        )
        val pluginFolders = readPluginFolders()
        return Settings(
            libraryRoots = roots,
            selfContainedProjects = selfContained,
            cacheSettings = cache,
            cloudBucket = bucket,
            firstRunCompletedAt = firstRunCompletedAt,
            onboardingSkipped = onboardingSkipped,
            pluginFolders = pluginFolders,
        )
    }

    private fun readRoots(): List<LibraryRoot> {
        val raw = node.get(KEY_ROOTS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(StoredRoot.serializer()), raw)
                .map { it.toDomain() }
        }.getOrDefault(emptyList())
    }

    private fun writeRoots(roots: List<LibraryRoot>) {
        val payload = json.encodeToString(
            ListSerializer(StoredRoot.serializer()),
            roots.map { StoredRoot.fromDomain(it) },
        )
        node.put(KEY_ROOTS, payload)
        node.flush()
    }

    private fun readSelfContained(): Set<ProjectUuid> {
        val raw = node.get(KEY_SELF_CONTAINED, null) ?: return emptySet()
        return runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), raw)
                .map { ProjectUuid(it) }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun writeSelfContained(values: Set<ProjectUuid>) {
        val payload = json.encodeToString(
            ListSerializer(String.serializer()),
            values.map { it.value },
        )
        node.put(KEY_SELF_CONTAINED, payload)
        node.flush()
    }

    private fun readPluginFolders(): List<String> {
        val raw = node.get(KEY_PLUGIN_FOLDERS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun writePluginFolders(folders: List<String>) {
        val payload = json.encodeToString(
            ListSerializer(String.serializer()),
            folders,
        )
        node.put(KEY_PLUGIN_FOLDERS, payload)
        node.flush()
    }

    private fun readCacheSettings(): BlobCacheSettings {
        val max = node.getLong(KEY_CACHE_MAX_BYTES, BlobCacheSettings.Default.maxSizeBytes)
        val lru = node.getBoolean(KEY_CACHE_LRU, BlobCacheSettings.Default.lruEnabled)
        return BlobCacheSettings(maxSizeBytes = max, lruEnabled = lru)
    }

    /** Serializable mirror of [LibraryRoot] so we don't need to add `@Serializable` upstream. */
    @Serializable
    private data class StoredRoot(
        val kind: String,
        val path: String,
        val alias: String? = null,
        @SerialName("external_kind") val externalKind: String? = null,
    ) {
        fun toDomain(): LibraryRoot = when (kind) {
            "projects" -> LibraryRoot.Projects(path)

            "user_samples" -> LibraryRoot.UserSamples(path)

            "external" -> LibraryRoot.External(
                path = path,
                alias = alias.orEmpty(),
                kind = externalKind?.let { runCatching { ExternalKind.valueOf(it) }.getOrNull() }
                    ?: ExternalKind.Other,
            )

            else -> LibraryRoot.Projects(path)
        }

        companion object {
            fun fromDomain(root: LibraryRoot): StoredRoot = when (root) {
                is LibraryRoot.Projects -> StoredRoot(kind = "projects", path = root.path)

                is LibraryRoot.UserSamples -> StoredRoot(kind = "user_samples", path = root.path)

                is LibraryRoot.External -> StoredRoot(
                    kind = "external",
                    path = root.path,
                    alias = root.alias,
                    externalKind = root.kind.name,
                )
            }
        }
    }

    private companion object {
        const val KEY_ROOTS = "library_roots_v1"
        const val KEY_CLOUD_BUCKET = "cloud_bucket"
        const val KEY_SELF_CONTAINED = "self_contained_uuids_v1"
        const val KEY_CACHE_MAX_BYTES = "cache_max_bytes"
        const val KEY_CACHE_LRU = "cache_lru_enabled"
        const val KEY_FIRST_RUN_COMPLETED_AT = "first_run_completed_at_v1"
        const val KEY_ONBOARDING_SAMPLES_SKIPPED = "onboarding_samples_skipped_v1"
        const val KEY_ONBOARDING_SAMPLES_PROMPT_DISMISSED = "onboarding_samples_prompt_dismissed_v1"
        const val KEY_PLUGIN_FOLDERS = "plugin_folders_v1"

        /**
         * Pre-OAuth builds wrote a service-account JSON blob under this key. Cleared at startup so
         * upgrading users don't leave credentials on disk; safe to drop for good once the migration
         * window has passed.
         */
        const val KEY_LEGACY_CLOUD_CREDENTIAL = "cloud_credential_json"

        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    }
}
