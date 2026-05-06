package com.sketchbook.desktop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.sketchbook.core.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import kotlin.reflect.KClass

/**
 * Concrete [MetroViewModelFactory] implementation. Metro contributes the
 * `@ContributesIntoMap(AppScope::class) @ViewModelKey @Inject` ViewModels into the three
 * provider maps below; the abstract base then resolves a class to its instance.
 *
 * Bound twice — once as `MetroViewModelFactory` (consumed via `LocalMetroViewModelFactory` in
 * Compose) and once as a vanilla `ViewModelProvider.Factory` (so legacy consumers like
 * `viewModel(factory = ...)` work without going through the Compose local).
 */
@ContributesBinding(AppScope::class)
@ContributesBinding(AppScope::class, binding<ViewModelProvider.Factory>())
@SingleIn(AppScope::class)
@Inject
class InjectedViewModelFactory(
    override val viewModelProviders: Map<KClass<out ViewModel>, () -> ViewModel>,
    override val assistedFactoryProviders: Map<KClass<out ViewModel>, () -> ViewModelAssistedFactory>,
    override val manualAssistedFactoryProviders: Map<KClass<out ManualViewModelAssistedFactory>, () -> ManualViewModelAssistedFactory>,
) : MetroViewModelFactory()
