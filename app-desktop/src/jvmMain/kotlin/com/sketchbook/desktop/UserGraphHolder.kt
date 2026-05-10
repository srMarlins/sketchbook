package com.sketchbook.desktop

import com.sketchbook.auth.AuthSession
import com.sketchbook.auth.AuthState
import com.sketchbook.cloud.FirebaseBlobStore
import com.sketchbook.core.AppScope
import com.sketchbook.desktop.auth.OAuthCloudCredentials
import com.sketchbook.repo.SettingsRepository
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the per-user [UserGraph] and rebuilds it on auth/bucket changes. Lives in `AppScope`
 * because the holder itself is application-lifetime; the graph it exposes is `null` whenever
 * the user is signed out OR no `cloudBucket` is configured.
 *
 * **Pivot semantics.** `combine`s [AuthSession.state] with `Settings.cloudBucket`. On
 * `(SignedIn, non-blank bucket)`: builds a fresh [UserGraph] with a [FirebaseBlobStore] keyed on
 * the signed-in user's id and the configured bucket. On any other state: emits `null`. The
 * previous backend's HTTP-client connections are not migrated — in-flight uploads finish on the
 * old instance; the new graph picks up state from the catalog.
 *
 * **Coexists with [com.sketchbook.desktop.repo.SwappableSyncQueue].** The sync queue still owns
 * its own `FirebaseBlobStore` for now; this holder is the new canonical owner of cloud-backend
 * lifetime. A follow-up PR migrates the sync queue to read from this holder.
 *
 * **Shared [HttpClient].** The holder uses the application-scoped `HttpClient` from the graph
 * (one CIO connection pool app-wide). The same client backs OAuth, GCS, and any other network
 * service; only the thin [FirebaseBlobStore] wrapper is rebuilt per `(userId, bucket)` swap.
 *
 * See `docs/architecture/dependency-injection.md` §1.1.
 */
@SingleIn(AppScope::class)
@Inject
class UserGraphHolder(
    private val authSession: AuthSession,
    private val settings: SettingsRepository,
    private val httpClient: HttpClient,
    private val scope: CoroutineScope,
) {
    private val _userGraph = MutableStateFlow<UserGraph?>(null)
    val userGraph: StateFlow<UserGraph?> = _userGraph

    init {
        scope.launch {
            combine(
                authSession.state,
                settings.observe().map { it.cloudBucket }.distinctUntilChanged(),
            ) { auth, bucket -> auth to bucket }
                .collect { (auth, bucket) ->
                    _userGraph.value =
                        if (auth is AuthState.SignedIn && !bucket.isNullOrBlank()) {
                            val backend =
                                FirebaseBlobStore(
                                    http = httpClient,
                                    credentials = OAuthCloudCredentials(authSession),
                                    bucket = bucket,
                                    userId = auth.userId,
                                )
                            UserGraph(cloudBackend = backend)
                        } else {
                            null
                        }
                }
        }
    }
}
