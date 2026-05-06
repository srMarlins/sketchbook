package com.sketchbook.sync

import com.sketchbook.cloud.BlobScope
import com.sketchbook.core.BlobHash

/**
 * Local content-addressed blob cache. Lookups are by `(hash, scope)` pair — a blob present in
 * `BlobScope.Shared` does NOT count as present in a `BlobScope.Private(uuid)` pool, mirroring
 * the cloud semantics in [com.sketchbook.cloud.CloudBackend].
 *
 * On miss, the implementation fetches from the cloud, writes the bytes to a stable on-disk
 * location, and returns a path. The path is what callers materialize *from* (typically with
 * a hardlink into the project tree); the cache owns eviction.
 *
 * **Path-typed return.** Returning a `kotlinx.io.files.Path` would be the portable choice but
 * the only consumer today is the JVM Materializer, which needs `java.nio.file.Path` for atomic
 * moves and hardlinks. We expose a single JVM-typed entry point on the JVM impl
 * (`JvmBlobCache.getOrFetch`) and keep this commonMain interface narrow.
 */
interface BlobCache {

    /**
     * Returns true iff [hash] is currently present in the cache for [scope]. Cheap — no I/O
     * beyond an SQLite SELECT. Used by tests + LRU bookkeeping.
     */
    suspend fun contains(hash: BlobHash, scope: BlobScope): Boolean
}
