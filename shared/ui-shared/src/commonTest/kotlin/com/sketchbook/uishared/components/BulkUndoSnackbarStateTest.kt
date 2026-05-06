package com.sketchbook.uishared.components

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BulkUndoSnackbarStateTest {

    @Test fun showStartsFiveSecondCountdown() = runTest {
        val state = BulkUndoSnackbarState(this)
        state.show("Approved 22 proposals", onUndo = {})
        assertEquals(5, state.secondsRemaining.value)
        advanceTimeBy(2_500)
        assertEquals(3, state.secondsRemaining.value)
    }

    @Test fun expiresAfterFiveSecondsAndCallsOnExpire() = runTest {
        var expired = false
        val state = BulkUndoSnackbarState(this)
        state.show("x", onUndo = {}, onExpire = { expired = true })
        advanceTimeBy(5_100)
        assertNull(state.current.value)
        assertTrue(expired)
    }

    @Test fun secondShowReplacesFirstAndCommitsIt() = runTest {
        var firstExpired = false
        val state = BulkUndoSnackbarState(this)
        state.show("first", onUndo = {}, onExpire = { firstExpired = true })
        advanceTimeBy(2_000)
        state.show("second", onUndo = {})
        assertTrue(firstExpired)
        assertEquals("second", state.current.value?.message)
        assertEquals(5, state.secondsRemaining.value)
    }

    @Test fun undoFiresCallbackAndDismisses() = runTest {
        var undone = false
        val state = BulkUndoSnackbarState(this)
        state.show("x", onUndo = { undone = true })
        state.undo()
        assertTrue(undone)
        assertNull(state.current.value)
    }
}
