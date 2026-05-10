package com.sketchbook.auth.firebase

import com.google.firebase.FirebasePlatform
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * JVM implementation of the [FirebasePlatform] hook that firebase-java-sdk uses for its tiny
 * key/value scratchpad (replacing Android's SharedPreferences on platforms where that doesn't
 * exist). The SDK persists exactly one piece of auth state through this interface — the
 * serialized current user, under the key `com.google.firebase.auth.FIREBASE_USER` (see
 * [FIREBASE_USER_STORAGE_KEY]).
 *
 * Backed by an in-memory [ConcurrentHashMap]. We deliberately do NOT mirror writes to disk:
 *
 *  1. The durable copy of the refresh token already lives in `KeyringTokenStore`. Persisting
 *     it twice would create two places that need to stay coherent on sign-out, and the
 *     keyring write is the canonical one.
 *  2. On every JVM start, [AuthStateInjector.preSeed] re-injects fresh tokens before the SDK
 *     reads back state. There's nothing the SDK could persist between runs that we'd want to
 *     trust over our own restore path.
 *  3. The other keys the SDK writes under (database paths, installation IDs) are derivable on
 *     each launch and don't benefit from persistence.
 *
 * The [seed] map is read at construction time and merged into storage — the Pattern A1 entry
 * point that hands us tokens BEFORE `Firebase.initialize(...)` is called. Subsequent SDK
 * writes (token refresh, etc.) flow through [store] and overwrite the seeded values.
 */
internal class JvmFirebasePlatform(
    /**
     * Optional pre-seeded values. The Pattern A1 storage-hijack passes a single entry under
     * [FIREBASE_USER_STORAGE_KEY] so the SDK reads us as already-signed-in at boot.
     */
    seed: Map<String, String> = emptyMap(),
) : FirebasePlatform() {
    private val store: ConcurrentHashMap<String, String> = ConcurrentHashMap<String, String>().apply { putAll(seed) }

    override fun store(
        key: String,
        value: String,
    ) {
        store[key] = value
    }

    override fun retrieve(key: String): String? = store[key]

    override fun clear(key: String) {
        store.remove(key)
    }

    override fun log(msg: String) {
        // firebase-java-sdk logs noisily at INFO; surface to stderr only when our verbose
        // env flag is set. The cloud module's structured logger isn't reachable from here
        // (auth must not depend on cloud).
        if (VERBOSE) {
            System.err.println("[FirebasePlatform] $msg")
        }
    }

    override fun getDatabasePath(name: String): File {
        // firestore-on-jvm has no client-side persistence (design doc §"Firestore on JVM
        // works, but with no client-side persistence"), so the SDK only asks for this when
        // some non-Firestore product wires up. Returning a temp-dir path is safe — the SDK
        // creates the directory if it writes there, and on next launch we don't depend on
        // anything that lived inside it.
        return File(System.getProperty("java.io.tmpdir"), "sketchbook-firebase-$name")
    }

    companion object {
        /** Toggle verbose platform logging via `-Dsketchbook.firebase.verbose=true`. Off by default. */
        private val VERBOSE: Boolean = System.getProperty("sketchbook.firebase.verbose")?.toBoolean() == true
    }
}
