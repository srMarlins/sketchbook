package com.sketchbook.desktop

import com.sketchbook.repo.SettingsRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first

/**
 * Decision made once per app launch: do we show onboarding, or go straight to the main app?
 *
 * Designed to grow a `Migration` arm later (when v0.1 -> v1 catalog upgrades become a thing) -
 * adding a new sealed variant + a new branch in [LaunchGate.resolve] is the only churn required.
 */
sealed interface LaunchDecision {
    data object Onboarding : LaunchDecision
    data object MainApp : LaunchDecision
}

/**
 * Resolves which top-level surface to render at startup, based on whether onboarding has
 * completed. Single source of truth: `Settings.firstRunCompletedAt`.
 */
@Inject
class LaunchGate(private val settings: SettingsRepository) {

    suspend fun resolve(): LaunchDecision =
        if (settings.observe().first().firstRunCompletedAt == null) {
            LaunchDecision.Onboarding
        } else {
            LaunchDecision.MainApp
        }
}
