package com.sketchbook.syncio

import com.sketchbook.core.BlobHash
import io.github.rctcwyvrn.blake3.Blake3
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * BLAKE3 hasher producing [BlobHash]es with the design-doc-mandated `b3:` prefix + 64 hex
 * chars. Streams the input in 64 KB chunks so files of any size hash with a constant memory
 * footprint.
 *
 * **Native lib guard:** [available] returns false if the BLAKE3 library can't load on this
 * platform (rare — pure-Java backend, but futureproof). Callers can fall back or bail.
 */
object Hasher {
    private const val BUFFER_SIZE = 64 * 1024

    val available: Boolean by lazy {
        runCatching { Blake3.newInstance() }.isSuccess
    }

    fun hash(path: Path): BlobHash {
        Files.newInputStream(path).use { stream ->
            return hash(stream)
        }
    }

    fun hash(stream: InputStream): BlobHash {
        val hasher = Blake3.newInstance()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            // Blake3.update takes the full array, so a partial read forces a copy. The full-buffer
            // case dominates a 543 MB scan (8,500 chunks); skipping the copy there avoids
            // 543 MB of throwaway garbage per file.
            if (read == buffer.size) hasher.update(buffer) else hasher.update(buffer.copyOf(read))
        }
        // 32 bytes = 64 hex chars.
        return BlobHash("b3:" + hasher.hexdigest())
    }

    fun hash(bytes: ByteArray): BlobHash {
        val hasher = Blake3.newInstance()
        hasher.update(bytes)
        return BlobHash("b3:" + hasher.hexdigest())
    }
}
