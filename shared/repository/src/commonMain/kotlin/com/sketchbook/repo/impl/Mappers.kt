package com.sketchbook.repo.impl

import com.sketchbook.catalog.db.Projects
import com.sketchbook.catalog.db.SelectAllProjectsWithMissing
import com.sketchbook.catalog.db.SelectArchivedProjectsWithMissing
import com.sketchbook.catalog.db.SelectProjectByIdWithMissing
import com.sketchbook.core.ParseStatus
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectPath
import com.sketchbook.core.ProjectRow
import com.sketchbook.core.Stage
import kotlin.time.Instant

/**
 * Map a SQLDelight row to a domain [ProjectRow]. Tags are joined into the row by the caller
 * (a separate query). SQLDelight types never leak past this boundary.
 *
 * `missingSampleCount` defaults to 0 when not supplied — used by the legacy `Projects` row
 * mapping. The `*WithMissing` query overloads supply the count from a correlated subquery
 * against `project_samples`.
 */
internal fun Projects.toDomain(
    tags: List<String> = emptyList(),
    missingSampleCount: Int = 0,
): ProjectRow =
    build(
        id = id,
        name = name,
        path = path,
        tempo = tempo,
        trackCount = track_count,
        liveVersion = live_version,
        lastModifiedSec = last_modified,
        colorTag = color_tag,
        effortScore = effort_score,
        parseStatus = parse_status,
        fileSizeBytes = file_size_bytes,
        tags = tags,
        missingSampleCount = missingSampleCount,
        archived = is_archived != 0L,
        key = key,
        stageInferred = stage_inferred,
        stageOverride = stage_override,
    )

internal fun SelectAllProjectsWithMissing.toDomain(tags: List<String> = emptyList()): ProjectRow =
    build(
        id = id,
        name = name,
        path = path,
        tempo = tempo,
        trackCount = track_count,
        liveVersion = live_version,
        lastModifiedSec = last_modified,
        colorTag = color_tag,
        effortScore = effort_score,
        parseStatus = parse_status,
        fileSizeBytes = file_size_bytes,
        tags = tags,
        missingSampleCount = missing_sample_count.toInt(),
        archived = is_archived != 0L,
        key = key,
        stageInferred = stage_inferred,
        stageOverride = stage_override,
    )

internal fun SelectArchivedProjectsWithMissing.toDomain(tags: List<String> = emptyList()): ProjectRow =
    build(
        id = id,
        name = name,
        path = path,
        tempo = tempo,
        trackCount = track_count,
        liveVersion = live_version,
        lastModifiedSec = last_modified,
        colorTag = color_tag,
        effortScore = effort_score,
        parseStatus = parse_status,
        fileSizeBytes = file_size_bytes,
        tags = tags,
        missingSampleCount = missing_sample_count.toInt(),
        archived = is_archived != 0L,
        key = key,
        stageInferred = stage_inferred,
        stageOverride = stage_override,
    )

internal fun SelectProjectByIdWithMissing.toDomain(tags: List<String> = emptyList()): ProjectRow =
    build(
        id = id,
        name = name,
        path = path,
        tempo = tempo,
        trackCount = track_count,
        liveVersion = live_version,
        lastModifiedSec = last_modified,
        colorTag = color_tag,
        effortScore = effort_score,
        parseStatus = parse_status,
        fileSizeBytes = file_size_bytes,
        tags = tags,
        missingSampleCount = missing_sample_count.toInt(),
        archived = is_archived != 0L,
        key = key,
        stageInferred = stage_inferred,
        stageOverride = stage_override,
    )

@Suppress("LongParameterList")
private fun build(
    id: Long,
    name: String,
    path: String,
    tempo: Double?,
    trackCount: Long,
    liveVersion: String?,
    lastModifiedSec: Double,
    colorTag: Long?,
    effortScore: Long?,
    parseStatus: String?,
    fileSizeBytes: Long?,
    tags: List<String>,
    missingSampleCount: Int,
    archived: Boolean,
    key: String?,
    stageInferred: String?,
    stageOverride: String?,
): ProjectRow =
    ProjectRow(
        id = ProjectId(id),
        name = name,
        path = ProjectPath.fromPlatform(path),
        tempo = tempo,
        trackCount = trackCount.toInt(),
        lastSavedLiveVersion = liveVersion,
        updatedAt = Instant.fromEpochMilliseconds((lastModifiedSec * 1000).toLong()),
        tags = tags,
        colorTag = colorTag?.toInt(),
        effortScore = effortScore?.toInt(),
        parseStatus = parseStatusFor(parseStatus),
        missingSampleCount = missingSampleCount,
        fileSizeBytes = fileSizeBytes ?: 0L,
        archived = archived,
        key = key,
        stageInferred = Stage.parseOrNull(stageInferred),
        stageOverride = Stage.parseOrNull(stageOverride),
    )

private fun parseStatusFor(raw: String?): ParseStatus =
    when (raw) {
        "ok" -> ParseStatus.Ok
        "failed" -> ParseStatus.Failed
        else -> ParseStatus.Pending
    }
