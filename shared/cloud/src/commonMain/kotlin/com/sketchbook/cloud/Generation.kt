package com.sketchbook.cloud

import kotlinx.serialization.Serializable

/**
 * Provider-neutral object generation token. Used as the `expectedHead` argument to conditional
 * writes. Today it wraps Google Cloud Storage's `x-goog-generation` (Long-as-String) but
 * stays opaque so a future R2/B2 backend can fill it with an etag instead.
 *
 * - [ZERO] is the "object must not exist" sentinel; passes as `x-goog-if-generation-match: 0`
 *   to GCS.
 */
@JvmInline
@Serializable
value class Generation(val raw: String) {
    init {
        require(raw.isNotEmpty()) { "Generation must not be empty (use Generation.ZERO for must-not-exist)" }
    }

    companion object {
        val ZERO: Generation = Generation("0")
    }
}
