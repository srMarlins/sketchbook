package com.sketchbook.auth.firebase

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Pattern A1 (storage hijack) — the load-bearing trick that lets us drive Firestore on JVM
 * via gitlive's wrapper without a working `signInWithCredential(...)` (TODO() on JVM).
 *
 * We obtain a Firebase ID + refresh token out-of-band (Google OAuth → JWKS verify →
 * Identity Toolkit `signInWithIdp`), then PRE-SEED the SDK's tiny key/value scratchpad
 * with a `FirebaseUserImpl`-shaped JSON under [FIREBASE_USER_STORAGE_KEY] BEFORE
 * `Firebase.initialize(...)` is called. The SDK reads its "previously persisted" auth
 * state at boot, sees a valid user, and acts signed-in for every downstream Firestore /
 * Storage RPC.
 *
 * Verified by reading `firebase-java-sdk` 0.6.3 (`FirebaseAuth.kt:248–254` for the
 * retrieve-at-boot path; `FirebaseAuth.kt:241` for the storage-key constant). Spike
 * `pattern-a1` probe confirms end-to-end; commit `9367777` in this branch's history.
 *
 * Refresh-token rotation: once seeded, `FirebaseAuth.getAccessToken()` POSTs the stored
 * refresh token to `securetoken.googleapis.com/v1/token` on its own when the cached ID
 * token nears expiry (FirebaseAuth.kt:549–566 in the SDK). Same endpoint our
 * `IdentityToolkitClient.refresh()` already targets; same refresh-token format.
 * Refresh "just works" after the one-time injection.
 */
internal object AuthStateInjector {
    /**
     * Build the `FirebaseUserImpl`-shaped JSON the SDK reads back at boot. The deserializer
     * accepts a wider field set than the @Serializable one writes — we mirror what the
     * deserializer reads so the format is forward-compatible across SDK minor bumps:
     *
     *   uid           ← "uid" | "user_id" | "localId"
     *   idToken       ← "idToken" | "id_token"
     *   refreshToken  ← "refreshToken" | "refresh_token"
     *   expiresIn     ← "expiresIn" | "expires_in"     (seconds until idToken expiry)
     *   createdAt     ← "createdAt" | System.currentTimeMillis()
     *   email         ← "email"
     *   isAnonymous   ← "isAnonymous" | false
     */
    fun firebaseUserImplJson(
        tokens: FirebaseTokens,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        // SDK refresh logic does (createdAt + expiresIn*1000) - now() < threshold; floor at 60s
        // so a token that just-barely-expired doesn't get used for the one boot-time RPC before
        // the SDK schedules its first refresh.
        val expiresInSeconds: Int =
            ((tokens.expiresAt.toEpochMilliseconds() - nowMillis) / 1000)
                .coerceAtLeast(60)
                .toInt()
        val obj: JsonObject =
            buildJsonObject {
                put("uid", JsonPrimitive(tokens.uid))
                put("idToken", JsonPrimitive(tokens.idToken))
                put("refreshToken", JsonPrimitive(tokens.refreshToken))
                put("expiresIn", JsonPrimitive(expiresInSeconds))
                put("createdAt", JsonPrimitive(nowMillis))
                tokens.email?.let { put("email", JsonPrimitive(it)) }
                put("isAnonymous", JsonPrimitive(false))
            }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }
}

/**
 * Storage key the SDK retrieves the persisted-user JSON under, for the default `FirebaseApp`.
 * Stable Android constant — unchanged since 2019; firebase-java-sdk tracks the Android source
 * verbatim. Non-default app names would suffix `[name]` (see `FirebaseAuth.kt:241`); we only
 * ever use the default app.
 */
internal const val FIREBASE_USER_STORAGE_KEY: String = "com.google.firebase.auth.FIREBASE_USER"
