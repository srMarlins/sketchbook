package com.sketchbook.desktop

import com.sketchbook.actions.ProposalActionExecutor
import com.sketchbook.auth.AuthSession
import com.sketchbook.auth.GoogleAuthSession
import com.sketchbook.auth.KeyringTokenStore
import com.sketchbook.auth.OAuthClient
import com.sketchbook.auth.TokenStore
import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.CatalogFts
import com.sketchbook.catalog.CatalogHandle
import com.sketchbook.catalog.JvmSampleScanner
import com.sketchbook.catalog.JvmScanner
import com.sketchbook.catalog.SyncStateStore
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.AppScope
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SnapshotRev
import com.sketchbook.desktop.auth.DesktopAuthSession
import com.sketchbook.desktop.auth.PrefsIdentityStore
import com.sketchbook.desktop.bootstrap.HostId
import com.sketchbook.desktop.repo.LeasedLockRepository
import com.sketchbook.desktop.repo.PreferencesSettingsRepository
import com.sketchbook.desktop.repo.SwappableSyncQueue
import com.sketchbook.repo.AlsPatchService
import com.sketchbook.repo.JournalRepository
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.repo.LockRepository
import com.sketchbook.repo.ProjectFtsSearcher
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.ProposalsRepository
import com.sketchbook.repo.RepairRepository
import com.sketchbook.repo.SettingsRepository
import com.sketchbook.repo.SnapshotRepository
import com.sketchbook.repo.SyncQueue
import com.sketchbook.repo.impl.SqlSnapshotRepository
import com.sketchbook.sync.PullPoller
import com.sketchbook.syncio.AlsPatcher
import com.sketchbook.syncio.Watcher
import com.sketchbook.syncio.WatcherToSyncState
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraph
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.prefs.Preferences

/**
 * Composition root for the Compose Desktop shell. Metro generates the synthetic graph impl at
 * compile time — every accessor below is materialized via FIR; no reflection at runtime.
 *
 * **Scoping.** Bindings the app needs to share state across screens — repositories backed by
 * `MutableStateFlow`, the application-lifetime [CoroutineScope] — are `@SingleIn(AppScope::class)`.
 * Without that, each accessor would hand back a fresh instance, so two state-holders observing
 * the "same" repository would actually see independent flows. That's the load-bearing reason
 * everything below is scoped — it isn't ceremony.
 *
 * If a future binding is genuinely stateless (a pure mapper, a per-call factory) it should be
 * left unscoped so callers don't pin live references unnecessarily.
 *
 * **Catalog (SQLite).** Project / Snapshot / Proposals / Repair / Journal are SQLDelight-backed;
 * the DB lives at `~/.local/share/sketchbook/catalog.db` (Linux/Mac) or
 * `%APPDATA%\Sketchbook\catalog.db` (Windows). One handle per app instance.
 */
@DependencyGraph(scope = AppScope::class)
interface DesktopAppGraph : ViewModelGraph {
    val appScope: CoroutineScope
    val authSession: AuthSession
    val catalogHandle: CatalogHandle
    val catalog: Catalog
    val catalogFts: CatalogFts
    val syncStateStore: SyncStateStore
    val scanner: JvmScanner
    val sampleScanner: JvmSampleScanner
    val projectRepository: ProjectRepository
    val journalRepository: JournalRepository
    val snapshotRepository: SnapshotRepository
    val proposalsRepository: ProposalsRepository
    val proposalActionExecutor: ProposalActionExecutor
    val repairRepository: RepairRepository
    val settingsRepository: SettingsRepository
    val lockRepository: LockRepository
    val syncQueue: SyncQueue
    val libraryScanCoordinator: LibraryScanCoordinator
    val userGraphHolder: UserGraphHolder
    val launchGate: LaunchGate

    // `metroViewModelFactory` is inherited from [ViewModelGraph] — the contributed
    // `@ContributesIntoMap(AppScope::class) @ViewModelKey @Inject` ViewModel map is plumbed
    // through `InjectedViewModelFactory` (below), then exposed via the inherited accessor and
    // installed into Compose with `LocalMetroViewModelFactory` in `RootContent`.

    // ---- App lifetime: shared mutable state ---------------------------------------------------

    @Provides
    @SingleIn(AppScope::class)
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Single `CoroutineDispatcher` binding used by repositories that need to leave Compose's
     * stateIn dispatcher for blocking JDBC. `JvmScanner`/`JvmSampleScanner` keep their own
     * defaults — they need both an IO and a Default dispatcher and would require a qualifier
     * if injected through the graph.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * Wall clock. Injected via Metro so tests can swap it for a deterministic
     * [kotlin.time.Clock] without touching `Clock.System` at construction sites. Mirrors the
     * dispatcher convention.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideClock(): kotlin.time.Clock = kotlin.time.Clock.System

