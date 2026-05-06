package com.sketchbook.sync

import com.sketchbook.catalog.db.Catalog
import com.sketchbook.cloud.BlobScope
import com.sketchbook.cloud.CloudBackend
import com.sketchbook.core.BlobHash
import com.sketchbook.repo.BlobCacheSettings
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.time.Clock

/**
 * Disk-backed [BlobCache] for the JVM. Blobs live at `<cacheRoot>/<hash[0..1]>/<hash>`; LRU
 * eviction is driven by the catalog's `blob_cache` table (kept in lockstep with disk so a crash
 * mid-write doesn't leave dangling rows).
 *
 * **Eviction strategy.** Run after every successful insert: if total cache bytes exceeds
 * [BlobCacheSettings.maxSizeBytes], walk LRU-then-largest and delete until under budget. Pinned
 * rows are skipped. Honors [BlobCacheSettings.lruEnabled] — when false, never evict.
 *
 * **Per-scope keys.** Path layout includes scope so a blob present in `Shared` doesn't satisfy
 * a `Private` lookup (mirrors cloud semantics). The catalog's `blob_cache.hash` is global, so a
 * row exists for whichever scope wrote first; the path-on-disk is the source of truth for
 * presence.
 */
class JvmBlobCache(
    private val catalog: Catalog,
    private val cacheRoot: Path,
    private val cloud: CloudBackend,
    private val clock: Clock = Clock.System,
    private val cacheSettings: () -> BlobCacheSettings,
) : BlobCache {

    init {
        Files.createDirectories(cacheRoot)
    }

    override suspend fun contains(hash: BlobHash, scope: BlobScope): Boolean {
        return Files.exists(blobPath(hash, scope))
    }

    /**
     * Get the on-disk path for [hash] in [scope], fetching from the cloud on miss. Returns a
     * `java.nio.file.Path` so callers (the JVM materializer) can hardlink/copy directly.
     */
    suspend fun getOrFetch(hash: BlobHash, scope: BlobScope): Path {
        val target = blobPath(hash, scope)
        if (Files.exists(target)) {
            touch(hash)
            return target
        }
        Files.createDirectories(target.parent)
        val tempPath = target.resolveSibling("${target.fileName}.fetch-${System.nanoTime()}")
        val source: RawSource = cloud.getBlob(hash, scope)
        val bytes = source.buffered().readByteArray()
        Files.write(tempPath, bytes)
        try {
            Files.move(tempPath, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            Files.deleteIfExists(tempPath)
            throw e
        }
        recordInsert(hash, bytes.size.toLong())
        runCatching { evictIfOverBudget() }
        return target
    }

    /**
     * Compute the on-disk path for a `(hash, scope)` pair. Uses [BlobHash.hex] (digest only,
     * no `b3:` prefix) — the colon in the wire form isn't a valid Windows path char.
     */
    private fun blobPath(hash: BlobHash, scope: BlobScope): Path {
        val h = hash.hex
        val prefix = if (h.length >= 2) h.substring(0, 2) else "00"
        val scopeDir = when (scope) {
            BlobScope.Shared -> "shared"
            is BlobScope.Private -> "private/${scope.uuid.value}"
        }
        return cacheRoot.resolve(scopeDir).resolve(prefix).resolve(h)
    }

    private fun recordInsert(hash: BlobHash, size: Long) {
        catalog.transaction {
            catalog.catalogQueries.insertBlobCache(
                hash = hash.value,
                size = size,
                last_used = clock.now().toString(),
                pinned = 0L,
            )
        }
    }

    private fun touch(hash: BlobHash) {
        catalog.transaction {
            catalog.catalogQueries.touchBlob(
                last_used = clock.now().toString(),
                hash = hash.value,
            )
        }
    }

    private fun evictIfOverBudget() {
        val settings = cacheSettings()
        if (!settings.lruEnabled) return
        val total = catalog.catalogQueries.sumBlobCacheBytes().executeAsOne()
        if (total <= settings.maxSizeBytes) return
        var remaining = total
        val candidates = catalog.catalogQueries.selectAllBlobsByLruThenSize().executeAsList()
        for (row in candidates) {
            if (remaining <= settings.maxSizeBytes) break
            // The DB stores the canonical wire form (`b3:<hex>`); on-disk uses the hex digest
            // alone since `:` isn't a valid Windows path char.
            val hex = row.hash.removePrefix(BlobHash.PREFIX)
            val prefix = if (hex.length >= 2) hex.substring(0, 2) else "00"
            // Delete the on-disk file across both scopes (we don't track scope in the table; both
            // possible paths are tried — at most one will exist for a given hash today).
            val sharedPath = cacheRoot.resolve("shared").resolve(prefix).resolve(hex)
            Files.deleteIfExists(sharedPath)
            val privateRoot = cacheRoot.resolve("private")
            if (Files.isDirectory(privateRoot)) {
                Files.list(privateRoot).use { stream ->
                    for (uuidDir in stream) {
                        val candidate = uuidDir.resolve(prefix).resolve(hex)
                        Files.deleteIfExists(candidate)
                    }
                }
            }
            catalog.catalogQueries.deleteBlob(row.hash)
            remaining -= row.size
        }
    }
}
