package com.sketchbook.desktop

import com.sketchbook.desktop.repo.InMemoryLockRepository
import com.sketchbook.desktop.repo.InMemoryProjectRepository
import com.sketchbook.desktop.repo.InMemoryProposalsRepository
import com.sketchbook.desktop.repo.InMemoryRepairRepository
import com.sketchbook.desktop.repo.InMemorySettingsRepository
import com.sketchbook.desktop.repo.InMemorySnapshotRepository
import com.sketchbook.repo.LockRepository
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.ProposalsRepository
import com.sketchbook.repo.RepairRepository
import com.sketchbook.repo.SettingsRepository
import com.sketchbook.repo.SnapshotRepository
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
 * In-memory stub repositories until the SQLDelight-backed bindings replace them.
 */
@DependencyGraph(scope = AppScope::class)
interface DesktopAppGraph {

    val appScope: CoroutineScope
    val projectRepository: ProjectRepository
    val snapshotRepository: SnapshotRepository
    val proposalsRepository: ProposalsRepository
    val repairRepository: RepairRepository
    val settingsRepository: SettingsRepository
    val lockRepository: LockRepository

    @Provides
    @SingleIn(AppScope::class)
    fun provideAppScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @SingleIn(AppScope::class)
    fun provideProjectRepository(): ProjectRepository = InMemoryProjectRepository()

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
}

/** Application-lifetime scope marker for Metro bindings. */
abstract class AppScope private constructor()

/** Builds the graph at runtime — Metro generates the impl class. */
fun buildDesktopAppGraph(): DesktopAppGraph = createGraph<DesktopAppGraph>()
