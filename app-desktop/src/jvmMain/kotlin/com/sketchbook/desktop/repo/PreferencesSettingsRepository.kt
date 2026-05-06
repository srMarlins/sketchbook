package com.sketchbook.desktop.repo

import com.sketchbook.core.ProjectUuid
import com.sketchbook.repo.BlobCacheSettings
import com.sketchbook.repo.ExternalKind
import com.sketchbook.repo.LibraryRoot
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
import java.util.prefs.Preferences

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

    override suspend fun setCloudCredential(serviceAccountJson: String?): Result<Unit> = withContext(ioDispatcher) {
        if (serviceAccountJson == null) node.remove(KEY_CLOUD_CREDENTIAL)
        else node.put(KEY_CLOUD_CREDENTIAL, serviceAccountJson)
        node.flush()
        state.value = state.value.copy(
            cloudCredentialJson = serviceAccountJson,
            cloudConfigured = serviceAccountJson != null,
        )
        Result.success(Unit)
    }

    override suspend fun setCloudBucket(bucket: String?): Result<Unit> = withContext(ioDispatcher) {
        val normalized = bucket?.takeIf { it.isNotBlank() }
        if (normalized == null) node.remove(KEY_CLOUD_BUCKET)
        else node.put(KEY_CLOUD_BUCKET, normalized)
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

    // ---- read helpers --------------------------------------------------------------------------

    private fun read(): Settings {
        val roots = readRoots()
        val cred = node.get(KEY_CLOUD_CREDENTIAL, null)
        val bucket = node.get(KEY_CLOUD_BUCKET, null)?.takeIf { it.isNotBlank() }
        val selfContained = readSelfContained()
        val cache = readCacheSettings()
        return Settings(
            libraryRoots = roots,
            cloudConfigured = cred != null,
            selfContainedProjects = selfContained,
            cacheSettings = cache,
            cloudCredentialJson = cred,
            cloudBucket = bucket,
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
        const val KEY_CLOUD_CREDENTIAL = "cloud_credential_json"
        const val KEY_CLOUD_BUCKET = "cloud_bucket"
        const val KEY_SELF_CONTAINED = "self_contained_uuids_v1"
        const val KEY_CACHE_MAX_BYTES = "cache_max_bytes"
        const val KEY_CACHE_LRU = "cache_lru_enabled"

        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    }
}
