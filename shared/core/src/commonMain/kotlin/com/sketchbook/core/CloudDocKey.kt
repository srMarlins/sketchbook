package com.sketchbook.core

import kotlin.jvm.JvmInline

/**
 * Path of a [CloudDoc] in the bucket — small structured-JSON objects with CAS but no manifest /
 * blob layer. Use cases: tree registry, per-host machine slices, account settings, future
 * invitations / sharing tokens. See `docs/plans/2026-05-07-backend-generalization-design.md`
 * §"Two cloud primitives".
 *
 * The path is the full object name within the user's tenant prefix — e.g. `registry.json`,
 * `profile/plugin_manifest_<host_id>.json`. The cloud impl prepends the tenant prefix.
 */
@JvmInline
value class CloudDocKey(
    val path: String,
) {
    init {
        require(path.isNotBlank()) { "CloudDocKey path must not be blank" }
        require(!path.startsWith("/")) { "CloudDocKey path must be relative, got '$path'" }
        require(!path.contains("..")) { "CloudDocKey path must not contain '..', got '$path'" }
    }

    /** Path prefix for `listDocs` — matches all keys whose [path] starts with [value]. */
    @JvmInline
    value class Prefix(
        val value: String,
    ) {
        init {
            require(!value.startsWith("/")) { "CloudDocKey.Prefix must be relative, got '$value'" }
        }
    }
}
