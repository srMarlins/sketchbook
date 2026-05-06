package com.sketchbook.featureprojects

import com.sketchbook.core.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shares a Health-chip-driven filter between the sidebar chip (chrome scope) and the
 * `ProjectListViewModel` (per-NavEntry scope). The chip publishes via [setFilter]; the
 * VM injects this and reads [filter] in its existing `combine` pipeline.
 *
 * Why a coordinator: the chip click happens before navigation; the destination VM doesn't
 * exist yet. A `@SingleIn(AppScope)` coordinator outlives every NavEntry, so the filter
 * survives the chip-click → screen-entry handoff without `LaunchedEffect` orchestration in
 * `RootContent`.
 */
interface ProjectFilterCoordinator {
    val filter: StateFlow<HealthFilter?>
    fun setFilter(filter: HealthFilter?)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultProjectFilterCoordinator : ProjectFilterCoordinator {
    private val _filter = MutableStateFlow<HealthFilter?>(null)
    override val filter: StateFlow<HealthFilter?> = _filter.asStateFlow()
    override fun setFilter(filter: HealthFilter?) {
        _filter.value = filter
    }
}
