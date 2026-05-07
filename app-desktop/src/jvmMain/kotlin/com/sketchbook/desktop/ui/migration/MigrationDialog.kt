package com.sketchbook.desktop.ui.migration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sketchbook.desktop.migration.MigrationCoordinator
import com.sketchbook.migration.MigrationProgress
import com.sketchbook.migration.MigrationStatus

/**
 * Compose modal that gates app startup on cloud-storage migration. Mirrors the wireframe in
 * the design doc:
 *
 *     ┌─────────────────────────────────────────────────────────┐
 *     │  Cloud storage upgrade                                  │
 *     │  • 23 project trees                                     │
 *     │  • 412 manifest files to relocate                       │
 *     │  • 0 blob files to move (content stays put)             │
 *     │  [Preview…]  [Run migration]                            │
 *     └─────────────────────────────────────────────────────────┘
 *
 * Wiring (commit 14): the desktop main composable observes
 * [MigrationCoordinator.state] and renders [MigrationDialog] when the state is one of
 * [MigrationCoordinator.State.Pending] / [Running] / [Failed]. [Done] dismisses the dialog
 * and lets startup continue; [Quit] exits the app.
 */
@Composable
fun MigrationDialog(
    coordinator: MigrationCoordinator,
    onConfirm: () -> Unit,
) {
    val state by coordinator.state.collectAsState()
    when (val s = state) {
        is MigrationCoordinator.State.Pending -> PendingDialog(s.status, onConfirm = onConfirm, onDismiss = coordinator::onUserDismissed)
        is MigrationCoordinator.State.Running -> RunningDialog(s.latest)
        is MigrationCoordinator.State.Failed -> FailedDialog(s.reason, onConfirm = onConfirm)
        else -> Unit
    }
}

@Composable
private fun PendingDialog(
    pending: MigrationStatus.Pending,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cloud storage upgrade") },
        text = {
            Column {
                Text(
                    "Sketchbook needs to reorganize your cloud storage to support new sync " +
                        "features (User Library, machine profile). This runs once per machine and " +
                        "is safe to retry — your local files are untouched.",
                )
                Spacer(Modifier.height(12.dp))
                Text("On this machine:")
                Text("  • ${pending.report.projectTreesPending} project trees")
                Text("  • ${pending.report.manifestsPending} manifest files to relocate")
                Text("  • 0 blob files to move (content stays put)")
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Run migration") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Quit") }
        },
    )
}

@Composable
private fun RunningDialog(progress: MigrationProgress) {
    AlertDialog(
        onDismissRequest = { /* not dismissable while running */ },
        title = { Text("Migrating cloud storage…") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                val (label, fraction) =
                    when (progress) {
                        is MigrationProgress.Probing -> {
                            "Probing bucket…" to 0.05f
                        }

                        is MigrationProgress.Relocating -> {
                            "Relocating manifests (${progress.done}/${progress.total})" to
                                if (progress.total == 0) 0.5f else (progress.done.toFloat() / progress.total)
                        }

                        is MigrationProgress.BuildingRegistry -> {
                            "Building registry…" to 0.85f
                        }

                        is MigrationProgress.RegisteredTree -> {
                            "Registered ${progress.entry.displayName}" to 0.9f
                        }

                        is MigrationProgress.Done -> {
                            "Done" to 1f
                        }

                        is MigrationProgress.Failed -> {
                            progress.reason to 1f
                        }
                    }
                Text(label)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun FailedDialog(
    reason: String,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { /* still requires acknowledgment */ },
        title = { Text("Migration failed") },
        text = {
            Column {
                Text("The cloud-storage upgrade did not complete. Sketchbook hasn't changed any local files.")
                Spacer(Modifier.height(8.dp))
                Text("Reason: $reason")
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConfirm) { Text("Retry") }
                Spacer(Modifier.width(0.dp))
            }
        },
    )
}
