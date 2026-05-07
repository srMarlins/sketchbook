package com.sketchbook.auth

import com.sketchbook.core.UserId
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.http.Parameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Production [AuthSession] backed by [OAuthFlow] (real impl: [OAuthClient]) + [TokenStore].
 *
 * On construction, optionally schedules a background restore: if a [scope] is supplied AND the
 * keystore holds a refresh token, mint an access token. Note that this does NOT emit
 * [AuthState.SignedIn] in v1 because we don't re-fetch userinfo here — `DesktopAuthSession`
 * (Task 8) wraps this with a [PrefsIdentityStore] that holds the cached email + sub.
 *
 * The [email] and [userId] constructor params are placeholder seams for a future variant that
 * accepts an identity hint without going through the wrapper. They are unused in v1.
 */
class GoogleAuthSession(
    private val tokenStore: TokenStore,
    val oauthClient: OAuthFlow,
    private val httpClient: HttpClient = HttpClient(),
    private val clock: Clock = Clock.System,
    private val scope: CoroutineScope? = null,
    @Suppress("UNUSED_PARAMETER") email: String? = null,
    @Suppress("UNUSED_PARAMETER") userId: UserId? = null,
) : AuthSession {
    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var cachedAccessToken: String? = null
    private var cachedAccessTokenExpiry: Instant = Instant.DISTANT_PAST
    private var cachedRefreshToken: String? = null

    init {
        // Background restore from keystore if scope is provided. In tests we usually skip this
        // by passing scope = null and exercising signIn() explicitly.
        scope?.launch { tryRestore() }
    }

    override suspend fun signIn(): Result<AuthState.SignedIn> =
        mutex.withLock {
            val outcome = oauthClient.signIn()
            outcome.map { tokens ->
                tokenStore.write(tokens.refreshToken)
                cachedRefreshToken = tokens.refreshToken
                cachedAccessToken = tokens.accessToken
                cachedAccessTokenExpiry = clock.now().plus(tokens.expiresInSeconds.seconds).minus(60.seconds)
                val signedIn = AuthState.SignedIn(userId = tokens.userId, email = tokens.email)
                _state.value = signedIn
                signedIn
            }
        }

    override suspend fun signOut() =
        mutex.withLock {
            val rt = cachedRefreshToken
            if (rt != null) {
                // Best-effort revoke. Never fail the call on revoke errors.
                runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.submitForm(
                            url = "https://oauth2.googleapis.com/revoke",
                            formParameters = Parameters.build { append("token", rt) },
                        )
                    }
                }
            }
            tokenStore.clear()
            cachedAccessToken = null
            cachedRefreshToken = null
            cachedAccessTokenExpiry = Instant.DISTANT_PAST
            _state.value = AuthState.SignedOut
        }

    override suspend fun accessToken(): String =
        mutex.withLock {
            val now = clock.now()
            val cached = cachedAccessToken
            if (cached != null && now < cachedAccessTokenExpiry) {
                return@withLock cached
            }
            val rt =
                cachedRefreshToken
                    ?: tokenStore.read()
                    ?: run {
                        _state.value = AuthState.SignedOut
                        throw AuthSessionExpired()
                    }
            when (val r = oauthClient.refresh(rt)) {
                is OAuthClient.RefreshResult.Ok -> {
                    cachedAccessToken = r.accessToken
                    cachedAccessTokenExpiry = clock.now().plus(r.expiresInSeconds.seconds).minus(60.seconds)
                    cachedRefreshToken = rt
                    r.accessToken
                }

                is OAuthClient.RefreshResult.Invalid -> {
                    tokenStore.clear()
                    cachedRefreshToken = null
                    cachedAccessToken = null
                    _state.value = AuthState.SignedOut
                    throw AuthSessionExpired()
                }
            }
        }

    /** Test helper — drops the cached access token so the next [accessToken] call refreshes. */
    internal fun expireForTest() {
        cachedAccessTokenExpiry = Instant.DISTANT_PAST
        cachedAccessToken = null
    }

    private suspend fun tryRestore() {
        val rt = tokenStore.read() ?: return
        when (val r = oauthClient.refresh(rt)) {
            is OAuthClient.RefreshResult.Ok -> {
                cachedRefreshToken = rt
                cachedAccessToken = r.accessToken
                cachedAccessTokenExpiry = clock.now().plus(r.expiresInSeconds.seconds).minus(60.seconds)
                // We don't re-fetch userinfo — the caller (DesktopAppGraph) provides the cached
                // identity. If we don't have one, leave SignedOut and let the user re-sign-in.
            }

            is OAuthClient.RefreshResult.Invalid -> {
                tokenStore.clear()
            }
        }
    }
}
