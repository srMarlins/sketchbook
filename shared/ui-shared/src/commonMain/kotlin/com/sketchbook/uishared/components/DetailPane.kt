package com.sketchbook.uishared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AppTheme

/**
 * Right-docked detail pane. Header with title + close affordance, scrollable body, optional
 * sticky footer for primary actions. Stays open across row clicks — caller swaps the body /
 * footer content based on which row is selected.
 */
@Composable
fun DetailPane(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable (() -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .widthIn(min = 320.dp, max = 420.dp)
            .fillMaxHeight()
            .background(AppTheme.colors.surfaceCard),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AppTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        ) {
            ProvideContentColor(AppTheme.colors.inkPrimary) {
                Text(title, style = AppTheme.typography.bodyEmphasis, modifier = Modifier.weight(1f))
            }
            Box(modifier = Modifier.clickable(onClick = onDismiss).padding(AppTheme.spacing.xs)) {
                ProvideContentColor(AppTheme.colors.inkMuted) { Text("✕") }
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(AppTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
            ) { body() }
        }
        if (footer != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppTheme.colors.surfaceSunken)
                    .padding(AppTheme.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
            ) { footer() }
        }
    }
}

@Composable
fun DetailPaneEmpty(message: String = "Select a row for details", modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(420.dp)
            .fillMaxHeight()
            .background(AppTheme.colors.surfaceCard),
        contentAlignment = Alignment.Center,
    ) {
        ProvideContentColor(AppTheme.colors.inkMuted) {
            Text(message, style = AppTheme.typography.body)
        }
    }
}
