package com.sketchbook.uishared.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AppTheme

/**
 * Page-level title with the red "rule margin" underline — the vertical-bar accent that runs
 * the length of a notebook page on its own; here we use a horizontal hairline of the same
 * color so the title reads as a heading written into the page rather than a Material AppBar.
 *
 * The right slot is for inline actions (e.g. a primary CTA, a status chip).
 */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ProvideContentColor(colors.inkPrimary) {
                    Text(title, style = AppTheme.typography.title)
                }
                if (subtitle != null) {
                    ProvideContentColor(colors.inkMuted) {
                        Text(subtitle, style = AppTheme.typography.body)
                    }
                }
            }
            if (actions != null) actions()
        }
        Spacer(Modifier.height(AppTheme.spacing.xs))
        // Red rule-margin hairline. Drawn directly so we can offset it slightly inset.
        Canvas(modifier = Modifier.fillMaxWidth().height(2.dp)) {
            val y = size.height / 2f
            drawLine(
                color = colors.ruleMargin,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.5f,
            )
        }
    }
}
