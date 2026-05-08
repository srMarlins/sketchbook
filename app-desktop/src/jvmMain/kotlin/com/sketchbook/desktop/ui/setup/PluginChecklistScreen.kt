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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sketchbook.repo.HostPluginEntry

/**
 * Final post-onboarding screen — the bootstrap plugin checklist. Driven by
 * [PluginChecklistViewModel].
 *
 * Wireframe lives in `docs/plans/2026-05-07-backend-generalization-design.md`. This impl
 * is intentionally lean: the design doc's vendor + per-project counts surface lands when
 * the catalog carries vendor strings (out of scope here). Until then each row shows the
 * name, format, and the "Find online" search link.
 */
@Composable
fun PluginChecklistScreen(
    viewModel: PluginChecklistViewModel,
    onReprobe: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Set up this Mac", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        val total = state.pending.size + state.recentlyInstalled.size + state.alreadyInstalled.size
        val pending = state.pending.size
        Text(
            "Found $total plugins across your projects and templates. " +
                "$pending of them aren't installed on this Mac yet.",
        )
        Spacer(Modifier.height(16.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            if (state.recentlyInstalled.isNotEmpty()) {
                item {
                    SectionHeader("Just installed (${state.recentlyInstalled.size})")
                }
                items(state.recentlyInstalled) { row -> PluginRowItem(row, justInstalled = true) }
                item { Spacer(Modifier.height(12.dp)) }
            }
            items(state.pending) { row -> PluginRowItem(row, justInstalled = false) }
            if (state.alreadyInstalled.isNotEmpty()) {
                item { Divider(Modifier.padding(vertical = 12.dp)) }
                item { SectionHeader("Already installed (${state.alreadyInstalled.size})") }
                items(state.alreadyInstalled) { row -> PluginRowItem(row, justInstalled = false) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onReprobe) {
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
        Column(modifier = Modifier.padding(end = 16.dp)) {
            Text(row.name, fontWeight = FontWeight.Medium)
            val sub = "${row.format}${if (justInstalled) " · just installed" else ""}"
            Text(sub)
        }
    }
}

/** Convert a flat union to the row shape the screen consumes. Used by the Settings entry. */
fun toScreenRows(entries: List<HostPluginEntry>): List<PluginRow> =
    entries.map { PluginRow(name = it.name, format = it.format, installed = it.installed) }
