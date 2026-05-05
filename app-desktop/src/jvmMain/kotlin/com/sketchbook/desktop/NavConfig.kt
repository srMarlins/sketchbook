package com.sketchbook.desktop

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * `SavedStateConfiguration` for Nav3's back-stack persistence on JVM Desktop. Android uses
 * reflection to discover [NavKey] subclasses; Desktop must register them explicitly.
 */
internal val NavSavedStateConfig: SavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(Screen.Projects::class)
            subclass(Screen.ProjectDetail::class)
            subclass(Screen.Timeline::class)
            subclass(Screen.Proposals::class)
            subclass(Screen.NeedsAttention::class)
            subclass(Screen.Settings::class)
        }
    }
}
