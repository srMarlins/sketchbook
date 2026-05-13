package com.sketchbook.core

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * App-level fan-out for user-facing errors and notices. Background components (sync drain, auth
 * refresh, library scan) emit via [emit]; the chrome subscribes to [snackbars] for transient
 * notes and to [banners] for the persistent header row.
 *
 * Why an `AppScope` singleton: emissions originate from work that outlives any single screen — the
 * sync drain, the Firestore listener, the file watcher. Routing those through the active VM would
 * pin error reporting to per-screen lifetimes and lose messages on navigation. The chrome (which
 * spans onboarding + main app) is the only consumer that's always alive when the app is.
 *
 * **Snackbars are events**, replay-0 with [BufferOverflow.DROP_OLDEST] (capacity 8). Late
 * subscribers don't see history; bursts under load drop the oldest rather than blocking the
 * emitter. **Banners are state** keyed by [BannerKey] — re-emitting the same key replaces;
 * [retractBanner] clears once the underlying condition resolves.
 */
interface UserMessageBus {
    val snackbars: SharedFlow<UserMessage.Snackbar>
    val banners: StateFlow<Map<BannerKey, UserMessage.Banner>>

    fun emit(message: UserMessage)

    fun retractBanner(key: BannerKey)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultUserMessageBus : UserMessageBus {
    private val _snackbars =
        MutableSharedFlow<UserMessage.Snackbar>(
            replay = 0,
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val snackbars: SharedFlow<UserMessage.Snackbar> = _snackbars.asSharedFlow()

    private val _banners = MutableStateFlow<Map<BannerKey, UserMessage.Banner>>(emptyMap())
    override val banners: StateFlow<Map<BannerKey, UserMessage.Banner>> = _banners.asStateFlow()

    override fun emit(message: UserMessage) {
        when (message) {
            is UserMessage.Snackbar -> _snackbars.tryEmit(message)
            is UserMessage.Banner -> _banners.update { it + (message.key to message) }
        }
    }

    override fun retractBanner(key: BannerKey) {
        _banners.update { if (key in it) it - key else it }
    }
}
