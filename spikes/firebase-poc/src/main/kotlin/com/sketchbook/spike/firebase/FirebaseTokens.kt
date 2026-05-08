/*
 * Token data classes for the Firebase migration spike.
 *
 * Designed to lift cleanly into `shared/auth` in Phase 2 of the migration —
 * not spike-specific in shape, just spike-specific in packaging.
 */
package com.sketchbook.spike.firebase

import kotlin.time.Instant

/**
 * Result of [IdentityToolkitClient.signInWithGoogleIdToken] or [IdentityToolkitClient.refresh].
 *
 * **`idToken`** — the Firebase Auth ID token. Bearer for Firestore + Storage REST. Default 1h TTL.
 * **`refreshToken`** — long-lived; persists in OS keyring. Used to mint new ID tokens via
 *  [IdentityToolkitClient.refresh] when the current one nears expiry.
 * **`uid`** — the Firebase Auth UID. Stable per Google `sub` (i.e., per Google account); does NOT
 *  change across sign-ins. This is what Security Rules check via `request.auth.uid`.
 * **`expiresAt`** — wall-clock instant when the ID token stops being valid. Refresh ~60s before.
 * **`email`** — convenience copy of the user's email; null on anonymous auth.
 */
data class FirebaseTokens(
    val idToken: String,
    val refreshToken: String,
    val uid: String,
    val expiresAt: Instant,
    val email: String?,
)

/**
 * Result of [GoogleIdTokenVerifier.verify]. Subset of the JWT claims we care about
 * for the [IdentityToolkitClient] hand-off — full claim payload is intentionally not
 * surfaced because callers should prefer the verified Firebase token going forward.
 */
data class VerifiedGoogleIdToken(
    val sub: String,
    val email: String,
    val emailVerified: Boolean,
    val issuer: String,
    val audience: String,
    val expiresAt: Instant,
)
