package com.sketchbook.featureonboarding.anim

import androidx.compose.animation.core.CubicBezierEasing

/**
 * Material's "emphasized decelerate" easing — the default for entries in onboarding.
 *
 * Defined once here so every per-moment animation in this feature reads from the same
 * curve and the design doc's "calmer than working" feel doesn't drift across files.
 */
val EmphasizedDecelerate = CubicBezierEasing(0.2f, 0f, 0f, 1f)
