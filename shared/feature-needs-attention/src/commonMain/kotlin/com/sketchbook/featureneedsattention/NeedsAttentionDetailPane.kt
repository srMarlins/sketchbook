package com.sketchbook.featureneedsattention

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sketchbook.repo.MacImportFinding
import com.sketchbook.repo.MissingSampleFinding
import com.sketchbook.uishared.components.Badge
import com.sketchbook.uishared.components.Button
import com.sketchbook.uishared.components.ButtonVariant
import com.sketchbook.uishared.components.DetailPane
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

/**
 * Right-pane target for the Needs-attention drawer. Carries the full finding object so the pane
 * never needs to query the VM to look one up — when the underlying list mutates, the screen just
 * clears its local target.
 */
sealed interface NeedsAttentionDetailTarget {
    data class Mac(val finding: MacImportFinding) : NeedsAttentionDetailTarget
    data class Missing(val finding: MissingSampleFinding) : NeedsAttentionDetailTarget
}

/**
 * Right-pane content for a selected Needs-attention row. Dispatches the existing single-item
 * intents (`AckMacImport`, `RepairMacPaths`, `ApplyMatch`, `DismissMissingSample`) and dismisses
 * on action. Bulk intents stay on the section headers in the screen — not duplicated here.
 */
@Composable
fun NeedsAttentionDetailPane(
    target: NeedsAttentionDetailTarget,
    vm: NeedsAttentionViewModel,
    onDismiss: () -> Unit,
) {
    when (target) {
        is NeedsAttentionDetailTarget.Mac -> MacImportDetail(target.finding, vm, onDismiss)
        is NeedsAttentionDetailTarget.Missing -> MissingSampleDetail(target.finding, vm, onDismiss)
    }
}

@Composable
private fun MacImportDetail(
    f: MacImportFinding,
    vm: NeedsAttentionViewModel,
    onDismiss: () -> Unit,
) {
    DetailPane(
        title = f.name,
        onDismiss = onDismiss,
        body = {
            ProvideContentColor(AppTheme.colors.inkMuted) {
                Text(f.path, style = AppTheme.typography.mono)
            }
            LabelledRow("Mac paths", f.macPathsCount.toString())
            LabelledRow("Project Info", if (f.projectInfoMissing) "missing" else "ok")
            ProvideContentColor(AppTheme.colors.inkSecondary) {
                Text(
                    "Repair rewrites the .als so Live finds samples on this machine. Ignore drops the project from this list without touching it.",
                    style = AppTheme.typography.caption,
                )
            }
        },
        footer = {
            Button(
                onClick = {
                    vm.dispatch(NeedsAttentionViewModel.Intent.AckMacImport(f.projectId))
                    onDismiss()
                },
                variant = ButtonVariant.Ghost,
            ) { Text("Ignore") }
            Button(
                onClick = {
                    vm.dispatch(NeedsAttentionViewModel.Intent.RepairMacPaths(f.projectId))
                    onDismiss()
                },
                variant = ButtonVariant.Primary,
            ) { Text("Repair") }
        },
    )
}

@Composable
private fun MissingSampleDetail(
    f: MissingSampleFinding,
    vm: NeedsAttentionViewModel,
    onDismiss: () -> Unit,
) {
    DetailPane(
        title = f.projectName,
        onDismiss = onDismiss,
        body = {
            ProvideContentColor(AppTheme.colors.inkMuted) {
                Text(f.missingPath, style = AppTheme.typography.mono)
            }
            ProvideContentColor(AppTheme.colors.inkPrimary) {
                val n = f.candidates.size
                Text(
                    "$n candidate${if (n == 1) "" else "s"}",
                    style = AppTheme.typography.bodyEmphasis,
                )
            }
            f.candidates.forEach { c ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                ) {
                    if (f.autoMatch?.path == c.path) {
                        Badge(color = AppTheme.colors.pinGreen) {
                            ProvideContentColor(AppTheme.colors.inkPrimary) {
                                Text("auto", style = AppTheme.typography.caption)
                            }
                        }
                    }
                    ProvideContentColor(AppTheme.colors.inkPrimary) {
                        Text(
                            c.path,
                            style = AppTheme.typography.mono,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Button(
                        onClick = {
                            vm.dispatch(
                                NeedsAttentionViewModel.Intent.ApplyMatch(
                                    projectId = f.projectId,
                                    missingPath = f.missingPath,
                                    candidatePath = c.path,
                                ),
                            )
                            onDismiss()
                        },
                        variant = ButtonVariant.Ghost,
                    ) { Text("Use this") }
                }
            }
        },
        footer = {
            Button(
                onClick = {
                    vm.dispatch(
                        NeedsAttentionViewModel.Intent.DismissMissingSample(
                            projectId = f.projectId,
                            missingPath = f.missingPath,
                        ),
                    )
                    onDismiss()
                },
                variant = ButtonVariant.Ghost,
            ) { Text("Dismiss") }
        },
    )
}

@Composable
private fun LabelledRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        ProvideContentColor(AppTheme.colors.inkMuted) {
            Text(label, style = AppTheme.typography.caption, modifier = Modifier.width(140.dp))
        }
        ProvideContentColor(AppTheme.colors.inkPrimary) {
            Text(value, style = AppTheme.typography.body)
        }
    }
}
