package com.sketchbook.desktop.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Stateless plugin-checklist surface. Pure Compose: takes the [state] up front and emits
 * intents through the supplied lambdas. Tests + `@Preview` instantiate this directly without
 * dragging the VM, the cloud, or the catalog into the test surface.
 *
 * The route entry point that wires up the VM and lifecycle-aware state collection lands once
 * [PluginChecklistViewModel] picks up `UserScope` DI (tracked in
 * https://github.com/srMarlins/sketchbook/issues/130).
 */
@Composable
fun PluginChecklistScreen(
    state: PluginChecklistUiState,
    onReprobe: () -> Unit,
    onDismiss: () -> Unit,
    osLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(16.dp)) {
        Text("Set up this $osLabel", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        // Total + pending change at most when buckets reshuffle on reprobe; keep the
        // computation off the recomposition hot path even though it's cheap today.
        val total =
            remember(state.pending, state.recentlyInstalled, state.alreadyInstalled) {
                state.pending.size + state.recentlyInstalled.size + state.alreadyInstalled.size
            }
        val pending = state.pending.size
        if (state.isInitialLoad) {
            Text("Loading plugin coverage…")
        } else if (state.loadFailed != null) {
            Text("Couldn't load plugin checklist: ${state.loadFailed}")
        } else {
            Text(
                "Found $total plugins across your projects and templates. " +
                    "$pending of them aren't installed on this $osLabel yet.",
            )
        }
        Spacer(Modifier.height(16.dp))

        // Wrap the list in `weight(1f)` so it scrolls inside the available area instead of
        // pushing the action row off-screen for long lists. `items(...)` pass a stable key
        // derived from `(name, format)` so reshuffles on reprobe don't reset row state.
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.recentlyInstalled.isNotEmpty()) {
                item("recently-installed-header") {
                    SectionHeader("Just installed (${state.recentlyInstalled.size})")
                }
                items(
                    items = state.recentlyInstalled,
                    key = { row -> "ri:${row.name}|${row.format}" },
                    contentType = { "plugin-row" },
                ) { row -> PluginRowItem(row, justInstalled = true) }
                item("recently-installed-spacer") { Spacer(Modifier.height(12.dp)) }
            }
            items(
                items = state.pending,
                key = { row -> "p:${row.name}|${row.format}" },
                contentType = { "plugin-row" },
            ) { row -> PluginRowItem(row, justInstalled = false) }
            if (state.alreadyInstalled.isNotEmpty()) {
                item("already-installed-divider") { Divider(Modifier.padding(vertical = 12.dp)) }
                item("already-installed-header") {
                    SectionHeader("Already installed (${state.alreadyInstalled.size})")
                }
                items(
                    items = state.alreadyInstalled,
                    key = { row -> "ai:${row.name}|${row.format}" },
                    contentType = { "plugin-row" },
                ) { row -> PluginRowItem(row, justInstalled = false) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Disabled while a probe is in flight so users can't queue N reprobes by
            // mashing the button — each click would otherwise serialize behind the mutex.
            Button(
                onClick = onReprobe,
                enabled = !state.isReprobing,
            ) {
                Text(if (state.isReprobing) "Re-checking…" else "Re-check installed plugins")
            }
            OutlinedButton(onClick = onDismiss) { Text("Skip — show in Settings") }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun PluginRowItem(
    row: PluginRow,
    justInstalled: Boolean,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column {
            Text(row.name, fontWeight = FontWeight.Medium)
            val sub = "${row.format.wireName}${if (justInstalled) " · just installed" else ""}"
            Text(sub)
        }
    }
}
