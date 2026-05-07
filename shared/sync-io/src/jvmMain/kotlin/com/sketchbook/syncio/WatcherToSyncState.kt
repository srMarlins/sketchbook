package com.sketchbook.syncio

import com.sketchbook.catalog.SyncStateStore
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.ProjectId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import java.nio.file.Path

/**
 * Glue between the file watcher and the catalog's dirty bit. For each `.als` save under one of
 * the configured roots, resolves the project directory and flips `sync_state.dirty = 1` so the
 * background drain (PR-H) picks it up.
 *
 * **Path resolution rule.** A save at `<projectDir>/Backup/<file>.als` resolves to `<projectDir>`.
 * A save at `<projectDir>/<file>.als` (the main file overwrite) also resolves to `<projectDir>`.
 * In both cases we query `selectProjectIdsByParentDir(<projectDir>)` and mark every match dirty.
 *
 * Lives in `:shared:sync-io` rather than `:shared:sync` because `:shared:sync-io` already
 * depends on `:shared:sync` (for [WorkingTree]); the reverse edge would close a cycle. Conceptually
 * the class is sync-io's own — it's the bridge from filesystem events to catalog state, both of
 * which are sync-io's neighbors.
 *
 * The returned [Flow] still surfaces every [SaveEvent] downstream (passthrough via [onEach]) so a
 * caller that needs them — observability, tests — can collect them; the side effect on
 * [SyncStateStore] is fired regardless of whether anyone consumes the events.
 */
class WatcherToSyncState(
    private val watcher: Watcher,
    private val catalog: Catalog,
    private val syncState: SyncStateStore,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchAll(roots: List<Path>): Flow<SaveEvent> {
        val flows = roots.map { watcher.watch(it) }
        val merged: Flow<SaveEvent> =
            if (flows.isEmpty()) {
                emptyFlow()
            } else {
                merge(*flows.toTypedArray())
            }
        return merged
            .onEach { event ->
                if (event is SaveEvent.Saved) handleSave(event.path)
            }.flowOn(Dispatchers.IO)
    }

    private fun handleSave(savedPath: Path) {
        if (!savedPath.toString().endsWith(".als")) return
        val parent = savedPath.parent ?: return
        // Live's backup folder convention: <projectDir>/Backup/<file>.als. Otherwise the save is
        // a direct write into the project dir itself.
        val projectDir = if (parent.fileName?.toString() == "Backup") parent.parent else parent
        projectDir ?: return
        // Best-effort: a transient DB error (locked, schema race during migration) shouldn't kill
        // the watcher Flow for the rest of the session — auto-push would silently stop. Swallow;
        // the next save will retry. (Shared modules don't log; we'll surface persistent failures
        // through the existing sync-state observability if it becomes a problem.)
        runCatching {
            val ids =
                catalog.catalogQueries
                    .selectProjectIdsByParentDir(parent_dir = projectDir.toString())
                    .executeAsList()
            for (rawId in ids) {
                val uuid = syncState.identityFor(ProjectId(rawId))
                syncState.markDirty(uuid)
            }
        }
    }
}
