package com.sketchbook.core

import kotlin.time.Instant

/**
 * A project at rest: the row catalog/UI hands around. No history, no manifest detail —
 * just enough to render a list/grid item or open a detail screen.
 */
data class ProjectRow(
    val id: ProjectId,
    val name: String,
    val path: ProjectPath,
    val tempo: Double?,
    val trackCount: Int,
    val lastSavedLiveVersion: String?,
    val updatedAt: Instant,
    val tags: List<String>,
    val colorTag: Int?,
)

/**
 * Full project view. Adds metadata that a detail screen wants but a list doesn't.
 */
data class Project(
    val row: ProjectRow,
    val metadata: ProjectMetadata,
)

/**
 * Per-project metadata extracted by the parser. v0.1 minimum set per design doc §3.2.
 */
data class ProjectMetadata(
    val tempo: Double?,
    val timeSignatureNumerator: Int?,
    val timeSignatureDenominator: Int?,
    val audioTrackCount: Int,
    val midiTrackCount: Int,
    val returnTrackCount: Int,
    val groupTrackCount: Int,
    val plugins: List<PluginRef>,
    val sampleRefs: List<SampleRef>,
    val lastSavedLiveVersion: String?,
    /** Count of `FileRef/Path` values starting with a Mac-only prefix (`/Volumes/`, `/Users/`, etc.). */
    val macPathsCount: Int,
) {
    val totalTrackCount: Int get() = audioTrackCount + midiTrackCount + returnTrackCount + groupTrackCount
}

data class PluginRef(
    val name: String,
    val format: PluginFormat,
    val trackName: String?,
)

enum class PluginFormat { Vst2, Vst3, Au, AbletonNative, Unknown }

/**
 * Sample reference recovered from a `.als` file. The [rawPath] is whatever Live wrote — relative,
 * absolute, Mac-style, etc. Resolution to a real file (and size/exists) happens later.
 */
data class SampleRef(
    val rawPath: String,
)
