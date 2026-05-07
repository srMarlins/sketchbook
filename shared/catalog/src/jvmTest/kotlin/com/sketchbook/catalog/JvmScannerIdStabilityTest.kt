package com.sketchbook.catalog

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A1 — pins the id-preserving rescan behaviour. The previous `INSERT OR REPLACE INTO projects`
 * upsert deleted-and-reinserted on UNIQUE conflict, minting a new AUTOINCREMENT id every rescan
 * and orphaning every dependent table that keys on `project_id` (journal_entries, repair_acks,
 * project_samples, project_plugins, project_tags). With the read-then-update-or-insert helper
 * the same path keeps the same id forever.
 *
 * The scenario:
 *   1. Scan once → id1.
 *   2. Append a journal_entries row referencing id1 (simulates a real downstream write).
 *   3. Rewrite the .als with different bytes (so mtime/contents change → scanner re-parses).
 *   4. Scan again → id2.
 *   5. Assert id1 == id2 *and* the journal entry still resolves via selectProjectById(id1).
 *
 * The `flushBatch` `executeAsOne()` on selectProjectIdByPath would itself blow up if the row
 * were deleted-and-reinserted under a different id (the journal_entries FK-less reference would
 * silently dangle), so the assertion on id stability is what protects against the bug class.
 */
class JvmScannerIdStabilityTest {
    private fun writeAls(
        target: Path,
        xml: String,
    ) {
        Files.createDirectories(target.parent)
        Files.newOutputStream(target).use { out ->
            GZIPOutputStream(out).use { it.write(xml.toByteArray(Charsets.UTF_8)) }
        }
    }

    @Test
    fun rescanPreservesProjectId() =
        runTest {
            val root = createTempDirectory("scanner-id-stability-")
            try {
                val alsPath = root.resolve("Projects/Foo/Foo.als")
                writeAls(
                    alsPath,
                    """<?xml version="1.0"?>
                <Ableton Creator="Ableton Live 12.0.0"><LiveSet>
                  <MainTrack><DeviceChain><Mixer><Tempo><Manual Value="120.0"/></Tempo></Mixer></DeviceChain></MainTrack>
                  <Tracks><AudioTrack><Name><EffectiveName Value="Drums"/></Name></AudioTrack></Tracks>
                </LiveSet></Ableton>""",
                )

                val handle = CatalogDb.openInMemory()
                val catalog = handle.catalog
                val scanner =
                    JvmScanner(
                        catalog,
                        CatalogFts(handle.driver),
                        ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
                    )

                // First scan — capture id1.
                scanner.scan(root).toList()
                val abs = alsPath.toRealPath().toAbsolutePath().toString()
                val id1 = catalog.catalogQueries.selectProjectIdByPath(abs).executeAsOneOrNull()
                assertNotNull(id1, "first scan must persist a row at $abs")

                // Append a journal entry keyed on id1. Mirrors what SqlJournalRepository writes —
                // we go through the raw query so the test doesn't depend on the repository module.
                catalog.catalogQueries.insertJournalEntry(
                    occurred_at = 1_700_000_000_000L,
                    actor = "test",
                    action_type = "test.action",
                    project_id = id1,
                    payload_json = "{}",
                    project_name = "Foo",
                    project_path = abs,
                )

                // Rewrite the .als with a different tempo + bump mtime so the scanner doesn't take
                // the incremental-skip fast path. Same path, different contents.
                writeAls(
                    alsPath,
                    """<?xml version="1.0"?>
                <Ableton Creator="Ableton Live 12.1.0"><LiveSet>
                  <MainTrack><DeviceChain><Mixer><Tempo><Manual Value="140.0"/></Tempo></Mixer></DeviceChain></MainTrack>
                  <Tracks><AudioTrack><Name><EffectiveName Value="Drums"/></Name></AudioTrack></Tracks>
                </LiveSet></Ableton>""",
                )
                Files.setLastModifiedTime(
                    alsPath,
                    java.nio.file.attribute.FileTime
                        .fromMillis(System.currentTimeMillis() + 60_000),
                )

                // Second scan — must hit the UPDATE branch and keep the same id.
                scanner.scan(root).toList()
                val id2 = catalog.catalogQueries.selectProjectIdByPath(abs).executeAsOneOrNull()
                assertNotNull(id2, "second scan must keep the row at $abs")

                assertEquals(id1, id2, "rescan must preserve project_id (was $id1, now $id2)")

                // The journal entry's project_id must still resolve to a live project row. If the
                // INSERT OR REPLACE bug regressed, id1 would no longer match any row.
                val resolved = catalog.catalogQueries.selectProjectById(id1).executeAsOneOrNull()
                assertNotNull(
                    resolved,
                    "journal entry's project_id $id1 must still resolve after rescan",
                )
                // Confirm the rescan actually rewrote the row (proves we hit UPDATE, not a no-op).
                assertEquals(140.0, resolved.tempo)

                val journalRows =
                    catalog.catalogQueries
                        .selectJournalForProject(
                            project_id = id1,
                            limit_ = 10L,
                        ).executeAsList()
                assertEquals(1, journalRows.size)
                assertEquals(id1, journalRows.single().project_id)
            } finally {
                root.toFile().deleteRecursively()
            }
        }
}
