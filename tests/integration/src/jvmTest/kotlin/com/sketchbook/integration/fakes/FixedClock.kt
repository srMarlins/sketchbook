package com.sketchbook.integration.fakes

import kotlin.time.Clock
import kotlin.time.Instant

class FixedClock(var instant: Instant) : Clock {
    override fun now(): Instant = instant
}
