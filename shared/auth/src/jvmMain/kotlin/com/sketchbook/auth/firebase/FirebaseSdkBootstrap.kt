package com.sketchbook.auth.firebase

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.FirebasePlatform
import com.google.firebase.initialize
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pattern A1 (storage hijack) entry point for production. Pre-seeds the gitlive/firebase-java-sdk
 * with externally-obtained Firebase tokens, then runs `Firebase.initialize(...)` so subsequent
 * Firestore listener + Storage RPCs flow through the SDK signed-in as our user.
 *
 * **Lifecycle.** Idempotent across the lifetime of the JVM: [ensureInitialized] is safe to call
 * from any number of consumers; only the first call mints + initializes. Later calls return
 * after re-seeding the platform with fresh tokens (in case sign-in/sign-out replaced the active
 * user). The underlying `Firebase.initialize` and `FirebasePlatform.initializeFirebasePlatform`
 * each throw "already exists" on the second invocation; we wrap those.
 *
 * **Sign-out → sign-in handling.** firebase-java-sdk's `FirebaseAuth` reads the persisted
 * user JSON exactly once — at construction (`FirebaseAuth.kt:248–254`). Re-seeding storage
 * after that point does nothing; the SDK's `currentUser` stays whatever was set at boot.
 * That means just clearing local state on sign-out leaves the gitlive SDK still acting as
 * the previous user, which is a real cross-user leak risk on subsequent listener calls.
 *
 * [clearSession] handles this by (1) calling gitlive's `Firebase.auth.signOut()` to null
 * the SDK-side currentUser + run its own storage clear, and (2) `FirebaseApp.delete()`-ing
 * the underlying app so the *next* `ensureInitialized` re-runs `Firebase.initialize` with
 * fresh tokens and the new user shows up as `currentUser` from the very first RPC.
 * `FirebaseAuthSession.signOut` calls `clearSession` after its local-state clear.
 *
 * @property authSession source of fresh Firebase ID + refresh tokens. Used for the pre-seed
 *   payload; once the SDK is initialized, the SDK manages its own refresh cycle independently.
 * @property config picked up so the SDK initializes against the same Firebase project the
 *   rest of the app talks to (Web API key + project ID + storage bucket).
 */
