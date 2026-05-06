package com.sketchbook.integration.fakes

import com.sketchbook.core.BlobHash
import com.sketchbook.sync.FileStat
import com.sketchbook.sync.WorkingTree
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.write
import kotlin.time.Instant

/**
 * In-memory [WorkingTree]. Tests build a tree of paths → bytes and a hash function that returns
 * the deterministic SHA-equivalent for the bytes (we just use a fake `b3:` prefix + hex of length
 * matching the real digest format).
 */
class FakeWorkingTree(
    private val files: Map<String, FileBlob>,
) : WorkingTree {

    data class FileBlob(val bytes: ByteArray, val mtime: Instant) {
        val size: Long get() = bytes.size.toLong()
        val hash: BlobHash by lazy { hashOf(bytes) }
    }

    override fun list(): List<String> = files.keys.sorted()

    override fun stat(relativePath: String): FileStat {
        val f = files[relativePath] ?: error("missing $relativePath")
        return FileStat(size = f.size, mtime = f.mtime)
    }

    override fun read(relativePath: String): RawSource {
        val f = files[relativePath] ?: error("missing $relativePath")
        return Buffer().also { it.write(f.bytes) }
    }

    override fun hash(relativePath: String): BlobHash = files[relativePath]?.hash ?: error("missing $relativePath")

    companion object {
        fun hashOf(bytes: ByteArray): BlobHash {
            // Deterministic, not real BLAKE3. Length matches the 64-hex requirement.
            val digest = ByteArray(32)
            for (i in bytes.indices) digest[i % digest.size] = (digest[i % digest.size] + bytes[i]).toByte()
            return BlobHash(BlobHash.PREFIX + digest.toHex())
        }

        private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
