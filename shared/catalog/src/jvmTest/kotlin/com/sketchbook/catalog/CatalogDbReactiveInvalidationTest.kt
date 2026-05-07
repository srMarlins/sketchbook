package com.sketchbook.catalog

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Regression test for the May 2026 "UI doesn't update during scan" bug. The catalog DB used to be
 * built via `pooled.asJdbcDriver()` (HikariCP-wrapped DataSource). That extension is documented
 * in SQLDelight 2.x's source to return a driver whose `addListener`/`notifyListeners` are
 * **no-ops** — flow subscribers never receive a re-emission on transaction commit. The fix was
 * to use a single-connection [JdbcSqliteDriver] whose listener trio is actually wired.
 *
 * This test pins `CatalogDb.openOnDisk`'s contract: after a transaction commits a write to
 * `projects`, an active `Query.asFlow()` subscriber receives a fresh emission within seconds.
 * Without the fix, this hangs until the Turbine timeout.
 */
class CatalogDbReactiveInvalidationTest {
    private val tmp = createTempDirectory("catalog-reactive-")

    @AfterTest fun cleanup() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun queryAsFlowReEmitsAfterTransactionCommit() =
        runBlocking {
            val handle = CatalogDb.openOnDisk(tmp.resolve("catalog.db"))
            try {
                handle.catalog.catalogQueries
                    .selectAllProjects()
                    .asFlow()
                    .mapToList(Dispatchers.IO)
                    .test(timeout = 5.seconds) {
                        assertEquals(0, awaitItem().size, "expected empty initial emission")

                        handle.catalog.transaction {
                            handle.catalog.catalogQueries.insertOrReplaceProject(
                                path = "$tmp/A.als",
                                name = "A",
                                parent_dir = "$tmp",
                                tempo = null,
                                time_sig_num = null,
                                time_sig_den = null,
                                key = null,
                                track_count = 0L,
                                audio_tracks = 0L,
                                midi_tracks = 0L,
                                return_tracks = 0L,
                                live_version = null,
                                last_modified = 1.0,
                                last_scanned = 1.0,
                                parse_status = "ok",
                                parse_error = null,
                                mac_paths_count = 0L,
                                effort_score = null,
                                effort_breakdown = null,
                                file_size_bytes = 0L,
                            )
                        }
                        assertEquals(1, awaitItem().size, "expected 1 row after first commit")

                        handle.catalog.transaction {
                            handle.catalog.catalogQueries.insertOrReplaceProject(
                                path = "$tmp/B.als",
                                name = "B",
                                parent_dir = "$tmp",
                                tempo = null,
                                time_sig_num = null,
                                time_sig_den = null,
                                key = null,
                                track_count = 0L,
                                audio_tracks = 0L,
                                midi_tracks = 0L,
                                return_tracks = 0L,
                                live_version = null,
                                last_modified = 2.0,
                                last_scanned = 2.0,
                                parse_status = "ok",
                                parse_error = null,
                                mac_paths_count = 0L,
                                effort_score = null,
                                effort_breakdown = null,
                                file_size_bytes = 0L,
                            )
                        }
                        assertEquals(2, awaitItem().size, "expected 2 rows after second commit")

                        cancelAndIgnoreRemainingEvents()
                    }
            } finally {
                handle.driver.close()
                // Best-effort cleanup; Windows file locks can linger briefly after close.
                runCatching {
                    Files.deleteIfExists(tmp.resolve("catalog.db"))
                    Files.deleteIfExists(tmp.resolve("catalog.db-wal"))
                    Files.deleteIfExists(tmp.resolve("catalog.db-shm"))
                }
            }
        }
}
