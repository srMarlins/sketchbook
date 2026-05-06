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
import com.sketchbook.sync.SnapshotPipeline
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val scope: CoroutineScope,
    private val hostId: String,
    private val hostName: String,
    private val httpClient: HttpClient = HttpClient(CIO),
    private val json: Json = Json { ignoreUnknownKeys = false },
) : SyncQueue {

    private val fallback = InMemorySyncQueue(projects = projects, scope = scope)
    private val delegate = MutableStateFlow<SyncQueue>(fallback)

    init {
        scope.launch {
            settings.observe()
                .map { it.cloudReady to (it.cloudCredentialJson to it.cloudBucket) }
                .distinctUntilChanged()
                .collect { (ready, pair) ->
                    val (credJson, bucket) = pair
                    delegate.value = if (ready && credJson != null && bucket != null) {
                        buildGcsQueue(credJson, bucket)
                    } else {
                        fallback
                    }
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
            GcsSyncQueue(
                cloud = backend,
                pipeline = pipeline,
                syncState = syncStateStore,
                projects = projects,
                scope = scope,
            )
        }.getOrElse {
            // Bad creds JSON or unsupported scheme — fall back to in-memory so the UI keeps
            // rendering. The error surfaces via Settings → Test connection.
            fallback
        }
    }

    override fun observe(): Flow<SyncQueueState> =
        delegate.flatMapLatest { it.observe() }

    override fun observeProject(id: ProjectId): Flow<ProjectSyncState> =
        delegate.flatMapLatest { it.observeProject(id) }

    override suspend fun pushNow(uuid: ProjectUuid): Result<Unit> =
        delegate.value.pushNow(uuid)

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

    /** Whether the live queue is the real GCS impl (rather than the in-memory fallback). */
    val cloudActive: Boolean get() = delegate.value is GcsSyncQueue

    private companion object {
        @Suppress("unused")
        fun newHostId(): String = UUID.randomUUID().toString()
    }
}
