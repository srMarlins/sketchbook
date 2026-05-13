package com.sketchbook.core

/**
 * App-level user-facing message. Emitted to [UserMessageBus] by background components
 * (sync drain, auth refresh, scan coordinator) and rendered by the chrome — never by the
 * originating ViewModel — so an error from a job that outlives a screen still reaches the user.
 *
 * Two flavors:
 *  - [Snackbar] — transient. Auto-dismiss after a few seconds. One-off failures, in-flight retries.
 *  - [Banner] — persistent until a [BannerKey]'d condition clears (offline → online, signed-out
 *    → signed-in). Re-emitting the same key coalesces; the chrome shows a single banner per key.
 *
 * No closures here — kept a pure data type so it's serializable, equatable, and testable. CTAs go
 * through [Action] + [ActionKind] enums; the chrome interprets standard intents (open settings,
 * sign in) directly rather than callbacks pinned by the emitter.
 */
sealed class UserMessage {
    abstract val text: String
    abstract val action: Action?

    data class Snackbar(
        override val text: String,
        override val action: Action? = null,
    ) : UserMessage()

    data class Banner(
        val key: BannerKey,
        override val text: String,
        override val action: Action? = null,
    ) : UserMessage()
}

/**
 * Identifies a persistent condition. Re-emitting the same key replaces the existing banner;
 * [UserMessageBus.retractBanner] clears it when the underlying condition resolves. Add new
 * keys here as new persistent-error categories appear — avoid ad-hoc strings.
 */
enum class BannerKey {
    Offline,
    AuthExpired,
    QuotaExceeded,
}

/** Optional call-to-action attached to a [UserMessage]. */
data class Action(
    val label: String,
    val kind: ActionKind,
)

/**
 * What a CTA does when clicked. Concrete kinds rather than callbacks so [UserMessage] stays
 * a data type and the chrome can route them to its already-injected navigation / auth handles.
 */
sealed interface ActionKind {
    data object OpenSettings : ActionKind

    data object SignIn : ActionKind

    data object Dismiss : ActionKind
}
