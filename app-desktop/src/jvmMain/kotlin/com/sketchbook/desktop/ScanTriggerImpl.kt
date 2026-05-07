package com.sketchbook.desktop

import com.sketchbook.core.AppScope
import com.sketchbook.featureonboarding.ScanTrigger
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Adapter that lets `feature-onboarding` (a multiplatform module that doesn't know about JVM /
 * the desktop scan engine) request a scan after `OnboardingIntent.Finish`. The coordinator's
 * settings observer already auto-picks-up roots written by Finish; calling [start] here makes
 * sure the observer is running (idempotent — safe even though `Main` already starts it once).
 */
@ContributesBinding(AppScope::class)
@Inject
class ScanTriggerImpl(private val coordinator: LibraryScanCoordinator) : ScanTrigger {
    override fun triggerScan() {
        coordinator.start()
    }
}
