package com.sketchbook.featureprojects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.sketchbook.uishared.components.EmptyState
import com.sketchbook.uishared.components.Pill
import com.sketchbook.uishared.components.RowItem
import com.sketchbook.uishared.components.Tag
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.components.TextField
import com.sketchbook.uishared.theme.AppTheme

/**
 * Project list screen. Reads `state` reactively, dispatches `Intent`s for input. Keeps no local
 * UI state of its own — text-field value comes from `state.query`, click handlers translate to
 * `Intent.Open`.
 */
@Composable
fun ProjectListScreen(
    holder: ProjectListStateHolder,
    modifier: Modifier = Modifier,
) {
    val state by holder.state.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppTheme.colors.surfacePage)
            .padding(PaddingValues(AppTheme.spacing.md)),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
    ) {
        TextField(
            value = state.query,
            onChange = { holder.dispatch(ProjectListStateHolder.Intent.Search(it)) },
            placeholder = "Search projects, plugins, samples…",
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.rows.isEmpty()) {
            EmptyState(
                title = if (state.query.isBlank()) "No projects yet" else "No matches",
                hint = if (state.query.isBlank()) "Open a project in Live to populate the catalog." else "Try a different query.",
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
            ) {
                items(state.rows, key = { it.id.value }) { row ->
                    RowItem(
                        title = row.name,
                        subtitle = projectSubtitle(row),
                        leading = { Pill(colorIndex = row.colorTag) },
                        trailing = {
                            row.tags.firstOrNull()?.let { firstTag ->
                                Tag(label = firstTag)
                            }
                        },
                        onClick = { holder.dispatch(ProjectListStateHolder.Intent.Open(row.id)) },
                    )
                }
            }
        }
    }
}

private fun projectSubtitle(row: com.sketchbook.core.ProjectRow): String {
    val tempo = row.tempo?.let { "${it.toInt()} bpm" }
    val tracks = "${row.trackCount} tracks"
    return listOfNotNull(tempo, tracks).joinToString(" · ")
}
