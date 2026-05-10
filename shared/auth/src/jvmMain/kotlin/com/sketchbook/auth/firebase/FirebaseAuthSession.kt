package com.sketchbook.auth.firebase

import com.sketchbook.auth.AuthSession
import com.sketchbook.auth.AuthSessionExpired
import com.sketchbook.auth.AuthState
import com.sketchbook.auth.OAuthFlow
import com.sketchbook.auth.TokenStore
import com.sketchbook.core.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Production [AuthSession] for the Firebase-backed cloud. End-to-end:
 *
 *   1. [signIn] runs the existing Google OAuth loopback via [oauthClient] (browser, PKCE,
 *      token endpoint). This is unchanged user experience.
 *   2. The Google ID token returned by step 1 is verified client-side via
 *      [googleIdTokenVerifier] — JWKS RS256 + issuer + audience + expiry (security-commitment
 *      #1). Identity Toolkit verifies again on its end; doing it locally first means we never
 *      ship a tampered claim across the network.
 *   3. The verified Google ID token is traded to Identity Toolkit's `signInWithIdp` for a
 *      Firebase ID token + refresh token via [identityToolkit].
 *   4. The **Firebase** refresh token is persisted in [tokenStore]. The Google refresh token
 *      from step 1 is discarded after exchange — Firebase Auth becomes the long-lived session
 *      anchor; Google's role ends after consent.
 *
 * [accessToken] returns the Firebase ID token (default 1h TTL), refreshing via
 * `securetoken.googleapis.com` ~60s before expiry. The returned token is what Firestore +
 * Storage Security Rules check via `request.auth.uid`.
 *
 * Sign-out clears local state. Refresh-token revoke (security-commitment #3) wires in
 * Step 4.
 */
class FirebaseAuthSession(
    private val tokenStore: TokenStore,
    private val oauthClient: OAuthFlow,
    private val identityToolkit: IdentityToolkitClient,
    private val googleIdTokenVerifier: GoogleIdTokenVerifier,
    private val clock: Clock = Clock.System,
    private val scope: CoroutineScope? = null,
) : AuthSession {
    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var cachedIdToken: String? = null
    private var cachedExpiry: Instant = Instant.DISTANT_PAST
    private var cachedRefreshToken: String? = null
    private var cachedUid: String? = null

    init {
        scope?.launch { tryRestore() }
    }

    override suspend fun signIn(): Result<AuthState.SignedIn> =
        mutex.withLock {
            val googleTokens = oauthClient.signIn().getOrElse { return@withLock Result.failure(it) }

            // Security-commitment #1: verify the Google ID token's signature + claims before
            // we hand it to Identity Toolkit.
            googleIdTokenVerifier.verify(googleTokens.idToken).getOrElse {
                return@withLock Result.failure(it)
            }

            val firebaseTokens =
                identityToolkit.signInWithGoogleIdToken(googleTokens.idToken).getOrElse {
                    return@withLock Result.failure(it)
                }

            tokenStore.write(firebaseTokens.refreshToken)
            cacheTokens(firebaseTokens)
            val signedIn =
                AuthState.SignedIn(
                    userId = UserId(firebaseTokens.uid),
                    email = firebaseTokens.email ?: googleTokens.email,
                )
            _state.value = signedIn
            Result.success(signedIn)
        }

    override suspend fun signOut() =
        mutex.withLock {
            // Security-commitment #3 (refresh-token revoke) is implemented in two parts:
            //
            //   Part A — Phase 2 (this step): clear local state. The in-memory cache and
            //   keyring entry go before _state flips, so a concurrent reader holding the
            //   FirebaseAuthSession mutex contender position can't observe stale tokens.
            //
            //   Part B — Phase 3 (deferred): server-side revoke via a Cloud Function calling
            //   `auth.revokeRefreshTokens(uid)`. Identity Toolkit has no public client-facing
            //   revoke endpoint — refresh tokens are only invalidatable via Admin SDK. The
            //   function lands alongside the MetadataStore work in Phase 3, since both need
            //   the same Cloud Functions deployment.
            //
            // The Phase-2 cut is acceptable for current shipping state (no users yet); Phase
            // 3 closes the race window between sign-out and keyring deletion that the design
            // doc cites. See docs/plans/2026-05-08-firebase-migration-design.md → Security
            // commitments #3.
            tokenStore.clear()
            cachedIdToken = null
            cachedRefreshToken = null
            cachedUid = null
            cachedExpiry = Instant.DISTANT_PAST
            _state.value = AuthState.SignedOut
        }

    override suspend fun accessToken(): String =
        mutex.withLock {
            val now = clock.now()
            val cached = cachedIdToken
            if (cached != null && now < cachedExpiry) {
                return@withLock cached
            }
            val rt =
                cachedRefreshToken
                    ?: tokenStore.read()
                    ?: run {
                        _state.value = AuthState.SignedOut
                        throw AuthSessionExpired()
                    }
            val refreshed =
                identityToolkit.refresh(rt).getOrElse {
                    tokenStore.clear()
                    cachedRefreshToken = null
                    cachedIdToken = null
                    _state.value = AuthState.SignedOut
                    throw AuthSessionExpired()
                }
            cacheTokens(refreshed)
            // The secure-token endpoint may rotate the refresh token. Persist the new one.
            if (refreshed.refreshToken != rt) {
                tokenStore.write(refreshed.refreshToken)
            }
            refreshed.idToken
        }

    /** Test helper — drops the cached ID token so the next [accessToken] call refreshes. */
    internal fun expireForTest() {
        cachedExpiry = Instant.DISTANT_PAST
        cachedIdToken = null
    }

    private fun cacheTokens(tokens: FirebaseTokens) {
        cachedIdToken = tokens.idToken
        // 60s of slack so a request issued right at the boundary doesn't fire with a
        // just-expired token.
        cachedExpiry = tokens.expiresAt - 60.seconds
        cachedRefreshToken = tokens.refreshToken
        cachedUid = tokens.uid
    }

    private suspend fun tryRestore() {
        val rt = tokenStore.read() ?: return
        val refreshed =
            identityToolkit.refresh(rt).getOrElse {
                tokenStore.clear()
                return
            }
        cacheTokens(refreshed)
        if (refreshed.refreshToken != rt) {
            tokenStore.write(refreshed.refreshToken)
        }
        // Don't flip state to SignedIn — DesktopAuthSession owns the cached identity (email)
        // and decides when to emit. Same convention as GoogleAuthSession.
    }
}
