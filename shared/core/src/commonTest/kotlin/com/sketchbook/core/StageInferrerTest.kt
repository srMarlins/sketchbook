package com.sketchbook.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Pure heuristic — no DB, no FS — so every test is just calling [StageInferrer.infer] with
 * fixed inputs and asserting the variant. Rule order matters; cases that overlap two rules
 * (e.g. "12 tracks + Limiter, edited 3 days ago" can match both Mixing and InProgress) are
 * tested explicitly so a refactor that flips rule order shows up as a red test.
 */
class StageInferrerTest {

    private val now = Instant.parse("2026-05-05T12:00:00Z")

    @Test
    fun classifiesAsDoneWhenMasteringChainAndLocalBounceAndEditedOver30DaysAgo() {
        val stage = StageInferrer.infer(
            trackCount = 8,
            pluginNames = listOf("EQ Eight", "Pro-L 2"),
            hasLocalBounce = true,
            lastModified = now - 60.days,
            now = now,
        )
        assertEquals(Stage.Done, stage)
    }

    @Test
    fun classifiesAsMixingWhenMasteringChainAndEditedWithin14Days() {
        val stage = StageInferrer.infer(
            trackCount = 12,
            pluginNames = listOf("Compressor", "Limiter"),
            hasLocalBounce = false,
            lastModified = now - 3.days,
            now = now,
        )
        assertEquals(Stage.Mixing, stage)
    }

    @Test
    fun classifiesAsStuckWhenManyTracksAndNoBounceAndOver90DaysOld() {
        val stage = StageInferrer.infer(
            trackCount = 14,
            pluginNames = listOf("EQ Eight"),
            hasLocalBounce = false,
            lastModified = now - 120.days,
            now = now,
        )
        assertEquals(Stage.Stuck, stage)
    }

    @Test
    fun classifiesAsInProgressWhenFiveTracksAndRecentAndNoMastering() {
        val stage = StageInferrer.infer(
            trackCount = 7,
            pluginNames = listOf("Operator", "Reverb"),
            hasLocalBounce = false,
            lastModified = now - 5.days,
            now = now,
        )
        assertEquals(Stage.InProgress, stage)
    }

    @Test
    fun classifiesAsSketchWhenSmallAndRecentAndNothingElse() {
        val stage = StageInferrer.infer(
            trackCount = 3,
            pluginNames = emptyList(),
            hasLocalBounce = false,
            lastModified = now - 7.days,
            now = now,
        )
        assertEquals(Stage.Sketch, stage)
    }

    @Test
    fun masteringPriorityBeatsInProgressForLargeRecentProject() {
        // 12 tracks + Limiter edited 3 days ago could match both Mixing (rule 2) and
        // InProgress (rule 4). Rule order says Mixing wins.
        val stage = StageInferrer.infer(
            trackCount = 12,
            pluginNames = listOf("FabFilter Pro-L 2"),
            hasLocalBounce = false,
            lastModified = now - 3.days,
            now = now,
        )
        assertEquals(Stage.Mixing, stage)
    }

    @Test
    fun staleSketchFallsThroughToNull() {
        // 3 tracks, no mastering, no bounce, but 60 days old — too stale for Sketch
        // (recent < 30d), too few tracks for Stuck. No rule fires.
        val stage = StageInferrer.infer(
            trackCount = 3,
            pluginNames = emptyList(),
            hasLocalBounce = false,
            lastModified = now - 60.days,
            now = now,
        )
        assertNull(stage)
    }

    @Test
    fun pluginNameMatchIsCaseInsensitiveAndSubstring() {
        // "Ozone 11 Maximizer" carries the substring "ozone" and the substring "maximizer";
        // both are mastering needles. Edited 5 days ago → Mixing.
        val stage = StageInferrer.infer(
            trackCount = 6,
            pluginNames = listOf("Ozone 11 Maximizer"),
            hasLocalBounce = false,
            lastModified = now - 5.days,
            now = now,
        )
        assertEquals(Stage.Mixing, stage)
    }

    @Test
    fun masteredButNoBounceAndStale_doesNotMatchDoneOrMixing_fallsThrough() {
        // Mastered but no bounce + 60d old. Rule 1 needs bounce, rule 2 needs <=14d.
        // 6 tracks, so Stuck wants >=10 — falls through.
        val stage = StageInferrer.infer(
            trackCount = 6,
            pluginNames = listOf("Pro-L 2"),
            hasLocalBounce = false,
            lastModified = now - 60.days,
            now = now,
        )
        assertNull(stage)
    }

    @Test
    fun smallStaleProjectIsNotStuckEvenIfNoBounce() {
        // Only 3 tracks. Stuck requires >=10. With no other signals it just falls through.
        val stage = StageInferrer.infer(
            trackCount = 3,
            pluginNames = listOf("Operator"),
            hasLocalBounce = false,
            lastModified = now - 200.days,
            now = now,
        )
        assertNull(stage)
    }

    @Test
    fun parseOrNullRoundTripsKnownVariants() {
        for (s in Stage.values()) {
            assertEquals(s, Stage.parseOrNull(s.name))
        }
    }

    @Test
    fun parseOrNullReturnsNullForUnknownAndNull() {
        assertNull(Stage.parseOrNull(null))
        assertNull(Stage.parseOrNull(""))
        assertNull(Stage.parseOrNull("FutureVariantThatDoesntExistYet"))
    }
}
