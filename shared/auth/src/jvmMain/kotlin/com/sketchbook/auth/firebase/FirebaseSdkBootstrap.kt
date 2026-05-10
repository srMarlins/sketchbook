package com.sketchbook.auth.firebase

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.FirebaseOptions
import com.google.firebase.FirebasePlatform
import com.google.firebase.initialize
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
 * **Multi-user note (Phase 3 limitation).** The SDK's process-singleton `FirebasePlatform`
 * survives sign-out → sign-in-as-different-user. We overwrite the seeded user state on each
 * `ensureInitialized` call, but the gitlive `Firebase.auth.currentUser` cache may lag until
 * its own next refresh cycle. UserGraphHolder already rebuilds the cloud adapters on UID
 * transitions, so this lag isn't observable in normal use. Documented in the migration
 * design's "Phase 3 entry findings → Out of scope for Phase 3".
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
     * Mint a fresh token bundle, seed the platform with the FirebaseUserImpl JSON, and run
     * `Firebase.initialize` exactly once. After this returns, gitlive's `Firebase.firestore`
     * and `Firebase.auth` reach a signed-in instance.
     *
     * Throws [com.sketchbook.auth.AuthSessionExpired] if the AuthSession can't mint tokens.
     */
    suspend fun ensureInitialized() {
        // Fast path: already initialized AND the seeded user matches the current AuthSession
        // user. Re-seeding is cheap (a single map.put) but we still avoid it when not needed.
        if (initialized && platform != null) {
            // Always refresh the seed — even if the SDK's own refresh has rotated tokens,
            // a subsequent process restart will read the pre-seed first. Keeping the seed
            // current avoids a one-RPC window of stale-token failures after launch.
            val tokens = authSession.currentTokens()
            platform?.store(FIREBASE_USER_STORAGE_KEY, AuthStateInjector.firebaseUserImplJson(tokens))
            return
        }
        mutex.withLock {
            if (initialized) {
                val tokens = authSession.currentTokens()
                platform?.store(FIREBASE_USER_STORAGE_KEY, AuthStateInjector.firebaseUserImplJson(tokens))
                return@withLock
            }
            val tokens = authSession.currentTokens()
            val userJson = AuthStateInjector.firebaseUserImplJson(tokens)
            val platformImpl = JvmFirebasePlatform(seed = mapOf(FIREBASE_USER_STORAGE_KEY to userJson))
            FirebasePlatform.initializeFirebasePlatform(platformImpl)
            this.platform = platformImpl

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
            initialized = true
        }
    }
}
