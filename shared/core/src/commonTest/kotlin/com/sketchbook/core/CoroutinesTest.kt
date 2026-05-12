package com.sketchbook.core

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RunCatchingCancellableTest {
    @Test
    fun successWrapsValue() {
        val r = runCatchingCancellable { 42 }
        assertEquals(42, r.getOrThrow())
    }

    @Test
    fun nonCancellationThrowableBecomesFailure() {
        val r = runCatchingCancellable<Int> { error("boom") }
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun cancellationExceptionPropagates() {
        assertFailsWith<CancellationException> {
            runCatchingCancellable<Int> { throw CancellationException("cancelled") }
        }
    }
}