    /**
     * Owner [com.sketchbook.core.UserId] used as the empty-doc default when a fresh registry
     * is minted. Real per-user identity flows in via [com.sketchbook.repo.TreeRegistry.register]'s
     * `ownerUserId` argument; this binding will move into a `UserScope` subgraph keyed on the
     * authed user once #130 lands. Until then, this stays as the v1 single-user constant.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideOwnerUserId(): com.sketchbook.core.UserId = com.sketchbook.core.UserId.DEFAULT

    /**
     * Stable host identifier surfaced to the cloud (registry `created_by_host`, machines
     * roster, plugin manifest slice). Wrapped in [com.sketchbook.desktop.bootstrap.HostId]
     * so Metro can bind it as a distinct type rather than colliding with other String
     * bindings.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideHostId(): HostId = HostId(hostIdentity().id)

    @Provides
    @SingleIn(AppScope::class)
    fun provideCatalogHandle(): CatalogHandle = CatalogDb.openOnDisk(catalogDbPath())

    @Provides
    @SingleIn(AppScope::class)
    fun provideCatalog(handle: CatalogHandle): Catalog = handle.catalog

    @Provides
    @SingleIn(AppScope::class)
    fun provideCatalogFts(handle: CatalogHandle): CatalogFts = CatalogFts(handle.driver)

    /**
     * Adapt the JVM-only [CatalogFts] to the common-side [ProjectFtsSearcher] so
     * [com.sketchbook.repo.impl.SqlProjectRepository] can stay in `commonMain`.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideProjectFtsSearcher(fts: CatalogFts): ProjectFtsSearcher = ProjectFtsSearcher { query -> fts.search(query) }

    @Provides
    @SingleIn(AppScope::class)
    fun provideJvmScanner(
        catalog: Catalog,
        fts: CatalogFts,
    ): JvmScanner = JvmScanner(catalog = catalog, fts = fts)

    @Provides
    @SingleIn(AppScope::class)
    fun provideJvmSampleScanner(catalog: Catalog): JvmSampleScanner = JvmSampleScanner(catalog = catalog)

    // SqlSnapshotRepository stays manually wired — the `materialize` lambda is coupled to
    // SwappableSyncQueue's current backend. Future `CloudScope` PR cleans this up.
    @Provides
    @SingleIn(AppScope::class)
    fun provideSnapshotRepository(
        catalog: Catalog,
        syncQueue: SyncQueue,
        journal: JournalRepository,
    ): SnapshotRepository =
        SqlSnapshotRepository(
            catalog = catalog,
            ioDispatcher = Dispatchers.IO,
            journal = journal,
            materialize = { uuid, rev ->
                // Delegates to the SwappableSyncQueue's currently-active materializer (built when
                // cloud creds land). Returns a friendly failure when cloud is unconfigured so the
                // Timeline rewind UI doesn't crash on first launch.
                val swap = syncQueue as? com.sketchbook.desktop.repo.SwappableSyncQueue
                val mat =
                    swap?.currentMaterializer
                        ?: return@SqlSnapshotRepository Result.failure(
                            IllegalStateException("Configure cloud credentials in Settings before rewinding."),
                        )
                mat.materialize(uuid, rev)
            },
        )

    @Provides
    @SingleIn(AppScope::class)
    fun provideAlsPatchService(): AlsPatchService = AlsPatcher()

    @Provides
    @SingleIn(AppScope::class)
    fun provideSettingsRepository(): SettingsRepository =
        PreferencesSettingsRepository(
            node = Preferences.userNodeForPackage(SettingsRepository::class.java),
            ioDispatcher = Dispatchers.IO,
        )

    @Provides
    @SingleIn(AppScope::class)
    fun provideLockRepository(
        store: SyncStateStore,
        scope: CoroutineScope,
        syncQueue: SyncQueue,
        journal: JournalRepository,
    ): LockRepository =
        LeasedLockRepository(
            cloud = {
                (syncQueue as? com.sketchbook.desktop.repo.SwappableSyncQueue)?.currentCloud?.value
            },
            syncStateStore = store,
            hostId = hostIdentity().id,
            hostName = hostIdentity().name,
            scope = scope,
            journal = journal,
        )

    @Provides
    @SingleIn(AppScope::class)
    fun provideSyncQueue(
        authSession: AuthSession,
        settings: SettingsRepository,
        projects: ProjectRepository,
        store: SyncStateStore,
        catalog: Catalog,
        journal: JournalRepository,
        httpClient: HttpClient,
        scope: CoroutineScope,
    ): SyncQueue =
        SwappableSyncQueue(
            authSession = authSession,
            settings = settings,
            projects = projects,
            syncStateStore = store,
            catalog = catalog,
            blobCacheRoot = catalogDbPath().parent.resolve("blob-cache"),
            scope = scope,
            hostId = hostIdentity().id,
            hostName = hostIdentity().name,
            journal = journal,
            httpClient = httpClient,
        )

    /**
     * Application-lifetime [HttpClient]. Shared by every service that needs to make network
     * calls (OAuth, GCS, token revoke). One CIO connection pool app-wide is the right default —
     * each independent client would carry its own selector thread + connection pool, and HTTP/1.1
     * keep-alive across calls dies on a per-instance boundary.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideHttpClient(): HttpClient = HttpClient(CIO)

    @Provides
    @SingleIn(AppScope::class)
    fun provideTokenStore(): TokenStore =
        KeyringTokenStore(
            serviceName = "com.sketchbook.refresh",
            accountName = "default",
        )

    @Provides
    @SingleIn(AppScope::class)
    fun provideIdentityStore(): PrefsIdentityStore =
        PrefsIdentityStore(
            node = Preferences.userNodeForPackage(PrefsIdentityStore::class.java),
        )

    @Provides
    @SingleIn(AppScope::class)
    fun provideOAuthClient(httpClient: HttpClient): OAuthClient =
        OAuthClient(
            httpClient = httpClient,
            clientId = OAUTH_CLIENT_ID,
            scopes =
                listOf(
                    "openid",
                    "email",
                    "https://www.googleapis.com/auth/devstorage.read_write",
                ),
        )

    @Provides
    @SingleIn(AppScope::class)
    fun provideAuthSession(
        tokenStore: TokenStore,
        identityStore: PrefsIdentityStore,
        oauthClient: OAuthClient,
        httpClient: HttpClient,
        appScope: CoroutineScope,
    ): AuthSession {
        val inner =
            GoogleAuthSession(
                tokenStore = tokenStore,
                oauthClient = oauthClient,
                httpClient = httpClient,
                scope = appScope,
            )
        return DesktopAuthSession(
            inner = inner,
            identityStore = identityStore,
            scope = appScope,
        )
    }
}

/**
 * OAuth 2.0 desktop client ID for Sketchbook. Public clients have no secret — PKCE proves
 * the client. Created in the Google Cloud console under "OAuth 2.0 Client IDs" → Application
 * type "Desktop app". If you fork this repo, replace this with your own client ID.
 */
private const val OAUTH_CLIENT_ID = "REPLACE_ME.apps.googleusercontent.com"

/**
 * Stable per-machine identity used by the sync pipeline as `hostId` (lease ownership) and
 * `hostName` (display in conflict messages). The id is generated once and cached at
 * `<dataDir>/host-id`; the name defaults to `Sketchbook on <hostname>`.
 */
private data class HostIdentity(
    val id: String,
    val name: String,
)

private fun hostIdentity(): HostIdentity {
    val dir = catalogDbPath().parent
    val idFile = dir.resolve("host-id")
    val id =
        if (Files.exists(idFile)) {
            runCatching { Files.readString(idFile).trim() }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: java.util.UUID
                    .randomUUID()
                    .toString()
                    .also { Files.writeString(idFile, it) }
        } else {
            java.util.UUID
                .randomUUID()
                .toString()
                .also { Files.writeString(idFile, it) }
        }
    val name =
        "Sketchbook on " + (
            runCatching {
                java.net.InetAddress
                    .getLocalHost()
                    .hostName
            }.getOrNull() ?: "unknown"
        )
    return HostIdentity(id = id, name = name)
}

/** Builds the graph at runtime — Metro generates the impl class. */
fun buildDesktopAppGraph(): DesktopAppGraph = createGraph<DesktopAppGraph>()

/**
 * Spawn the background pull-poll loop. One coroutine per project subscribes to
 * [PullPoller.subscribe] and advances `sync_state.cloud_head_rev` as new manifests land. The
 * outer `collectLatest` over [SyncStateStore.observeAll] re-spawns the per-project subscribers
 * when projects appear or disappear.
 *
 * No-op while cloud is unconfigured ([SwappableSyncQueue.currentCloud] == null). The poller
 * lives on the app scope; closing the app cancels everything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun startBackgroundPull(graph: DesktopAppGraph) {
    val swap = graph.syncQueue as? com.sketchbook.desktop.repo.SwappableSyncQueue ?: return
    val store = graph.syncStateStore
    val snapshots = graph.snapshotRepository
    graph.appScope.launch {
        // Re-spawn the per-project pollers whenever the active cloud backend changes (creds
        // come/go) so the long-running coroutines aren't bound to a torn-down http client.
        swap.currentCloud.collectLatest { cloud ->
            if (cloud == null) return@collectLatest
            val poller = PullPoller(cloud = cloud, snapshots = snapshots)
            store.observeAll().collectLatest { rows ->
                // collectLatest cancels the previous block, including the per-project launches.
                kotlinx.coroutines.coroutineScope {
                    for (row in rows) {
                        launch {
                            poller
                                .subscribe(
                                    uuid = row.uuid,
                                    startAfter = if (row.localRev > 0) SnapshotRev(row.localRev) else null,
                                ).collect { newSnapshot ->
                                    store.markCloudHead(newSnapshot.projectUuid, newSnapshot.rev.value)
                                    autoMaterializeAfterPull(store, snapshots, newSnapshot.projectUuid)
                                }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Auto-materialize inbound cloud manifests when it's safe to clobber the working tree:
 *  - local has no unpushed work (`dirty == false`) — drain owns the dirty path,
 *  - cloud is actually ahead of local (`cloud_head_rev > local_rev`),
 *  - destination files aren't held open by another process (Live with the project loaded).
 *
 * On busy failure we retry on a 30s cadence — `PullPoller.subscribe` only emits when a NEW
 * manifest lands, which could be never. Retrying inline closes the loop without a separate
 * timer. `collectLatest` upstream cancels us if a newer manifest arrives mid-loop, so the
 * unbounded `while` is bounded in practice by either success, dirty-flip, or replacement.
 */
internal suspend fun autoMaterializeAfterPull(
    store: SyncStateStore,
    snapshots: SnapshotRepository,
    uuid: ProjectUuid,
) {
    while (currentCoroutineContext().isActive) {
        val state = store.stateOf(uuid) ?: break
        if (state.dirty) break
        if (state.cloudHeadRev <= state.localRev) break
        val r = snapshots.materializeAt(uuid, SnapshotRev(state.cloudHeadRev))
        if (r.isSuccess) {
            store.markSynced(uuid, state.cloudHeadRev)
            break
        }
        // Retry on busy (Live has the file); break on any other error so a real bug doesn't
        // infinite-loop (and cloud_head_rev > local_rev keeps the UI in RemoteAhead state).
        if (r.exceptionOrNull() !is com.sketchbook.syncio.WorkingTreeBusyException) break
        delay(30_000)
    }
}

/**
 * Spawn the file-watcher → dirty-bit pump. Watches every configured `LibraryRoot.Projects` and
 * flips `sync_state.dirty = 1` whenever Live writes a `.als` under one of them. The background
 * drain (PR-H) consumes those flips on its own cadence — this function closes the auto-trigger
 * half of the save→push loop.
 *
 * Outer `collectLatest` over [SettingsRepository.observe] re-launches the watcher whenever the
 * set of project roots changes, so adding a root in Settings takes effect without a restart.
 * No-op while no projects roots are configured.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun startWatcher(graph: DesktopAppGraph) {
    val store = graph.syncStateStore
    val catalog = graph.catalog
    graph.appScope.launch {
        graph.settingsRepository.observe().collectLatest { settings ->
            val projectsRoots =
                settings.libraryRoots
                    .filterIsInstance<LibraryRoot.Projects>()
                    .map { Paths.get(it.path) }
                    .filter { Files.isDirectory(it) }
            if (projectsRoots.isEmpty()) return@collectLatest
            val watcher = Watcher()
            val bridge = WatcherToSyncState(watcher, catalog, store)
            // collect() suspends until cancellation (collectLatest tearing us down on the next
            // settings emission), which doubles as the lifetime of the underlying DirectoryWatcher.
            bridge.watchAll(projectsRoots).collect { /* side-effect already fired in onEach */ }
        }
    }
}

/**
 * Resolve the on-disk catalog DB path. Honors `SKETCHBOOK_DB_PATH` for tests and
 * isolated runs; otherwise picks a per-OS data directory.
 */
private fun catalogDbPath(): Path {
    System.getenv("SKETCHBOOK_DB_PATH")?.let { override ->
        val p = Paths.get(override)
        Files.createDirectories(p.parent ?: p)
        return p
    }
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val home = Paths.get(System.getProperty("user.home"))
    val dir =
        when {
            os.contains("win") -> Paths.get(System.getenv("APPDATA") ?: home.toString()).resolve("Sketchbook")
            os.contains("mac") -> home.resolve("Library/Application Support/Sketchbook")
            else -> home.resolve(".local/share/sketchbook")
        }
    Files.createDirectories(dir)
    return dir.resolve("catalog.db")
}
