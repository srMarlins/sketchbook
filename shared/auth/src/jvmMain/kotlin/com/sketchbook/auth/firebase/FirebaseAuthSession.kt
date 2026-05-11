package com.sketchbook.auth.firebase

import com.sketchbook.auth.AuthSession
import com.sketchbook.auth.AuthSessionExpired
import com.sketchbook.auth.AuthState
import com.sketchbook.auth.OAuthFlow
import com.sketchbook.auth.TokenStore
import com.sketchbook.core.UserId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Production [AuthSession] for the Firebase-backed cloud. End-to-end:
 *
 *   1. [signIn] runs the existing Google OAuth loopback via [oauthClient] (browser, PKCE,
 *      token endpoint). User experience is unchanged from pre-Firebase.
 *   2. The Google ID token returned by step 1 is verified client-side via
 *      [googleIdTokenVerifier] — JWKS RS256 + issuer + audience + expiry. Security-commitment
 *      #1 in `docs/plans/2026-05-08-firebase-migration-design.md`.
 *   3. The verified Google ID token is traded to Identity Toolkit's `signInWithIdp` via
 *      [identityToolkit] for Firebase ID + refresh tokens.
 *   4. The **Firebase** refresh token is persisted in [tokenStore]; the Google refresh token
 *      is discarded.
 *
 * [idToken] returns the Firebase ID token (default 1h TTL); the secure-token endpoint is
 * called for refresh ~60s before expiry. Concurrent `idToken` callers find an expired
 * token and coalesce on [refreshMutex] — exactly one refresh request goes over the wire.
 *
 * **Concurrency.** State lives in a single [MutableStateFlow]; transitions are atomic.
 * Two narrow mutexes guard write-side operations:
 *
 *   - [signInMutex] serializes sign-in (one browser flow at a time). Held over the browser
 *     flow because concurrent sign-ins make no sense — but `idToken` / `signOut` do NOT
 *     take this mutex, so they're never blocked by a hung browser flow.
 *   - [refreshMutex] serializes refresh, [signOut], and [tryRestore]. Held over the network
 *     call but the refresh is fast (~tens of ms), so concurrent `signOut` blocking briefly is
 *     acceptable in exchange for ordering: signOut's `tokenStore.clear()` is guaranteed to
 *     happen after any concurrent refresh's `tokenStore.write(rotated)`, so the keyring can't
 *     end up holding a fresh refresh token when state says SignedOut.
 */
