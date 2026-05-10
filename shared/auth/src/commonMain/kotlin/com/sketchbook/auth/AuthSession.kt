package com.sketchbook.auth

import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the signed-in user lifecycle. [state] is the single source of truth — UI binds to it,
 * `UserGraphHolder` builds/tears down the user graph from it.
 *
 * Implementations must:
 *  - Read any persisted refresh token at construction and emit `SignedIn` if a fresh access
 *    token can be minted, without opening a browser.
 *  - Provide [idToken] for cloud backends. Caches and refreshes transparently. Surfaces
 *    refresh failure by flipping [state] to `SignedOut` AND throwing — the caller (e.g.
 *    `FirebaseCloudCredentials`) decides how to surface the failure to the user.
 */
interface AuthSession {
    val state: StateFlow<AuthState>

    /**
     * Launch the OAuth flow. Implementations open the system browser, run a loopback server, and
     * suspend until the user completes consent (or denies / times out).
     */
    suspend fun signIn(): Result<AuthState.SignedIn>

    /**
     * Revoke + clear the persisted refresh token. After this returns, [state] is `SignedOut`.
     * Best-effort revoke against the IdP — never fails the call on revoke errors.
     */
    suspend fun signOut()

    /**
     * Mint or return a cached Firebase ID token. This is the bearer for every downstream cloud
     * call (Firestore listeners, Firebase Storage uploads). Throws [AuthSessionExpired] if
     * refresh fails; in that case [state] has already flipped to `SignedOut`.
     */
    suspend fun idToken(): String
}
