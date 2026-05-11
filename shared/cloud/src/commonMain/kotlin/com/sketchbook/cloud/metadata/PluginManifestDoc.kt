package com.sketchbook.cloud.metadata

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Wire shape for `/users/{uid}/plugins/{hostId}` — per-host plugin slice. Phase 3 ships the
 * shape; wiring it to the PluginPresenceProbe + the UI's "plugin missing here" surface is a
 * Phase 4 follow-up (design doc §"Out of scope for Phase 3").
 *
 * One doc per host; each entry is a `(name, format, installed)` triple matching what the
 * existing `PluginPresenceProbe` already produces for the JVM filesystem walk.
 */
@Serializable
data class PluginManifestDoc(
    val os: String,
    val computed_at: Instant,
    val plugins: List<PluginEntry> = emptyList(),
) {
    @Serializable
    data class PluginEntry(
        val name: String,
        val format: String,
        val installed: Boolean,
    )
}
