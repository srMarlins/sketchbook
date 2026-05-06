package com.sketchbook.desktop

import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.CatalogFts
import com.sketchbook.catalog.CatalogHandle
import com.sketchbook.catalog.JvmScanner
import com.sketchbook.catalog.SyncStateStore
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.desktop.repo.InMemoryLockRepository
import com.sketchbook.desktop.repo.PreferencesSettingsRepository
import com.sketchbook.desktop.repo.SwappableSyncQueue
import com.sketchbook.repo.JournalRepository
import com.sketchbook.repo.LockRepository
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.ProposalsRepository
import com.sketchbook.repo.RepairRepository
import com.sketchbook.repo.SettingsRepository
import com.sketchbook.repo.SnapshotRepository
import com.sketchbook.repo.SyncQueue
import com.sketchbook.repo.impl.InMemoryJournalRepository
import com.sketchbook.repo.impl.SqlProjectRepository
import com.sketchbook.repo.impl.SqlProposalsRepository
import com.sketchbook.repo.impl.SqlRepairRepository
import com.sketchbook.repo.impl.SqlSnapshotRepository
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * **Catalog (SQLite).** Project / Snapshot / Proposals / Repair are SQLDelight-backed; the
 * DB lives at `~/.local/share/sketchbook/catalog.db` (Linux/Mac) or
 * `%APPDATA%\Sketchbook\catalog.db` (Windows). One handle per app instance. Lock + Settings
 * remain in-memory: locks belong to the sync engine (PR-22) and settings still ride on
 * `java.util.prefs.Preferences` until the keychain rotation in v1.1.
 */
@DependencyGraph(scope = AppScope::class)
interface DesktopAppGraph {

    val appScope: CoroutineScope
    val catalogHandle: CatalogHandle
    val catalog: Catalog
    val catalogFts: CatalogFts
    val syncStateStore: SyncStateStore
    val scanner: JvmScanner
    val projectRepository: ProjectRepository
    val journalRepository: JournalRepository
    val snapshotRepository: SnapshotRepository
    val proposalsRepository: ProposalsRepository
    val repairRepository: RepairRepository
    val settingsRepository: SettingsRepository
    val lockRepository: LockRepository
    val syncQueue: SyncQueue

    // ---- App lifetime: shared mutable state ---------------------------------------------------

    @Provides @SingleIn(AppScope::class)
    fun provideAppScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @SingleIn(AppScope::class)
    fun provideCatalogHandle(): CatalogHandle = CatalogDb.openOnDisk(catalogDbPath())

    @Provides @SingleIn(AppScope::class)
    fun provideCatalog(handle: CatalogHandle): Catalog = handle.catalog

    @Provides @SingleIn(AppScope::class)
    fun provideCatalogFts(handle: CatalogHandle): CatalogFts = CatalogFts(handle.driver)

    @Provides @SingleIn(AppScope::class)
    fun provideJvmScanner(catalog: Catalog, fts: CatalogFts): JvmScanner =
        JvmScanner(catalog = catalog, fts = fts)

    @Provides @SingleIn(AppScope::class)
    fun provideSyncStateStore(catalog: Catalog): SyncStateStore = SyncStateStore(catalog)

    @Provides @SingleIn(AppScope::class)
    fun provideJournalRepository(): JournalRepository = InMemoryJournalRepository()

    @Provides @SingleIn(AppScope::class)
    fun provideProjectRepository(
        catalog: Catalog,
        fts: CatalogFts,
        journal: JournalRepository,
    ): ProjectRepository = SqlProjectRepository(
        catalog = catalog,
        ioDispatcher = Dispatchers.IO,
        journal = journal,
        ftsSearch = { query -> fts.search(query) },
    )

    @Provides @SingleIn(AppScope::class)
    fun provideSnapshotRepository(catalog: Catalog): SnapshotRepository =
        SqlSnapshotRepository(catalog = catalog, ioDispatcher = Dispatchers.IO)

    @Provides @SingleIn(AppScope::class)
    fun provideProposalsRepository(catalog: Catalog): ProposalsRepository =
        SqlProposalsRepository(catalog = catalog, ioDispatcher = Dispatchers.IO)

    @Provides @SingleIn(AppScope::class)
    fun provideRepairRepository(catalog: Catalog): RepairRepository =
        SqlRepairRepository(catalog = catalog, ioDispatcher = Dispatchers.IO)

    @Provides @SingleIn(AppScope::class)
    fun provideSettingsRepository(): SettingsRepository = PreferencesSettingsRepository(
        node = Preferences.userNodeForPackage(SettingsRepository::class.java),
        ioDispatcher = Dispatchers.IO,
    )

    @Provides @SingleIn(AppScope::class)
    fun provideLockRepository(): LockRepository = InMemoryLockRepository()

    @Provides @SingleIn(AppScope::class)
    fun provideSyncQueue(
        settings: SettingsRepository,
        projects: ProjectRepository,
        store: SyncStateStore,
        scope: CoroutineScope,
    ): SyncQueue = SwappableSyncQueue(
        settings = settings,
        projects = projects,
        syncStateStore = store,
        scope = scope,
        hostId = hostIdentity().id,
        hostName = hostIdentity().name,
    )
}

/**
 * Stable per-machine identity used by the sync pipeline as `hostId` (lease ownership) and
 * `hostName` (display in conflict messages). The id is generated once and cached at
 * `<dataDir>/host-id`; the name defaults to `Sketchbook on <hostname>`.
 */
private data class HostIdentity(val id: String, val name: String)

private fun hostIdentity(): HostIdentity {
    val dir = catalogDbPath().parent
    val idFile = dir.resolve("host-id")
    val id = if (Files.exists(idFile)) {
        runCatching { Files.readString(idFile).trim() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString().also { Files.writeString(idFile, it) }
    } else {
        java.util.UUID.randomUUID().toString().also { Files.writeString(idFile, it) }
    }
    val name = "Sketchbook on " + (runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: "unknown")
    return HostIdentity(id = id, name = name)
}

/** Application-lifetime scope marker for Metro bindings. */
abstract class AppScope private constructor()

/** Builds the graph at runtime — Metro generates the impl class. */
fun buildDesktopAppGraph(): DesktopAppGraph = createGraph<DesktopAppGraph>()

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
    val dir = when {
        os.contains("win") -> Paths.get(System.getenv("APPDATA") ?: home.toString()).resolve("Sketchbook")
        os.contains("mac") -> home.resolve("Library/Application Support/Sketchbook")
        else -> home.resolve(".local/share/sketchbook")
    }
    Files.createDirectories(dir)
    return dir.resolve("catalog.db")
}
