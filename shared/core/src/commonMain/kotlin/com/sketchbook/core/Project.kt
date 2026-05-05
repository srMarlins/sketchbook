package com.sketchbook.core

import kotlinx.datetime.Instant

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
    val midiTrackCount: Int,
    val audioTrackCount: Int,
    val returnTrackCount: Int,
    val plugins: List<PluginRef>,
    val sampleRefs: List<SampleRef>,
    val lastSavedLiveVersion: String?,
)

data class PluginRef(
    val name: String,
    val format: PluginFormat,
    val trackName: String?,
)

enum class PluginFormat { Vst2, Vst3, Au, Native, Unknown }

data class SampleRef(
    val path: ProjectPath,
    val exists: Boolean,
    val sizeBytes: Long?,
)