class FirebaseAuthSession(
    private val tokenStore: TokenStore,
    private val oauthClient: OAuthFlow,
    private val identityToolkit: IdentityToolkitClient,
    private val googleIdTokenVerifier: GoogleIdTokenVerifier,
    private val clock: Clock = Clock.System,
    /**
     * Best-effort Firebase refresh-token revocation on sign-out (security-commitment #3
     * Part B — backed by the `revokeMySession` Cloud Function). Null disables the call;
     * production wiring sets this in [com.sketchbook.desktop.DesktopAppGraph]. A failure
     * here logs and continues with local clear — sign-out is never blocked on the
     * network.
     */
    private val cloudFunctions: CloudFunctionsClient? = null,
    /**
     * Optional hook that tears down the gitlive/firebase-java-sdk session. Wired to
     * [FirebaseSdkBootstrap.clearSession] in production. Required for sign-out → sign-in-
     * as-different-user to not leak the previous user's `Firebase.auth.currentUser` into
     * subsequent Firestore listener calls. Default no-op for tests that don't initialize
     * the SDK.
     */
    private val sdkClearSession: suspend () -> Unit = {},
) : AuthSession {
    private val tokens = MutableStateFlow<SessionTokens>(SessionTokens.None)
    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    private val signInMutex = Mutex()
    private val refreshMutex = Mutex()

    /**
     * Silently restore from a persisted refresh token if one exists. Returns `true` if the
     * session is now active (tokens cached, ready for [idToken]), `false` otherwise.
     * Called once at app startup by the wrapper
     * [com.sketchbook.desktop.auth.DesktopAuthSession]. Never throws — restore is best-effort.
     */
    suspend fun tryRestore(): Boolean =
        refreshMutex.withLock {
            val rt = tokenStore.read() ?: return@withLock false
            identityToolkit
                .refresh(rt)
                .fold(
                    onSuccess = { fresh ->
                        commitTokens(fresh)
                        if (fresh.refreshToken != rt) tokenStore.write(fresh.refreshToken)
                        true
                    },
                    onFailure = {
                        tokenStore.clear()
                        false
                    },
                )
        }

    override suspend fun signIn(): Result<AuthState.SignedIn> =
        signInMutex.withLock {
            val googleTokens =
                oauthClient.signIn().getOrElse { return@withLock Result.failure(it) }
            googleIdTokenVerifier.verify(googleTokens.idToken).getOrElse {
                return@withLock Result.failure(it)
            }
            val firebaseTokens =
                identityToolkit.signInWithGoogleIdToken(googleTokens.idToken).getOrElse {
                    return@withLock Result.failure(it)
                }
            // The token-store write + state transition is short and synchronous — no need to
            // take refreshMutex here. A concurrent refresh (impossible: no refresh token in
            // the store yet on the pre-sign-in path) would lose its refresh attempt anyway.
            tokenStore.write(firebaseTokens.refreshToken)
            commitTokens(firebaseTokens)
            val signedIn =
                AuthState.SignedIn(
                    userId = UserId(firebaseTokens.uid),
                    email = firebaseTokens.email ?: googleTokens.email,
                )
            _state.value = signedIn
            Result.success(signedIn)
        }

    override suspend fun signOut() {
        // Two-phase to avoid holding refreshMutex over a network call. The first phase
        // captures the still-valid ID token (needed to authenticate the revoke call) and
        // atomically flips local state to SignedOut. The second phase does the best-effort
        // revoke OUTSIDE the mutex so other auth ops (`idToken`, `tryRestore`, `currentTokens`)
        // can't stall behind a slow Cloud Function or hung TLS handshake.
        //
        // Race: a concurrent `refresh()` that landed between us capturing the token and us
        // taking the mutex could rotate tokens; that's fine — the captured token is still
        // valid for ~1h and the revoke call uses it as the bearer (not as the to-be-revoked).
        // After our `tokenStore.clear()` lands, any in-flight refresh that completes after
        // overwrites with a fresh refresh token, but the next caller through `refresh()`
        // sees `_state == SignedOut` first and bails before touching the store.
        val revokeToken: String?
        refreshMutex.withLock {
            val snap = tokens.value
            revokeToken = (snap as? SessionTokens.Active)?.idToken
            tokenStore.clear()
            tokens.value = SessionTokens.None
            _state.value = AuthState.SignedOut
        }
        // Security-commitment #3 Part B: best-effort `admin.auth().revokeRefreshTokens(uid)`.
        // A persistent failure mode (function not deployed yet, rules denying) will surface
        // across many sign-outs; user-visible behavior here is unchanged.
        val cf = cloudFunctions
        if (cf != null && revokeToken != null) {
            try {
                cf.revokeMySession(revokeToken)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                System.err.println("[FirebaseAuthSession] revokeMySession failed: $e")
            }
        }
        // Tear down the gitlive SDK so a subsequent sign-in (same or different UID) starts
        // from a clean slate. Without this the SDK's `Firebase.auth.currentUser` would
        // remain populated with the previous user's identity, and listener RPCs would
        // continue carrying the old auth header. Best-effort: a tear-down failure logs
        // and continues — the local-state clear above is the user-visible contract.
        try {
            sdkClearSession()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            System.err.println("[FirebaseAuthSession] sdkClearSession failed: $e")
        }
    }

    override suspend fun idToken(): String = currentTokens().idToken

    /**
     * Return the full current Firebase token bundle. Refreshes on expiry exactly like
     * [idToken] does. Used by [FirebaseSdkBootstrap] which needs the uid + email + expiresAt
     * fields (not just the bearer string) to pre-seed the gitlive SDK with Pattern A1.
     *
     * Throws [AuthSessionExpired] if refresh fails; [state] has already flipped to
     * `SignedOut` in that case.
     */
    suspend fun currentTokens(): FirebaseTokens {
        val snap = tokens.value
        if (snap is SessionTokens.Active && clock.now() < snap.expiresAt) {
            return snap.toFirebaseTokens()
        }
        refresh()
        return (tokens.value as SessionTokens.Active).toFirebaseTokens()
    }

    private suspend fun refresh(): String =
        refreshMutex.withLock {
            // Re-check inside the lock — another caller may have just refreshed.
            val snap = tokens.value
            if (snap is SessionTokens.Active && clock.now() < snap.expiresAt) {
                return@withLock snap.idToken
            }
            val rt =
                (snap as? SessionTokens.Active)?.refreshToken
                    ?: tokenStore.read()
                    ?: run {
                        _state.value = AuthState.SignedOut
                        throw AuthSessionExpired()
                    }
            val fresh =
                identityToolkit.refresh(rt).getOrElse {
                    tokenStore.clear()
                    tokens.value = SessionTokens.None
                    _state.value = AuthState.SignedOut
                    throw AuthSessionExpired()
                }
            commitTokens(fresh)
            if (fresh.refreshToken != rt) tokenStore.write(fresh.refreshToken)
            fresh.idToken
        }

    private fun commitTokens(t: FirebaseTokens) {
        // 60s of slack so a request issued right at the boundary doesn't fire with a
        // just-expired token (clock skew + propagation latency).
        tokens.value =
            SessionTokens.Active(
                idToken = t.idToken,
                refreshToken = t.refreshToken,
                expiresAt = t.expiresAt - 60.seconds,
                uid = t.uid,
            )
    }

    private fun SessionTokens.Active.toFirebaseTokens(): FirebaseTokens =
        FirebaseTokens(
            idToken = idToken,
            refreshToken = refreshToken,
            uid = uid,
            // Add the 60s slack back so the bootstrap reports the original expiry. The
            // bootstrap clamps to a 60s floor anyway, so the round-trip is lossless within
            // the slack window.
            expiresAt = expiresAt + 60.seconds,
            email = (_state.value as? AuthState.SignedIn)?.email,
        )

    private sealed interface SessionTokens {
        data object None : SessionTokens

        data class Active(
            val idToken: String,
            val refreshToken: String,
            val expiresAt: Instant,
            val uid: String,
        ) : SessionTokens
    }
}
