package com.sketchbook.core

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_PAYLOAD_TOO_LARGE = 413
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_INSUFFICIENT_STORAGE = 507
private const val HTTP_SERVER_ERROR_RANGE_START = 500
private const val HTTP_SERVER_ERROR_RANGE_END = 599

/**
 * Map a [Throwable] to a user-facing [UserMessage], or `null` when the error should be silent
 * (e.g. an action the user themselves cancelled).
 *
 * This mapper covers [SketchbookError] only — auth failures live in the parallel `AuthException`
 * hierarchy in `:shared:auth`, which has its own extension. App-side emitters dispatch by type.
 *
 * Decisions encoded:
 *  - Transport-level remote failures (`status == null` — DNS, TLS, socket reset) become a
 *    persistent **Offline** banner. The drain keeps retrying; the banner clears when something
 *    later succeeds.
 *  - HTTP 401/403 → **AuthExpired** banner with Sign-in CTA. Refresh token rejected.
 *  - HTTP 413/429/507 → **QuotaExceeded** banner with Open Settings CTA.
 *  - Other 5xx → snackbar. The drain already retries; the user just gets one transient note.
 *  - IO failures → snackbar with context-aware wording.
 *  - Conflict / Integrity / NotFound → snackbar (rare on the UI thread; per-project conflict has
 *    its own inline surface in GcsSyncQueue.conflictMessages).
 *  - Anything else → generic snackbar so the user isn't left silent.
 */
fun Throwable.toUserMessage(context: ErrorContext): UserMessage? =
    when (this) {
        is kotlin.coroutines.cancellation.CancellationException -> {
            // Cancellation is cooperative; the system initiated it. Emitters should rethrow at the
            // catch site, but if one slips through, never surface a generic "something went wrong"
            // snackbar for a cancellation the user themselves triggered (or a collectLatest swap).
            null
        }

        is SketchbookError.RemoteFailure -> {
            mapRemoteFailure(this, context)
        }

        is SketchbookError.IoFailure -> {
            mapIoFailure(context)
        }

        is SketchbookError.Conflict -> {
            UserMessage.Snackbar("Cloud diverged — see the project's conflict notice.")
        }

        is SketchbookError.IntegrityError -> {
            UserMessage.Snackbar("A cloud file looked corrupt. Retrying.")
        }

        is SketchbookError.NotFound -> {
            UserMessage.Snackbar("Couldn't find that on the cloud.")
        }

        else -> {
            UserMessage.Snackbar("Something went wrong — see diagnostics.")
        }
    }

private fun mapRemoteFailure(
    e: SketchbookError.RemoteFailure,
    context: ErrorContext,
): UserMessage =
    when (val status = e.status) {
        null -> {
            UserMessage.Banner(
                key = BannerKey.Offline,
                text = "You're offline. Sketchbook will sync when you reconnect.",
            )
        }

        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> {
            UserMessage.Banner(
                key = BannerKey.AuthExpired,
                text = "Sign-in expired. Sign in again to keep syncing.",
                action = Action(label = "Sign in", kind = ActionKind.SignIn),
            )
        }

        HTTP_PAYLOAD_TOO_LARGE, HTTP_TOO_MANY_REQUESTS, HTTP_INSUFFICIENT_STORAGE -> {
            UserMessage.Banner(
                key = BannerKey.QuotaExceeded,
                text = "Cloud storage limit reached.",
                action = Action(label = "Open settings", kind = ActionKind.OpenSettings),
            )
        }

        in HTTP_SERVER_ERROR_RANGE_START..HTTP_SERVER_ERROR_RANGE_END -> {
            UserMessage.Snackbar(
                text =
                    when (context) {
                        ErrorContext.Sync -> "Cloud is having trouble — retrying."
                        else -> "Cloud request failed (server error)."
                    },
            )
        }

        else -> {
            UserMessage.Snackbar(text = "Cloud request failed (HTTP $status).")
        }
    }

private fun mapIoFailure(context: ErrorContext): UserMessage =
    UserMessage.Snackbar(
        text =
            when (context) {
                ErrorContext.Settings -> {
                    "Couldn't save your settings."
                }

                ErrorContext.FileSystem, ErrorContext.Scan -> {
                    "Sketchbook can't read that folder — check permissions."
                }

                ErrorContext.Snapshot -> {
                    "Couldn't read project files for a snapshot."
                }

                ErrorContext.Lock -> {
                    "Couldn't update the project lock."
                }

                ErrorContext.Sync -> {
                    "Disk error during sync."
                }
            },
    )
