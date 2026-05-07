package com.sketchbook.core

import kotlinx.serialization.Serializable

/**
 * Project-relative path. Always normalized to forward-slash form so manifests are platform-stable.
 *
 * No `java.io.File`, no `okio.Path`, no `kotlinx.io.files.Path` leak: this type is the single
 * representation we serialize across machines. Conversion to/from a platform path is the I/O
 * layer's job (`sync-io`, `app-desktop`).
 *
 * Empty paths are forbidden. Leading slashes are stripped — paths are always relative to a
 * library root or project root (caller decides which).
 */
@JvmInline
@Serializable
value class ProjectPath(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "ProjectPath must not be blank" }
        require('\\' !in value) { "ProjectPath must use forward slashes, got '$value'" }
        require(!value.startsWith('/')) { "ProjectPath must be relative (no leading slash), got '$value'" }
    }

    /** Last segment of the path, e.g. `Samples/Imported/k.wav` → `k.wav`. */
    val name: String
        get() = value.substringAfterLast('/')

    /** Parent path or `null` if this path has no parent (single-segment). */
    val parent: ProjectPath?
        get() {
            val idx = value.lastIndexOf('/')
            return if (idx <= 0) null else ProjectPath(value.substring(0, idx))
        }

    fun child(segment: String): ProjectPath {
        require(segment.isNotEmpty()) { "child segment must not be empty" }
        require('/' !in segment && '\\' !in segment) { "child segment must not contain separators" }
        return ProjectPath("$value/$segment")
    }

    /**
     * Returns this path expressed relative to [other]. Both paths must be in normalized form;
     * [other] must be an ancestor (a strict prefix segment-aligned).
     *
     * `Projects/foo/Samples/k.wav`.relativeTo(`Projects/foo`) → `Samples/k.wav`.
     */
    fun relativeTo(other: ProjectPath): ProjectPath {
        require(value.startsWith(other.value + "/") || value == other.value) {
            "$value is not a descendant of ${other.value}"
        }
        require(value != other.value) { "$value == $other; relative path would be empty" }
        return ProjectPath(value.removePrefix(other.value + "/"))
    }

    companion object {
        /**
         * Build a [ProjectPath] from a platform string. Backslashes are converted to slashes,
         * leading slashes are stripped. Use this at the I/O boundary — never internally.
         */
        fun fromPlatform(raw: String): ProjectPath {
            val forward = raw.replace('\\', '/').trimStart('/')
            return ProjectPath(forward)
        }
    }
}
