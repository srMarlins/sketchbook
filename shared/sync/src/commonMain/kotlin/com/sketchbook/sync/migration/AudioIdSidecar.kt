package com.sketchbook.sync.migration

import com.sketchbook.core.ProjectUuid

/**
 * Reads/writes the `.audio-id` sidecar that anchors a project's [ProjectUuid] across machines.
 * Implementations are platform-specific because the v1 desktop target uses `java.nio.file`; a
 * future iOS/Android target would use `kotlinx-io` instead.
 *
 * The sidecar is the cross-machine source of truth for a project's identity. If both a sidecar
 * and a DB row exist and disagree, the sidecar wins and the DB is updated to match.
 *
 * File format: a single line containing the UUID, optionally with a trailing newline. We
 * tolerate (and strip) BOMs, leading/trailing whitespace, and an optional `# `-prefixed comment
 * line above the UUID — older hand-edited sidecars used that format.
 */
interface AudioIdSidecar {
    /** Returns the UUID written in `<projectDir>/.audio-id`, or null if missing/unreadable. */
    fun read(projectDir: String): ProjectUuid?

    /**
     * Writes [uuid] to `<projectDir>/.audio-id`, replacing any existing content. Returns true on
     * success, false if the directory doesn't exist or write fails.
     */
    fun write(
        projectDir: String,
        uuid: ProjectUuid,
    ): Boolean

    /** True if `<projectDir>/.audio-id` exists. */
    fun exists(projectDir: String): Boolean
}

/** Filename of the sidecar; constant across platforms. */
const val AUDIO_ID_SIDECAR_NAME: String = ".audio-id"
