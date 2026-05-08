package com.sketchbook.desktop.ui.setup

/**
 * Identity needed to publish the per-host plugin slice
 * (`<tenant>/profile/plugin_manifest_<host_id>.json`). Wrapped in a typed value so the graph
 * can `@Provides` it once without colliding with other bindings; the VM consumes it through
 * a single dependency rather than three loose strings.
 *
 * `os` deliberately *isn't* on this object — the [OsProvider] is the single source of truth
 * for that label across the screen + filter + publish path.
 */
data class HostSliceContext(
    val hostId: String,
    val hostName: String,
)
