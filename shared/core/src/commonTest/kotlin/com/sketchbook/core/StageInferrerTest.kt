package com.sketchbook.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Pure heuristic tests — no DB, no scanner. Each scenario locks in one branch of the rule
 * order so a future refactor that flips two cases gets caught immediately.
 */
class StageInferrerTest {

    private val now = Instant.parse("2026-05-06T12:00:00Z")

    private fun daysAgo(days: Long): Instant =
        Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - days * 86_400_000L)

    @Test
    fun classifiesSketchWhenSmallAndRecent() {
        val result = StageInferrer.infer(
            StageInputs(
                trackCount = 3,
                lastModified = daysAgo(5),
                pluginNames = listOf("Reverb", "EQ Eight"),
                hasLocalBounce = false,
            ),
            now,
        )
        assertEquals(Stage.Sketch, result)
    }

    @Test
    fun classifiesInProgressWhenMidSizedAndRecentWithoutMastering() {
        val result = StageInferrer.infer(
            StageInputs(
                trackCount = 8,
                lastModified = daysAgo(10),
                pluginNames = listOf("EQ Eight", "Saturator"),
                hasLocalBounce = false,
            ),
            now,
        )
        assertEquals(Stage.InProgress, result)
    }

    @Test
    fun classifiesMixingWhenMasteringChainPresentAndRecentlyEdited() {
        val result = StageInferrer.infer(
            StageInputs(
                trackCount = 14,
                lastModified = daysAgo(7),
                pluginNames = listOf("EQ Eight", "FabFilter Pro-L 2"),
                hasLocalBounce = false,
            ),
            now,
        )
        assertEquals(Stage.Mixing, result)
    }

    @Test
    fun classifiesDoneWhenMasteringPlusBouncePlusCooled() {
        val result = StageInferrer.infer(
            StageInputs(
                trackCount = 18,
                lastModified = daysAgo(60),
                pluginNames = listOf("Ozone 10 Maximizer"),
                hasLocalBounce = true,
            ),
            now,
        )
        assertEquals(Stage.Done, result)
    }

    @Test
    fun classifiesStuckWhenLargeAndDormantWithoutBounce() {
        val result = StageInferrer.infer(
            StageInputs(
                trackCount = 22,
                lastModified = daysAgo(180),
                pluginNames = listOf("EQ Eight"),
                hasLocalBounce = false,
            ),
            now,
        )
        assertEquals(Stage.Stuck, result)
    }

    @Test
    fun returnsNullForAmbiguousMiddle() {
        // 6 tracks, 60 days dormant, no mastering, no bounce — doesn't fit any rule.
        // Important: this is the "I started something a few months back" case the user
        // explicitly does NOT want auto-classified.
        val result = StageInferrer.infer(
            StageInputs(
                trackCount = 6,
                lastModified = daysAgo(60),
                pluginNames = listOf("EQ Eight"),
                hasLocalBounce = false,
            ),
            now,
        )
        assertNull(result)
    }

    @Test
    fun mixingTakesPriorityOverInProgress() {
        // Mid-sized + recent + mastering: both InProgress (>=5 tracks, <30d, no mastering) AND
        // Mixing (<14d, mastering) could otherwise match — the rule order returns Mixing because
        // mastering is the more specific signal.
        val result = StageInferrer.infer(
            StageInputs(
                trackCount = 8,
                lastModified = daysAgo(3),
                pluginNames = listOf("Pro-L 2", "Compressor"),
                hasLocalBounce = false,
            ),
            now,
        )
        assertEquals(Stage.Mixing, result)
    }

    @Test
    fun masteringMatchIsCaseInsensitiveAndSubstring() {
        // "FabFilter Pro-L 2" contains "Pro-L"; "iZotope Ozone 10" contains "Ozone".
        for (name in listOf("FabFilter Pro-L 2", "ozone 10", "OTT", "Stock Limiter Pro")) {
            val result = StageInferrer.infer(
                StageInputs(
                    trackCount = 12,
                    lastModified = daysAgo(2),
                    pluginNames = listOf(name),
                    hasLocalBounce = false,
                ),
                now,
            )
            assertEquals(Stage.Mixing, result, "expected Mixing for plugin '$name', got $result")
        }
    }

    @Test
    fun staleSketchFallsThroughToNull() {
        // Small + dormant + no mastering — Sketch requires <30d so this falls through to null.
        // We don't want to keep "Sketch" pinned on a 3-track idea the user hasn't touched in a
        // year; the chip would imply active intent.
        val result = StageInferrer.infer(
            StageInputs(
                trackCount = 3,
                lastModified = daysAgo(365),
                pluginNames = emptyList(),
                hasLocalBounce = false,
            ),
            now,
        )
        assertNull(result)
    }
}
