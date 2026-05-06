package com.sketchbook.uishared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AppTheme

/**
 * Top-level group container — single Surface card with a built-in header (chevron + title +
 * count + bulk-action slot) and a body slot underneath. Sub-groups inside use [SubGroupHeader]
 * for visual hierarchy.
 *
 * Design intent: every queue group on Proposals/Needs Attention/Journal is one of these cards,
 * separated by `spacing.md` between cards. Inside, rows are flat (no per-row Surface), so the
 * card edge is what visually contains the group.
 */
@Composable
fun GroupCard(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable (() -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    Surface(
        color = AppTheme.colors.surfaceCard,
        padding = PaddingValues(0.dp),
        elevation = 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppTheme.colors.tintCream)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
            ) {
                // Accent ribbon — small strip in front of the title that picks up the rule-margin
                // color. Marks the header band as a distinct affordance vs the row strip below.
                Box(
                    modifier = Modifier
                        .height(18.dp)
                        .width(3.dp)
                        .background(AppTheme.colors.ruleMargin),
                )
                ProvideContentColor(AppTheme.colors.inkSecondary) {
                    Text(if (expanded) "▾" else "▸", style = AppTheme.typography.mono)
                }
                ProvideContentColor(AppTheme.colors.inkPrimary) {
                    Text(title, style = AppTheme.typography.bodyEmphasis)
                }
                Badge(color = AppTheme.colors.surfaceCard) {
                    ProvideContentColor(AppTheme.colors.inkSecondary) {
                        Text(count.toString(), style = AppTheme.typography.caption)
                    }
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) { if (actions != null) actions() }
            }
            if (expanded) {
                StrongDivider()
                Column(
                    modifier = Modifier.padding(
                        start = AppTheme.spacing.lg,
                        end = AppTheme.spacing.lg,
                        top = AppTheme.spacing.xs,
                        bottom = AppTheme.spacing.xs,
                    ),
                ) { body() }
            }
        }
    }
}

/**
 * Sub-section header inside a [GroupCard]. Sunken background so it reads as a category strip
 * within the card. Use for missing-samples confidence buckets, mac-import folders, etc.
 */
@Composable
fun SubGroupHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
    actions: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AppTheme.colors.surfaceSunken)
            .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        ProvideContentColor(AppTheme.colors.inkPrimary) {
            Text(title.uppercase(), style = AppTheme.typography.caption)
        }
        ProvideContentColor(AppTheme.colors.inkMuted) {
            Text("· $count", style = AppTheme.typography.caption)
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) { if (actions != null) actions() }
    }
}

/**
 * Compact clickable row inside a [GroupCard]. No Surface, no edges — the card itself is the
 * visual container. Hairline divider drawn underneath unless [last] is true.
 */
@Composable
fun GroupRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    last: Boolean = false,
    indented: Boolean = true, // retained for compatibility — indent now lives on the GroupCard body inset
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = AppTheme.spacing.sm, vertical = AppTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        ) { content() }
        if (!last) HairlineDivider()
    }
}

@Composable
private fun HairlineDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AppTheme.colors.ruleLine),
    )
}

@Composable
private fun StrongDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AppTheme.colors.ruleLineStrong),
    )
}
