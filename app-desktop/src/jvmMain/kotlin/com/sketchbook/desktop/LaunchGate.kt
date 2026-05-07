package com.sketchbook.desktop

import com.sketchbook.repo.SettingsRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Decision made about which top-level surface to render. Driven by a live flow so the UI
 * transparently flips when `firstRunCompletedAt` is written by `OnboardingViewModel.Finish`.
 *
 * Designed to grow a `Migration` arm later (when v0.1 -> v1 catalog upgrades become a thing) -
 * adding a new sealed variant + a new mapping branch in [LaunchGate.observe] is the only churn
 * required.
 */
sealed interface LaunchDecision {
    data object Onboarding : LaunchDecision
    data object MainApp : LaunchDecision
    // Future: data class Migration(...) — adding a new variant + branch here is the only churn.
}

/**
 * Canonical resolver for the top-level launch surface. Single source of truth: the live
 * `Settings.firstRunCompletedAt`. Designed to grow a `Migration` arm later — adding a new
 * sealed variant + a new mapping branch is the only churn required.
 */
@Inject
class LaunchGate(private val settings: SettingsRepository) {

    /**
     * Live flow of decisions. Re-emits when [SettingsRepository] state changes — e.g. when
     * `OnboardingViewModel.Finish` writes `firstRunCompletedAt`, this flow flips from
     * [LaunchDecision.Onboarding] to [LaunchDecision.MainApp].
     */
    fun observe(): Flow<LaunchDecision> =
        settings.observe()
            .map { if (it.firstRunCompletedAt == null) LaunchDecision.Onboarding else LaunchDecision.MainApp }
            .distinctUntilChanged()

    /** Convenience one-shot for callers that just need the current decision (e.g. tests). */
    suspend fun resolve(): LaunchDecision = observe().first()
}
