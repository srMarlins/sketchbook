package com.sketchbook.catalog

import com.sketchbook.catalog.db.Catalog
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PR-BB BB1: pins the `selectLibraryHealth` aggregate. Seeds a 10-project fixture covering every
 * combination the chip cares about (synced/dirty, in-sync/out-of-sync revs, no sync_state row,
 * missing samples present/absent, archived) and asserts each component count.
 *
 * The fixture is intentionally exhaustive so adding a fourth signal (PR-T plugin-clean or
 * PR-R stage-not-stuck) only means tweaking the assertion column — the seeding logic already
 * covers the join shapes the new clauses will read.
 */
class LibraryHealthQueryTest {

    private val handle = CatalogDb.openInMemory()
    private val catalog: Catalog get() = handle.catalog

    private fun seedProject(
        name: String,
        archived: Boolean = false,
        lastModified: Double = 1.0,
    ): Long {
        val path = "/lib/$name.als"
        catalog.catalogQueries.insertOrReplaceProject(
            path = path,
            name = name,
            parent_dir = "/lib",
            tempo = 120.0,
            time_sig_num = 4,
            time_sig_den = 4,
            key = null,
            track_count = 1,
            audio_tracks = 1,
            midi_tracks = 0,
            return_tracks = 0,
            live_version = "12.0.0",
            last_modified = lastModified,
            last_scanned = lastModified,
            parse_status = "ok",
            parse_error = null,
            mac_paths_count = 0,
            effort_score = null,
            effort_breakdown = null,
            file_size_bytes = 0L,
        )
        val id = catalog.catalogQueries.selectProjectIdByPath(path).executeAsOne()
        if (archived) catalog.catalogQueries.setArchived(1L, id)
        return id
    }

    private fun seedIdentity(projectId: Long, uuid: String) {
        catalog.catalogQueries.insertProjectIdentityIfAbsent(
            project_id = projectId,
            uuid = uuid,
            created_at = "2026-05-06T00:00:00Z",
        )
    }

    private fun seedSyncState(uuid: String, dirty: Long, localRev: Long, cloudHeadRev: Long) {
        catalog.catalogQueries.insertOrReplaceSyncState(
            project_uuid = uuid,
            local_rev = localRev,
            cloud_head_rev = cloudHeadRev,
            dirty = dirty,
            self_contained = 0L,
            updated_at = 0L,
        )
    }

    private fun seedSample(projectId: Long, isMissing: Boolean) {
        catalog.catalogQueries.insertProjectSampleWithMissing(
            project_id = projectId,
            sample_path = "/samples/${projectId}_${isMissing}.wav",
            is_missing = if (isMissing) 1L else 0L,
        )
    }

    @Test
    fun aggregatesSyncedAndSampleCleanCounts() {
        // P1: synced + sample-clean (counts toward both)
        val p1 = seedProject("p1")
        seedIdentity(p1, "uuid-1")
        seedSyncState("uuid-1", dirty = 0, localRev = 5, cloudHeadRev = 5)
        seedSample(p1, isMissing = false)

        // P2: synced + has missing sample (counts synced, NOT sample_clean)
        val p2 = seedProject("p2")
        seedIdentity(p2, "uuid-2")
        seedSyncState("uuid-2", dirty = 0, localRev = 3, cloudHeadRev = 3)
        seedSample(p2, isMissing = true)

        // P3: dirty (not synced) + sample-clean (counts sample_clean only)
        val p3 = seedProject("p3")
        seedIdentity(p3, "uuid-3")
        seedSyncState("uuid-3", dirty = 1, localRev = 2, cloudHeadRev = 2)

        // P4: clean dirty flag but local_rev != cloud_head_rev → not synced.
        val p4 = seedProject("p4")
        seedIdentity(p4, "uuid-4")
        seedSyncState("uuid-4", dirty = 0, localRev = 1, cloudHeadRev = 2)

        // P5: no sync_state row at all (never pushed) → not synced; no samples → sample_clean.
        seedProject("p5")

        // P6: synced + no sample rows → counts synced + sample_clean.
        val p6 = seedProject("p6")
        seedIdentity(p6, "uuid-6")
        seedSyncState("uuid-6", dirty = 0, localRev = 7, cloudHeadRev = 7)

        // P7: project with both clean and missing samples — any missing flips it dirty.
        val p7 = seedProject("p7")
        seedIdentity(p7, "uuid-7")
        seedSyncState("uuid-7", dirty = 0, localRev = 1, cloudHeadRev = 1)
        seedSample(p7, isMissing = false)
        seedSample(p7, isMissing = true)

        // P8: archived project — must NOT be in any count, including total.
        val p8 = seedProject("p8", archived = true)
        seedIdentity(p8, "uuid-8")
        seedSyncState("uuid-8", dirty = 0, localRev = 1, cloudHeadRev = 1)

        // P9: dirty + missing sample (counts neither).
        val p9 = seedProject("p9")
        seedIdentity(p9, "uuid-9")
        seedSyncState("uuid-9", dirty = 1, localRev = 4, cloudHeadRev = 4)
        seedSample(p9, isMissing = true)

        // P10: synced + multiple clean samples (still one sample_clean row).
        val p10 = seedProject("p10")
        seedIdentity(p10, "uuid-10")
        seedSyncState("uuid-10", dirty = 0, localRev = 9, cloudHeadRev = 9)
        seedSample(p10, isMissing = false)
        seedSample(p10, isMissing = false)

        val row = catalog.catalogQueries.selectLibraryHealth().executeAsOne()

        // 9 active projects (P1..P10 minus P8 archived).
        assertEquals(9L, row.total, "total should exclude archived")
        // Synced (dirty=0 AND local_rev=cloud_head_rev): P1, P2, P6, P7, P10.
        assertEquals(5L, row.synced, "synced should be 5 (P1, P2, P6, P7, P10)")
        // Sample-clean (no missing samples): P1, P3, P4, P5, P6, P10. P4 has zero sample rows
        // (we only seeded sync_state for it), so missing_count is NULL → counts as clean.
        assertEquals(6L, row.sample_clean, "sample_clean should be 6 (P1, P3, P4, P5, P6, P10)")
    }

    @Test
    fun emptyCatalogReturnsZeroes() {
        val row = catalog.catalogQueries.selectLibraryHealth().executeAsOne()
        assertEquals(0L, row.total)
        // SQLite SUM over zero rows is NULL — SQLDelight surfaces that as null Long.
        assertEquals(null, row.synced)
        assertEquals(null, row.sample_clean)
    }

    @Test
    fun onlyArchivedProjectsYieldsZeroTotal() {
        val p = seedProject("only-archived", archived = true)
        seedIdentity(p, "uuid-only")
        seedSyncState("uuid-only", dirty = 0, localRev = 1, cloudHeadRev = 1)
        val row = catalog.catalogQueries.selectLibraryHealth().executeAsOne()
        assertEquals(0L, row.total)
    }
}
