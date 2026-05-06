package com.sketchbook.desktop.repo

import com.sketchbook.catalog.SyncStateStore
import com.sketchbook.cloud.DirectGcsBackend
import com.sketchbook.cloud.GcsAuth
import com.sketchbook.cloud.ServiceAccountKey
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.UserId
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.ProjectSyncState
import com.sketchbook.repo.Settings
import com.sketchbook.repo.SettingsRepository
import com.sketchbook.repo.SyncQueue
import com.sketchbook.repo.SyncQueueState
import com.sketchbook.cloud.CloudBackend
import com.sketchbook.core.SnapshotRev
import com.sketchbook.repo.BlobCacheSettings
import com.sketchbook.repo.JournalRepository
import com.sketchbook.sync.ForceSnapshotPipeline
import com.sketchbook.sync.JvmBlobCache
import com.sketchbook.sync.SnapshotPipeline
import com.sketchbook.syncio.ManifestMaterializer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Façade over the actual sync queue impl. The cloud backend is only constructible once
 * credentials + bucket land in [SettingsRepository]; until then, calls route through
 * [InMemorySyncQueue] so the UI's per-row pip and sidebar caption have something to bind to.
 *
 * Swap logic: a coroutine on the app scope watches `Settings.cloudReady`. When it transitions
 * true, build a real [GcsSyncQueue]; when it transitions false, fall back to in-memory. Active
 * uploads from the previous impl are not migrated — they finish on the previous instance and
 * the new instance picks up state from the catalog (`sync_state` rows).
 *
 * **Thread-model.** The delegate is a [MutableStateFlow]; reads `flatMapLatest` over it so a
 * swap mid-observation transparently re-subscribes. UI snapshot accessors call through the
 * concrete [InMemorySyncQueue]/[GcsSyncQueue]'s `snapshotFor(ProjectId)` helper.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwappableSyncQueue(
    private val settings: SettingsRepository,
    private val projects: ProjectRepository,
    private val syncStateStore: SyncStateStore,
    private val catalog: com.sketchbook.catalog.db.Catalog,
    private val blobCacheRoot: Path,
    private val scope: CoroutineScope,
    private val hostId: String,
    private val hostName: String,
    private val journal: JournalRepository? = null,
    private val httpClient: HttpClient = HttpClient(CIO),
    private val json: Json = Json { ignoreUnknownKeys = false },
) : SyncQueue, ForceSnapshotPipeline {

    private val fallback = InMemorySyncQueue(projects = projects, scope = scope)
    private val delegate = MutableStateFlow<SyncQueue>(fallback)

    /**
     * The materializer for the current cloud config, or null when cloud isn't ready. Rebuilt
     * alongside [delegate] in [buildGcsQueue]. Read by `SqlSnapshotRepository` via
     * `materialize` lambda — Timeline rewind goes through this.
     */
    @Volatile
    var currentMaterializer: ManifestMaterializer? = null
        private set

    /**
     * The active cloud backend, or null when cloud isn't configured. Exposed so the desktop
     * graph can spawn a [PullPoller] subscription per project — keeps PullPoller wiring out of
     * SwappableSyncQueue's direct DI graph (the poller depends on [SnapshotRepository], which
     * depends on this queue, which would otherwise be a cycle).
     */
    private val _currentCloud = MutableStateFlow<CloudBackend?>(null)
    val currentCloud: kotlinx.coroutines.flow.StateFlow<CloudBackend?> = _currentCloud

    init {
        scope.launch {
            settings.observe()
                .map { it.cloudReady to (it.cloudCredentialJson to it.cloudBucket) }
                .distinctUntilChanged()
                .collect { (ready, pair) ->
                    val (credJson, bucket) = pair
                    val previous = delegate.value
                    val next = if (ready && credJson != null && bucket != null) {
                        buildGcsQueue(credJson, bucket)
                    } else {
                        currentMaterializer = null
                        _currentCloud.value = null
                        fallback
                    }
                    // Stop the outgoing drain *before* publishing the new delegate so we don't
                    // race two GcsSyncQueue drain loops against the same DB. InMemorySyncQueue
                    // has no drain, so the cast guard handles that path.
                    if (previous !== next) {
                        (previous as? GcsSyncQueue)?.stop()
                    }
                    delegate.value = next
                    (next as? GcsSyncQueue)?.start()
                }
        }
    }

    private fun buildGcsQueue(credentialJson: String, bucket: String): SyncQueue {
        return runCatching {
            val key = json.decodeFromString(ServiceAccountKey.serializer(), credentialJson)
            val auth = GcsAuth(key = key, httpClient = httpClient)
            val backend = DirectGcsBackend(
                http = httpClient,
                auth = auth,
                bucket = bucket,
                userId = UserId.DEFAULT,
            )
            val pipeline = SnapshotPipeline(
                cloud = backend,
                ownerUserId = UserId.DEFAULT,
                hostId = hostId,
                hostName = hostName,
            )
            // Build the materializer alongside the queue so Timeline rewind works the moment
            // creds land. Cache settings come from settings.observe() — we read once here; a
            // cache-policy change forces the user to re-toggle creds today (rare, acceptable).
            val cacheSettings: () -> BlobCacheSettings = {
                kotlinx.coroutines.runBlocking { settings.observe().first() }.cacheSettings
            }
            val blobCache = JvmBlobCache(
                catalog = catalog,
                cacheRoot = blobCacheRoot,
                cloud = backend,
                cacheSettings = cacheSettings,
            )
            _currentCloud.value = backend
            currentMaterializer = ManifestMaterializer(
                cloud = backend,
                blobCache = blobCache,
                projectRoot = { uuid ->
                    val pid = syncStateStore.projectIdFor(uuid)
                        ?: throw IllegalStateException("no local project for uuid $uuid")
                    val row = kotlinx.coroutines.runBlocking {
                        projects.observeProject(pid).first()
                    } ?: throw IllegalStateException("project row $pid not found")
                    val parent = Paths.get(row.path.value).parent
                        ?: throw IllegalStateException("project path has no parent: ${row.path.value}")
                    parent
                },
            )
            GcsSyncQueue(
                cloud = backend,
                pipeline = pipeline,
                syncState = syncStateStore,
                projects = projects,
                scope = scope,
                journal = journal,
            )
        }.getOrElse {
            // Bad creds JSON or unsupported scheme — fall back to in-memory so the UI keeps
            // rendering. The error surfaces via Settings → Test connection.
            currentMaterializer = null
            _currentCloud.value = null
            fallback
        }
    }

    override fun observe(): Flow<SyncQueueState> =
        delegate.flatMapLatest { it.observe() }

    override fun observeProject(id: ProjectId): Flow<ProjectSyncState> =
        delegate.flatMapLatest { it.observeProject(id) }

    override suspend fun pushNow(uuid: ProjectUuid): Result<Unit> =
        delegate.value.pushNow(uuid)

    /**
     * Z3 quick-capture: route through the live cloud queue so the forced Named snapshot picks up
     * the same blob upload + journal wiring as auto-save. When cloud isn't ready we fall back to
     * a clear failure rather than silently dropping the snapshot — the desktop dialog can show
     * the user a "configure cloud first" message.
     */
    override suspend fun recordForcedNamed(uuid: ProjectUuid, label: String): Result<SnapshotRev> {
        return when (val current = delegate.value) {
            is GcsSyncQueue -> current.recordForcedNamed(uuid, label)
            else -> Result.failure(
                IllegalStateException("Cloud sync isn't configured — set credentials in Settings first."),
            )
        }
    }

    /** Per-row Sync-now invocation from the desktop detail panel. */
    suspend fun pushNowById(id: ProjectId): Result<Unit> = when (val current = delegate.value) {
        is GcsSyncQueue -> current.pushNowById(id)
        is InMemorySyncQueue -> {
            current.pushNowById(id)
            Result.success(Unit)
        }
        else -> Result.failure(IllegalStateException("unsupported sync queue impl"))
    }

    /** Snapshot lookup for non-suspending UI (SongStrip pip). */
    fun snapshotFor(id: ProjectId): ProjectSyncState = when (val current = delegate.value) {
        is GcsSyncQueue -> current.snapshotFor(id)
        is InMemorySyncQueue -> current.snapshotFor(id)
        else -> ProjectSyncState.Unknown
    }

    /** Inline conflict message for the detail-panel hint. Null when none. */
    fun conflictMessage(id: ProjectId): String? {
        val current = delegate.value as? GcsSyncQueue ?: return null
        val uuid = syncStateStore.identityFor(id)
        return current.conflictMessage(uuid)
    }

    /** Whether the live queue is the real GCS impl (rather than the in-memory fallback). */
    val cloudActive: Boolean get() = delegate.value is GcsSyncQueue

    private companion object {
        @Suppress("unused")
        fun newHostId(): String = UUID.randomUUID().toString()
    }
}
