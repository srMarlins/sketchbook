package com.sketchbook.repo.impl

import com.sketchbook.catalog.db.Projects
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectPath
import com.sketchbook.core.ProjectRow
import kotlin.time.Instant

/**
 * Map a SQLDelight row to a domain [ProjectRow]. Tags are joined into the row by the caller
 * (a separate query). SQLDelight types never leak past this boundary.
 */
internal fun Projects.toDomain(tags: List<String> = emptyList()): ProjectRow = ProjectRow(
    id = ProjectId(id),
    name = name,
    path = ProjectPath.fromPlatform(path),
    tempo = tempo,
    trackCount = track_count.toInt(),
    lastSavedLiveVersion = live_version,
    updatedAt = Instant.fromEpochMilliseconds((last_modified * 1000).toLong()),
    tags = tags,
    colorTag = color_tag?.toInt(),
)
