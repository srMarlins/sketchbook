package com.sketchbook.core

import kotlinx.serialization.Serializable

/**
 * Local catalog primary key. Stable for the lifetime of a single machine's `catalog.db`.
 * Use [ProjectUuid] for cross-machine identity (manifests, snapshots, sync).
 */
@JvmInline
@Serializable
value class ProjectId(val value: Long) {
    init {
        require(value > 0) { "ProjectId must be positive, got $value" }
    }
}

/**
 * Cross-machine project identity. ULID-shaped string written into `.audio-id` sidecars and
 * referenced in cloud manifests / snapshots. Constant across renames, moves, and machines.
 */
@JvmInline
@Serializable
value class ProjectUuid(val value: String) {
    init {
        require(value.isNotBlank()) { "ProjectUuid must not be blank" }
        // Tenants assemble cloud blob keys as "<userId>/blobs/.../<projectUuid>/...". A "/" or
        // ".." inside an id would let a poisoned uuid escape its prefix. Constrain to the safe
        // ULID-shaped charset (alphanumeric + dash); reject anything else at construction.
        require(value.length <= MAX_LEN) { "ProjectUuid too long: ${value.length} > $MAX_LEN" }
        require(value.all { it.isSafeIdChar() }) {
            "ProjectUuid must be alphanumeric or dash, got '$value'"
        }
    }

    companion object {
        const val MAX_LEN: Int = 64
    }
}

/**
 * Per-blob content hash. Always prefixed `b3:` followed by a lower-case hex BLAKE3 digest.
 * Wire-stable: persisted in manifests, journal entries, and the `blob_cache` table.
 */
@JvmInline
@Serializable
value class BlobHash(val value: String) {
    init {
        require(value.startsWith(PREFIX)) { "BlobHash must start with '$PREFIX', got '$value'" }
        val digest = value.removePrefix(PREFIX)
        require(digest.length == DIGEST_HEX_LEN) {
            "BlobHash digest must be $DIGEST_HEX_LEN hex chars, got ${digest.length} in '$value'"
        }
        require(digest.all { it in '0'..'9' || it in 'a'..'f' }) {
            "BlobHash digest must be lower-case hex, got '$digest'"
        }
    }

    val hex: String get() = value.removePrefix(PREFIX)

    companion object {
        const val PREFIX: String = "b3:"

        // BLAKE3 default 32-byte (256-bit) digest = 64 hex chars.
        const val DIGEST_HEX_LEN: Int = 64
    }
}

/**
 * Monotonic per-project snapshot revision. Starts at 0 (unsynced), increments each manifest write.
 */
@JvmInline
@Serializable
value class SnapshotRev(val value: Long) {
    init {
        require(value >= 0) { "SnapshotRev must be non-negative, got $value" }
    }

    operator fun compareTo(other: SnapshotRev): Int = value.compareTo(other.value)
    fun next(): SnapshotRev = SnapshotRev(value + 1)
}

/**
 * Tenant identifier. v1 hardcodes `"default"`; v1.2 multi-user adds real ids.
 */
@JvmInline
@Serializable
value class UserId(val value: String) {
    init {
        require(value.isNotBlank()) { "UserId must not be blank" }
        require(value.length <= MAX_LEN) { "UserId too long: ${value.length} > $MAX_LEN" }
        // Same reasoning as ProjectUuid: this value is concatenated into bucket object keys.
        require(value.all { it.isSafeIdChar() }) {
            "UserId must be alphanumeric or dash, got '$value'"
        }
    }

    companion object {
        const val MAX_LEN: Int = 64
        val DEFAULT: UserId = UserId("default")
    }
}

/** Allowed characters for opaque ids that flow into URL paths. */
private fun Char.isSafeIdChar(): Boolean = this in '0'..'9' || this in 'a'..'z' || this in 'A'..'Z' || this == '-' || this == '_'
