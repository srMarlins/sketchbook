package com.sketchbook.core

/**
 * Domain error hierarchy. Every public repository / sync / cloud API returns either a value or
 * one of these — no exception leak past the seam unless the caller is the I/O layer itself.
 */
sealed class SketchbookError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** No row matched the given identifier. */
    class NotFound(
        message: String,
    ) : SketchbookError(message)

    /** Optimistic-concurrency / lease-lock conflict. */
    class Conflict(
        message: String,
    ) : SketchbookError(message)

    /** A blob, manifest, or sidecar failed an integrity check (hash mismatch, malformed JSON…). */
    class IntegrityError(
        message: String,
        cause: Throwable? = null,
    ) : SketchbookError(message, cause)

    /** Local I/O failure (disk full, permission denied, file vanished mid-read…). */
    class IoFailure(
        message: String,
        cause: Throwable? = null,
    ) : SketchbookError(message, cause)

    /**
     * Cloud HTTP failure. [status] is the HTTP code; [body] is the truncated response body for diag.
     * `null` status means a transport-level failure (DNS, TLS, socket reset).
     */
    class RemoteFailure(
        val status: Int?,
        val body: String?,
        message: String,
        cause: Throwable? = null,
    ) : SketchbookError(message, cause)
}
