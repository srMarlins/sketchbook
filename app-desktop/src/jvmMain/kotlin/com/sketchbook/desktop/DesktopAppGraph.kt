package com.sketchbook.desktop

import com.sketchbook.desktop.repo.InMemoryProjectRepository
import com.sketchbook.desktop.repo.InMemoryProposalsRepository
import com.sketchbook.desktop.repo.InMemoryRepairRepository
import com.sketchbook.desktop.repo.InMemorySettingsRepository
import com.sketchbook.desktop.repo.InMemorySnapshotRepository
import com.sketchbook.repo.ProjectRepository
import com.sketchbook.repo.ProposalsRepository
import com.sketchbook.repo.RepairRepository
import com.sketchbook.repo.SettingsRepository
import com.sketchbook.repo.SnapshotRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Composition root for the Compose Desktop shell. Stub in-memory repositories until PR-19 wires
 * the SQLDelight/sync impls. The application coroutine scope outlives any one screen so feature
 * `StateHolder`s can keep their `stateIn` subscriptions alive across navigation.
 *
 * Plain Kotlin: a thin holder of singletons. We deferred Metro until its JVM-21 baseline matches
 * our toolchain (currently 17) and there are real (non-stub) bindings worth a graph.
 */
class DesktopAppGraph(
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    val projectRepository: ProjectRepository = InMemoryProjectRepository(),
    val snapshotRepository: SnapshotRepository = InMemorySnapshotRepository(),
    val proposalsRepository: ProposalsRepository = InMemoryProposalsRepository(),
    val repairRepository: RepairRepository = InMemoryRepairRepository(),
    val settingsRepository: SettingsRepository = InMemorySettingsRepository(),
)
