package com.sketchbook.desktop.repo

import com.sketchbook.auth.AuthSession
import com.sketchbook.auth.AuthState
import com.sketchbook.auth.firebase.FirebaseConfig
import com.sketchbook.catalog.SyncStateStore
import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.FirebaseBlobStore
import com.sketchbook.cloud.metadata.MetadataStore
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.UserId
import com.sketchbook.desktop.auth.FirebaseCloudCredentials
import com.sketchbook.repo.BlobCacheSettings
import com.sketchbook.repo.JournalRepository
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.ProjectSyncState
import com.sketchbook.repo.PushNowOutcome
import com.sketchbook.repo.SettingsRepository
import com.sketchbook.repo.SyncQueue
import com.sketchbook.repo.SyncQueueState
import com.sketchbook.sync.ForceSnapshotPipeline
import com.sketchbook.sync.JvmBlobCache
import com.sketchbook.sync.SnapshotPipeline
import com.sketchbook.syncio.ManifestMaterializer
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Façade over the actual sync queue impl. The cloud backend is only constructible once the
 * user has signed in via Google; until then, calls route through [InMemorySyncQueue] so the
 * UI's per-row pip and sidebar caption have something to bind to.
 *
 * Swap logic: a coroutine on the app scope observes [AuthSession.state]. On `SignedIn`, build
 * a real [GcsSyncQueue] wired with [FirebaseCloudCredentials] and the signed-in Firebase UID,
 * pointing at [FirebaseConfig.active]'s storage bucket. On `SignedOut`, fall back to
 * in-memory. Active uploads from the previous impl are not migrated — they finish on the
 * previous instance and the new instance picks up state from the catalog (`sync_state` rows).
 *
 * **Thread-model.** The delegate is a [MutableStateFlow]; reads `flatMapLatest` over it so a
 * swap mid-observation transparently re-subscribes. UI snapshot accessors call through the
 * concrete [InMemorySyncQueue]/[GcsSyncQueue]'s `snapshotFor(ProjectId)` helper.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwappableSyncQueue(
    private val authSession: AuthSession,
    private val settings: SettingsRepository,
    private val projects: ProjectRepository,
    private val syncStateStore: SyncStateStore,
    private val catalog: com.sketchbook.catalog.db.Catalog,
    private val blobCacheRoot: Path,
    private val scope: CoroutineScope,
    private val hostId: String,
    private val hostName: String,
    private val firebaseConfig: FirebaseConfig,
    private val journal: JournalRepository? = null,
    private val httpClient: HttpClient,
    private val metadataStore: MetadataStore,
) : SyncQueue,
    ForceSnapshotPipeline {
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
            // Only rebuild on identity transitions — emissions that don't change UID are no-ops.
            authSession.state
                .map { (it as? AuthState.SignedIn)?.userId }
                .distinctUntilChanged()
                .collect { userId ->
                    val previous = delegate.value
                    val next =
                        if (userId != null) {
                            buildGcsQueue(
                                authSession = authSession,
                                userId = userId,
                                bucket = firebaseConfig.storageBucket,
                                cacheSettings = settings.observe().first().cacheSettings,
                            )
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

    private fun buildGcsQueue(
        authSession: AuthSession,
        userId: UserId,
        bucket: String,
        cacheSettings: BlobCacheSettings,
    ): SyncQueue =
        try {
            val credentials = FirebaseCloudCredentials(authSession)
            val backend =
                FirebaseBlobStore(
                    http = httpClient,
                    credentials = credentials,
                    bucket = bucket,
                    userId = userId,
                )
            val pipeline =
                SnapshotPipeline(
                    cloud = backend,
                    metadataStore = metadataStore,
                    ownerUserId = userId,
                    hostId = hostId,
                    hostName = hostName,
                )
            // Materializer is built alongside the queue so Timeline rewind works the moment creds
            // land. Cache settings are snapshot-on-build (passed in by the caller, which collects
            // settings.observe() in its suspend collect): a cache-policy change forces the user to
            // re-toggle creds today, which is rare and acceptable. The previous wiring used a
            // `() -> BlobCacheSettings` lambda with runBlocking around `settings.observe().first()`
            // on every cache eviction check; that hot-path call is now eliminated. projectRoot
            // legitimately needs late binding (different uuid per call) and stays a suspend lambda.
            val blobCache =
                JvmBlobCache(
                    catalog = catalog,
                    cacheRoot = blobCacheRoot,
                    cloud = backend,
                    cacheSettings = cacheSettings,
                )
            _currentCloud.value = backend
            currentMaterializer =
                ManifestMaterializer(
                    cloud = backend,
                    blobCache = blobCache,
                    projectRoot = { uuid ->
                        val pid =
                            syncStateStore.projectIdFor(uuid)
                                ?: throw IllegalStateException("no local project for uuid $uuid")
                        val row =
                            projects.observeProject(pid).first()
                                ?: throw IllegalStateException("project row $pid not found")
                        val parent =
                            Paths.get(row.path.value).parent
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
        } catch (c: CancellationException) {
            throw c
        } catch (failure: Throwable) {
            // Bad creds or unsupported config — fall back to in-memory so the UI keeps
            // rendering. Log the cause so the failure isn't silent; the next access-token
            // request will also surface the underlying issue when a real sync attempt runs.
            System.err.println("[SwappableSyncQueue] buildGcsQueue failed: $failure")
            failure.printStackTrace(System.err)
            currentMaterializer = null
            _currentCloud.value = null
            fallback
        }

    override fun observe(): Flow<SyncQueueState> = delegate.flatMapLatest { it.observe() }

    override fun observeProject(id: ProjectId): Flow<ProjectSyncState> = delegate.flatMapLatest { it.observeProject(id) }

    override suspend fun pushNow(uuid: ProjectUuid): PushNowOutcome = delegate.value.pushNow(uuid)

    /**
     * Z3 quick-capture: route through the live cloud queue so the forced Named snapshot picks up
     * the same blob upload + journal wiring as auto-save. When cloud isn't ready we fall back to
     * a clear failure rather than silently dropping the snapshot — the desktop dialog can show
     * the user a "configure cloud first" message.
     */
    override suspend fun recordForcedNamed(
        uuid: ProjectUuid,
        label: String,
    ): Result<SnapshotRev> =
        when (val current = delegate.value) {
            is GcsSyncQueue -> {
                current.recordForcedNamed(uuid, label)
            }

            else -> {
                Result.failure(
                    IllegalStateException("Cloud sync isn't configured — sign in first."),
                )
            }
        }

    /** Per-row Sync-now invocation from the desktop detail panel. */
    suspend fun pushNowById(id: ProjectId): PushNowOutcome =
        when (val current = delegate.value) {
            is GcsSyncQueue -> {
                current.pushNowById(id)
            }

            is InMemorySyncQueue -> {
                current.pushNowById(id)
                PushNowOutcome.Pushed
            }

            else -> {
                throw com.sketchbook.core.SketchbookError
                    .IoFailure("unsupported sync queue impl")
            }
        }

    /** Snapshot lookup for non-suspending UI (SongStrip pip). */
    fun snapshotFor(id: ProjectId): ProjectSyncState =
        when (val current = delegate.value) {
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
