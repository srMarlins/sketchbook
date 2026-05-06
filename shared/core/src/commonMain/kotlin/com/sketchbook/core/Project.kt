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
    /** 0..100 effort score per [EffortScore]. Null until the streaming `.als` parser fills
     *  [ProjectMetadata]. */
    val effortScore: Int? = null,
    /** Parser outcome. `Pending` = scanner saw the file but parser hasn't run yet. */
    val parseStatus: ParseStatus = ParseStatus.Pending,
    /** Number of `SampleRef`s the parser couldn't resolve to a real file on disk. */
    val missingSampleCount: Int = 0,
    /** `.als` file size in bytes. Filled by the scanner via `Files.size`; useful as a proxy
     *  signal for the effort score until the streaming parser lands. */
    val fileSizeBytes: Long = 0L,
    val archived: Boolean = false,
    /** Project root key + scale (e.g. "D Minor", "F# Major"); null when the .als has no
     *  ScaleInformation block or the parser hasn't run. Source: [ProjectMetadata.keySignature]. */
    val key: String? = null,
)

enum class ParseStatus { Pending, Ok, Failed }

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
    /**
     * Project root key + scale, derived from `<ScaleInformation>` (e.g. "D Minor", "F# Major").
     * `null` when the project has no key set or only a partial `ScaleInformation` block.
     */
    val keySignature: String? = null,
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
 *
 * Live 11/12 SampleRef metadata fields used during repair. All nullable because:
 *  - older Live (≤10) stores them differently or not at all,
 *  - the parser may encounter malformed/partial entries and we don't want to fail the whole scan.
 *
 * [hasOriginalFileRefSibling] reflects whether the parser saw a
 * `<SampleRef>/<SourceContext>/<SourceContext>/<OriginalFileRef>/<FileRef>` block alongside the
 * primary `<FileRef>`. Live re-derives paths from this sibling under some operations, so any
 * repair must rewrite both copies atomically — see AlsRewriter.
 */
data class SampleRef(
    val rawPath: String,
    val relativePathType: Int? = null,
    val originalFileSize: Long? = null,
    val originalCrc: Long? = null,
    val lastModDate: Long? = null,
    val hasOriginalFileRefSibling: Boolean = false,
)
