package com.sketchbook.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * Cancellation-safe variant of [runCatching]. Rethrows [CancellationException] so a cancelled
 * coroutine actually unwinds rather than having its cancellation signal swallowed and mapped to a
 * Result.failure. Catches everything else as `Result.failure`.
 *
 * Use this anywhere a `runCatching { ... }` body suspends — `runCatching` itself catches
 * `Throwable`, which includes `CancellationException`, and that breaks structured concurrency:
 * a scope cancellation gets converted into a swallowed failure, the parent coroutine completes
 * "successfully," and downstream timers/deadlines never fire.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t)
    }