class FirebaseSdkBootstrap(
    private val authSession: FirebaseAuthSession,
    private val config: FirebaseConfig,
) {
    private val mutex = Mutex()
    private var platform: JvmFirebasePlatform? = null

    @Volatile
    private var initialized: Boolean = false

    /**
     * Cache the (uid, expiresAt) pair that's currently materialized in [platform]'s storage
     * so the fast path can short-circuit when subsequent [ensureInitialized] callers haven't
     * actually rotated tokens (M6). Without this every Firestore RPC re-encodes the
     * FirebaseUserImpl JSON + writes it to the platform map, costing a JSON serialization
     * + map write per call. Cleared in [clearSession].
     */
    @Volatile
    private var lastSeededUid: String? = null

    @Volatile
    private var lastSeededExpiresAtMillis: Long = 0L

    /**
     * Mint a fresh token bundle, seed the platform with the FirebaseUserImpl JSON, and run
     * `Firebase.initialize` exactly once. After this returns, gitlive's `Firebase.firestore`
     * and `Firebase.auth` reach a signed-in instance.
     *
     * Throws [com.sketchbook.auth.AuthSessionExpired] if the AuthSession can't mint tokens.
     */
    suspend fun ensureInitialized() {
        // Fast path: already initialized AND the seeded (uid, expiresAt) still matches the
        // current AuthSession tokens. The cache short-circuits a JSON re-encode + map write
        // per RPC; without it every Firestore call paid that cost (M6). When tokens rotate
        // (refresh, sign-in-as-different-user) we fall through to re-seed.
        if (initialized && platform != null) {
            val tokens = authSession.currentTokens()
            val expMillis = tokens.expiresAt.toEpochMilliseconds()
            if (tokens.uid == lastSeededUid && expMillis == lastSeededExpiresAtMillis) return
            platform?.store(FIREBASE_USER_STORAGE_KEY, AuthStateInjector.firebaseUserImplJson(tokens))
            lastSeededUid = tokens.uid
            lastSeededExpiresAtMillis = expMillis
            return
        }
        mutex.withLock {
            if (initialized) {
                val tokens = authSession.currentTokens()
                val expMillis = tokens.expiresAt.toEpochMilliseconds()
                if (tokens.uid == lastSeededUid && expMillis == lastSeededExpiresAtMillis) {
                    return@withLock
                }
                platform?.store(FIREBASE_USER_STORAGE_KEY, AuthStateInjector.firebaseUserImplJson(tokens))
                lastSeededUid = tokens.uid
                lastSeededExpiresAtMillis = expMillis
                return@withLock
            }
            val tokens = authSession.currentTokens()
            val userJson = AuthStateInjector.firebaseUserImplJson(tokens)
            val platformImpl = JvmFirebasePlatform(seed = mapOf(FIREBASE_USER_STORAGE_KEY to userJson))
            FirebasePlatform.initializeFirebasePlatform(platformImpl)
            this.platform = platformImpl
            lastSeededUid = tokens.uid
            lastSeededExpiresAtMillis = tokens.expiresAt.toEpochMilliseconds()

            val options =
                FirebaseOptions
                    .Builder()
                    .setApiKey(config.webApiKey)
                    .setProjectId(config.projectId)
                    // The applicationId field is required by FirebaseOptions but only used by
                    // products we don't ship on JVM (App Indexing, Crashlytics). Synthesizing a
                    // value that includes the project ID keeps the value debuggable in logs
                    // without baking yet another constant into our config.
                    .setApplicationId("1:0:jvm:${config.projectId}")
                    .setStorageBucket(config.storageBucket)
                    .build()
            // `com.google.firebase.Firebase.initialize(Application(), options)` lives in
            // firebase-java-sdk; gitlive's wrapper is reached via `dev.gitlive.firebase.Firebase`
            // (separate companion object on a separate class). We bootstrap the underlying SDK
            // here; consumers in :shared:cloud talk through the gitlive surface only.
            runCatching<Unit> {
                Firebase.initialize(Application(), options)
            }.onFailure { e ->
                // "already exists" means another process-local caller raced us through Firebase's
                // own init. That's fine — our platform impl is the active one (we registered it
                // above) and the SDK is reachable. Any other failure is a real init bug.
                if (e.message?.contains("already exists", ignoreCase = true) != true) throw e
            }
            // Diagnostic: confirm Pattern A1 seeded the user correctly. Printed to stderr so
            // it surfaces in live-integration runs without polluting production logs. Remove
            // once the auth path is fully stable.
            val uid =
                runCatching {
                    com.google.firebase.auth.FirebaseAuth
                        .getInstance()
                        .currentUser
                        ?.uid
                }.getOrNull()
            System.err.println("[FirebaseSdkBootstrap] after init: currentUser.uid=$uid (seeded uid=${tokens.uid})")
            initialized = true
        }
    }

    /**
     * Tear down the gitlive/firebase-java-sdk session so the next [ensureInitialized] can
     * rebuild for a fresh user (different UID, or same user signing in again after sign-out).
     * Idempotent — calling twice in a row is a no-op after the first.
     *
     * Three steps:
     *   1. `Firebase.auth.signOut()` → nulls SDK-side currentUser + runs the SDK's own
     *      storage clear path. Without this, Firestore RPCs continue carrying the old
     *      user's bearer.
     *   2. `FirebaseApp.delete()` → tears down the FirebaseApp itself. Required because
     *      `FirebaseAuth` reads its persisted user only at construction; the next user's
     *      tokens only become `currentUser` after a fresh `Firebase.initialize` pass.
     *   3. Reset our own `initialized` + `platform` so the next `ensureInitialized`
     *      reseeds and re-initializes from a clean slate.
     */
    suspend fun clearSession() {
        mutex.withLock {
            if (!initialized) return@withLock
            // Explicitly use the gitlive Firebase companion — `Firebase` (unqualified) in
            // this file is `com.google.firebase.Firebase`, whose `auth` extension is
            // firebase-java-sdk's, not gitlive's. gitlive's auth is what we initialized.
            runCatching {
                dev.gitlive.firebase.Firebase.auth
                    .signOut()
            }
            runCatching {
                // Default app — Sketchbook only ever initializes one. delete() blocks until
                // background callbacks drain; safe to call from a suspend context.
                FirebaseApp.getInstance().delete()
            }
            platform?.clear(FIREBASE_USER_STORAGE_KEY)
            platform = null
            initialized = false
            lastSeededUid = null
            lastSeededExpiresAtMillis = 0L
        }
    }
}
