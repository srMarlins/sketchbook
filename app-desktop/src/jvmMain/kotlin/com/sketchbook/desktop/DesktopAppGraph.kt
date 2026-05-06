package com.sketchbook.desktop

import com.sketchbook.desktop.repo.InMemoryLockRepository
import com.sketchbook.desktop.repo.InMemoryProjectRepository
import com.sketchbook.desktop.repo.InMemoryProposalsRepository
import com.sketchbook.desktop.repo.InMemoryRepairRepository
import com.sketchbook.desktop.repo.InMemorySettingsRepository
import com.sketchbook.desktop.repo.InMemorySnapshotRepository
import com.sketchbook.desktop.repo.InMemorySyncQueue
import com.sketchbook.repo.LockRepository
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.ProposalsRepository
import com.sketchbook.repo.RepairRepository
import com.sketchbook.repo.SettingsRepository
import com.sketchbook.repo.SnapshotRepository
import com.sketchbook.repo.SyncQueue
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
 */
@DependencyGraph(scope = AppScope::class)
interface DesktopAppGraph {

    val appScope: CoroutineScope
    val projectRepository: ProjectRepository
    /**
     * Concrete in-memory project repo. The scanner needs the concrete type so it can call
     * `addRows`, which is intentionally outside the public `ProjectRepository` API at v1
     * (the eventual SqlDelight-backed repo will scan internally and never expose batch insert
     * to UI code). Until then the desktop shell wires the scanner through this accessor.
     */
    val inMemoryProjectRepository: InMemoryProjectRepository
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

    // The in-memory repos hold their state in `MutableStateFlow`s, so two consumers observing
    // the same repository must see the same instance. Drop @SingleIn and the project list +
    // project detail would observe independent flows.

    @Provides @SingleIn(AppScope::class)
    fun provideInMemoryProjectRepository(): InMemoryProjectRepository = InMemoryProjectRepository()

    @Provides @SingleIn(AppScope::class)
    fun provideProjectRepository(impl: InMemoryProjectRepository): ProjectRepository = impl

    @Provides @SingleIn(AppScope::class)
    fun provideSnapshotRepository(): SnapshotRepository = InMemorySnapshotRepository()

    @Provides @SingleIn(AppScope::class)
    fun provideProposalsRepository(): ProposalsRepository = InMemoryProposalsRepository()

    @Provides @SingleIn(AppScope::class)
    fun provideRepairRepository(): RepairRepository = InMemoryRepairRepository()

    @Provides @SingleIn(AppScope::class)
    fun provideSettingsRepository(): SettingsRepository = InMemorySettingsRepository()

    @Provides @SingleIn(AppScope::class)
    fun provideLockRepository(): LockRepository = InMemoryLockRepository()

    @Provides @SingleIn(AppScope::class)
    fun provideSyncQueue(
        projects: ProjectRepository,
        scope: CoroutineScope,
    ): SyncQueue = InMemorySyncQueue(projects = projects, scope = scope)
}

/** Application-lifetime scope marker for Metro bindings. */
abstract class AppScope private constructor()

/** Builds the graph at runtime — Metro generates the impl class. */
fun buildDesktopAppGraph(): DesktopAppGraph = createGraph<DesktopAppGraph>()
