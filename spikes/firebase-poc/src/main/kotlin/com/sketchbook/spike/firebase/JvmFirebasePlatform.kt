/*
 * JVM implementation of FirebasePlatform — the storage / logging hook the SDK uses
 * to persist its own state on platforms that don't have Android's SharedPreferences.
 *
 * Backed by an in-memory map for the spike. Phase 2 production code wires this to
 * a real KV store (`KeyringTokenStore` for the auth-side stuff; a small file-backed
 * store for the rest). For the spike, in-memory is fine — process restart loses state,
 * but the spike is a one-shot run anyway.
 *
 * If we end up needing Pattern A1 (storage hijack) for token injection, this is the
 * file we extend: `retrieve(authTokenKey)` returns our Identity-Toolkit-obtained
 * tokens instead of whatever the SDK had stored.
 */
package com.sketchbook.spike.firebase

import com.google.firebase.FirebasePlatform
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class JvmFirebasePlatform(
    /** Optional pre-seeded values. Used by Pattern A1 spike runs to inject tokens before Firebase boots. */
    private val seed: Map<String, String> = emptyMap(),
) : FirebasePlatform() {
    private val store = ConcurrentHashMap<String, String>().apply { putAll(seed) }

    override fun store(
        key: String,
        value: String,
    ) {
        log("store($key, ${value.take(40)}...)")
        store[key] = value
    }

    override fun retrieve(key: String): String? {
        val value = store[key]
        log("retrieve($key) -> ${value?.take(40)}...")
        return value
    }

    override fun clear(key: String) {
        log("clear($key)")
        store.remove(key)
    }

    override fun log(msg: String) {
        // Tagging the source so spike output distinguishes platform-shim chatter from probe output.
        println("[FirebasePlatform] $msg")
    }

    override fun getDatabasePath(name: String): File {
        // Spike: temp dir; Phase 2: app-data dir per OS conventions.
        return File("${System.getProperty("java.io.tmpdir")}${File.separatorChar}sketchbook-spike-fb-$name")
    }
}
