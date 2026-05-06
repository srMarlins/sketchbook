package com.sketchbook.featurejournal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sketchbook.featurejournal.format.humanReadable
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.uishared.components.Button
import com.sketchbook.uishared.components.ButtonVariant
import com.sketchbook.uishared.components.DetailPane
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

/**
 * Right-pane content for a selected journal entry. Title is the resolved project name; body
 * shows the human action text plus a labelled before/after table where the action variant carries
 * one. The footer offers Undo only for invertible variants — repair-surface entries
 * (`MissingSampleMapped/Unmapped`, `MacPathRepaired`, `SnapshotRelabeled`) get their own
 * undo path elsewhere and aren't drilled-into here.
 */
@Composable
fun JournalDetailPane(
    entry: JournalEntry,
    projectName: String,
    vm: JournalViewModel,
    onDismiss: () -> Unit,
) {
    val invertible = isInvertible(entry.action)
    DetailPane(
        title = projectName,
        onDismiss = onDismiss,
        body = {
            ProvideContentColor(AppTheme.colors.inkPrimary) {
                Text(humanReadable(entry.action), style = AppTheme.typography.bodyEmphasis)
            }
            ProvideContentColor(AppTheme.colors.inkMuted) {
                Text(entry.timestamp.toString(), style = AppTheme.typography.caption)
            }
            entryFields(entry.action).forEach { (label, value) ->
                LabelledRow(label, value)
            }
            if (!invertible) {
                ProvideContentColor(AppTheme.colors.inkSecondary) {
                    Text(
                        "This entry is informational and cannot be undone from here.",
                        style = AppTheme.typography.caption,
                    )
                }
            }
        },
        footer = {
            if (invertible) {
                Button(
                    onClick = {
                        vm.dispatch(JournalViewModel.Intent.UndoOne(entry))
                        onDismiss()
                    },
                    variant = ButtonVariant.Primary,
                ) { Text("Undo") }
            }
        },
    )
}

private fun isInvertible(action: ActionRecord): Boolean = when (action) {
    is ActionRecord.Move,
    is ActionRecord.Rename,
    is ActionRecord.Archive,
    is ActionRecord.SetTags,
    -> true

    else -> false
}

private fun entryFields(action: ActionRecord): List<Pair<String, String>> = when (action) {
    is ActionRecord.Move -> listOf(
        "From" to action.pathBefore,
        "To" to action.pathAfter,
    )

    is ActionRecord.Rename -> listOf(
        "From" to action.nameBefore,
        "To" to action.nameAfter,
    )

    is ActionRecord.Archive -> listOf(
        "Was archived" to action.wasArchived.toString(),
        "Is archived" to action.isArchived.toString(),
    )

    is ActionRecord.SetTags -> listOf(
        "Before" to action.before.joinToString(", ").ifEmpty { "(none)" },
        "After" to action.after.joinToString(", ").ifEmpty { "(none)" },
    )

    is ActionRecord.ForceTakeLock -> listOf(
        "Prior owner" to (action.priorOwnerHostName ?: "(unknown)"),
    )

    is ActionRecord.PushConflict -> listOf(
        "Our rev" to action.ourRev.toString(),
        "Their rev" to action.theirRev.toString(),
    )

    is ActionRecord.MissingSampleMapped -> listOf(
        "Missing" to action.missingPath,
        "Candidate" to action.candidatePath,
        ".als outcome" to action.alsOutcome,
    )

    is ActionRecord.MissingSampleUnmapped -> listOf(
        "Missing" to action.missingPath,
        "Candidate" to action.candidatePath,
        ".als outcome" to action.alsOutcome,
    )

    is ActionRecord.MacPathRepaired -> listOf(
        "Mappings" to action.mappingCount.toString(),
        ".als outcome" to action.alsOutcome,
    )

    is ActionRecord.MacPathRestored -> listOf(
        "Mappings" to action.mappingCount.toString(),
        ".als outcome" to action.alsOutcome,
    )

    is ActionRecord.SnapshotRelabeled -> listOf(
        "Rev" to action.rev.toString(),
        "Before" to (action.labelBefore.takeUnless { it.isNullOrBlank() } ?: "(unlabeled)"),
        "After" to (action.labelAfter.takeUnless { it.isNullOrBlank() } ?: "(unlabeled)"),
    )

    is ActionRecord.StageOverridden -> listOf(
        "Inferred" to (action.stageInferred ?: "(none)"),
        "Before" to (action.stageBefore ?: "(auto)"),
        "After" to (action.stageAfter ?: "(auto)"),
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
