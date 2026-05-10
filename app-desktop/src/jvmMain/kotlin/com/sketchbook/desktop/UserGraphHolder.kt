package com.sketchbook.desktop

import com.sketchbook.auth.AuthSession
import com.sketchbook.auth.AuthState
import com.sketchbook.auth.firebase.FirebaseConfig
import com.sketchbook.cloud.FirebaseBlobStore
import com.sketchbook.core.AppScope
import com.sketchbook.desktop.auth.OAuthCloudCredentials
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns the per-user [UserGraph] and rebuilds it on auth state changes. Lives in `AppScope`
 * because the holder itself is application-lifetime; the graph it exposes is `null` whenever
 * the user is signed out.
 *
 * **Pivot semantics.** Observes [AuthSession.state]. On `SignedIn`: builds a fresh
 * [UserGraph] with a [FirebaseBlobStore] pointed at the Firebase-managed bucket from
 * [FirebaseConfig.active] and keyed on the signed-in Firebase UID. On `SignedOut`: emits
 * `null`. In-flight uploads on the previous backend finish on the old instance; the new
 * graph picks up state from the catalog.
 *
 * **Coexists with [com.sketchbook.desktop.repo.SwappableSyncQueue].** The sync queue still
 * owns its own [FirebaseBlobStore] for now; this holder is the canonical owner of cloud-
 * backend lifetime. A follow-up PR migrates the sync queue to read from this holder.
 *
 * **Shared [HttpClient].** The holder uses the application-scoped `HttpClient` from the
 * graph (one CIO connection pool app-wide). The same client backs OAuth, GCS, and any other
 * network service; only the thin [FirebaseBlobStore] wrapper is rebuilt per UID swap.
 */
@SingleIn(AppScope::class)
@Inject
class UserGraphHolder(
    private val authSession: AuthSession,
    private val httpClient: HttpClient,
    private val scope: CoroutineScope,
) {
    private val _userGraph = MutableStateFlow<UserGraph?>(null)
    val userGraph: StateFlow<UserGraph?> = _userGraph

    init {
        scope.launch {
            authSession.state.collect { auth ->
                _userGraph.value =
                    if (auth is AuthState.SignedIn) {
                        val backend =
                            FirebaseBlobStore(
                                http = httpClient,
                                credentials = OAuthCloudCredentials(authSession),
                                bucket = FirebaseConfig.active().storageBucket,
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
