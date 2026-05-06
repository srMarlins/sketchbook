package com.sketchbook.syncio

import com.sketchbook.catalog.CatalogDb
import com.sketchbook.catalog.SyncStateStore
import com.sketchbook.catalog.db.Catalog
import com.sketchbook.core.ProjectId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class WatcherToSyncStateTest {

    private val tmp = createTempDirectory("watcher-sync-state-")
    private val tearDown = mutableListOf<() -> Unit>()

    @AfterTest
    fun cleanup() {
        tearDown.forEach { runCatching { it() } }
        tmp.toFile().deleteRecursively()
    }

    private fun seedProject(catalog: Catalog, name: String, projectDir: File): Long {
        projectDir.mkdirs()
        val alsPath = File(projectDir, "$name.als").absolutePath
        catalog.catalogQueries.insertOrReplaceProject(
            path = alsPath,
            name = name,
            parent_dir = projectDir.absolutePath,
            tempo = 120.0,
            time_sig_num = 4,
            time_sig_den = 4,
            key = null,
            track_count = 0,
            audio_tracks = 0,
            midi_tracks = 0,
            return_tracks = 0,
            live_version = "12.0.0",
            last_modified = 0.0,
            last_scanned = 0.0,
            parse_status = "ok",
            parse_error = null,
            mac_paths_count = 0,
            effort_score = null,
            effort_breakdown = null,
            file_size_bytes = 0L,
        )
        return catalog.catalogQueries.selectProjectIdByPath(alsPath).executeAsOne()
    }

    @Test
    fun `als save under known project marks dirty`() = runBlocking {
        val handle = CatalogDb.openInMemory()
        val catalog = handle.catalog
        val syncState = SyncStateStore(catalog)

        val projectDir = File(tmp.toFile(), "Foo Project")
        val pid = seedProject(catalog, "Foo", projectDir)
        val uuid = syncState.identityFor(ProjectId(pid))

        val watcher = Watcher(debounce = 100.milliseconds)
        val wts = WatcherToSyncState(watcher, catalog, syncState)

        val job: Job = launch(Dispatchers.IO) {
            wts.watchAll(listOf(tmp)).onEach { /* drain */ }.launchIn(this)
        }
        tearDown += { runBlocking { job.cancelAndJoin() } }

        // Give the DirectoryWatcher a moment to register before we touch the FS.
        delay(500)

        val alsFile = File(projectDir, "Foo.als")
        alsFile.writeBytes(byteArrayOf(1, 2, 3))

        withTimeout(8_000) {
            while (true) {
                val state = syncState.stateOf(uuid)
                if (state?.dirty == true) break
                delay(50)
            }
        }

        val finalState = syncState.stateOf(uuid)
        assertTrue(finalState != null && finalState.dirty, "expected dirty=1 for $uuid")
        job.cancelAndJoin()
        tearDown.clear()
    }

    @Test
    fun `als save under unknown directory does NOT mark dirty`() = runBlocking {
        val handle = CatalogDb.openInMemory()
        val catalog = handle.catalog
        val syncState = SyncStateStore(catalog)

        // Project is registered, but its parent_dir lives outside the watch root.
        val unrelated = File(tmp.toFile(), "unrelated")
        val pid = seedProject(catalog, "Bar", unrelated)
        val uuid = syncState.identityFor(ProjectId(pid))

        // Watch a separate dir that has no projects under it.
        val watchRoot = File(tmp.toFile(), "watched").apply { mkdirs() }.toPath()

        val watcher = Watcher(debounce = 100.milliseconds)
        val wts = WatcherToSyncState(watcher, catalog, syncState)

        val job: Job = launch(Dispatchers.IO) {
            wts.watchAll(listOf(watchRoot)).onEach { /* drain */ }.launchIn(this)
        }
        tearDown += { runBlocking { job.cancelAndJoin() } }

        delay(500)

        val alsFile = File(watchRoot.toFile(), "stray.als")
        alsFile.writeBytes(byteArrayOf(1, 2, 3))

        // Give the watcher debounce + slack to fire.
        delay(1_500)

        val state = syncState.stateOf(uuid)
        assertTrue(state == null || !state.dirty, "expected no dirty row for unrelated project")
        job.cancelAndJoin()
        tearDown.clear()
    }

    @Test
    fun `backup folder save resolves to parent project dir`() = runBlocking {
        val handle = CatalogDb.openInMemory()
        val catalog = handle.catalog
        val syncState = SyncStateStore(catalog)

        // Simulate the Live layout: <root>/Foo Project/Foo.als, with backups under Foo Project/Backup.
        val projectDir = File(tmp.toFile(), "Foo Project")
        val pid = seedProject(catalog, "Foo", projectDir)
        val uuid = syncState.identityFor(ProjectId(pid))

        val backup = File(projectDir, "Backup").apply { mkdirs() }

        val watcher = Watcher(debounce = 100.milliseconds)
        val wts = WatcherToSyncState(watcher, catalog, syncState)

        val job: Job = launch(Dispatchers.IO) {
            wts.watchAll(listOf(tmp)).onEach { /* drain */ }.launchIn(this)
        }
        tearDown += { runBlocking { job.cancelAndJoin() } }

        delay(500)

        // Live writes a timestamped backup file; the .als path will not match projects.path,
        // but parent.parent (= projectDir) should match parent_dir.
        val backupAls = File(backup, "Foo [2026-05-06 100000].als")
        backupAls.writeBytes(byteArrayOf(7, 8, 9))

        withTimeout(8_000) {
            while (true) {
                val state = syncState.stateOf(uuid)
                if (state?.dirty == true) break
                delay(50)
            }
        }

        val finalState = syncState.stateOf(uuid)
        assertTrue(finalState != null && finalState.dirty, "expected dirty=1 for $uuid via backup-folder rule")
        job.cancelAndJoin()
        tearDown.clear()
    }
}
