package com.sketchbook.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogFtsTest {

    private fun seed(): Pair<com.sketchbook.catalog.db.Catalog, CatalogFts> {
        val handle = CatalogDb.openInMemory()
        val catalog = handle.catalog
        val fts = CatalogFts(handle.driver)
        // Five fake projects. Vary on `name` + `plugin_names` + `sample_filenames` so we can
        // disambiguate which gets the highest bm25 score for "kick".
        val rows = listOf(
            Triple("kick_drum_lab", "Drumbus Saturator", "kick.wav snare.wav hat.wav"),
            Triple("punchy_kick_test", "Eq8 Compressor2", "kick_thump.wav"),
            Triple("ambient_pad", "Reverb Echo", "pad.wav"),
            Triple("bass_track", "Limiter", "sub.wav"),
            Triple("kicks_sample_pack", "Operator", "kick.wav kick2.wav kick3.wav"),
        )
        rows.forEachIndexed { idx, (name, plug, samp) ->
            catalog.catalogQueries.insertOrReplaceProject(
                path = "/lib/$name.als",
                name = name,
                parent_dir = "/lib",
                tempo = 120.0,
                time_sig_num = 4,
                time_sig_den = 4,
                track_count = 1,
                audio_tracks = 1,
                midi_tracks = 0,
                return_tracks = 0,
                live_version = "12.0.0",
                last_modified = idx.toDouble(),
                last_scanned = idx.toDouble(),
                parse_status = "ok",
                mac_paths_count = 0,
            )
            val id = catalog.catalogQueries.selectProjectIdByPath("/lib/$name.als").executeAsOne()
            fts.upsert(
                rowid = id,
                name = name,
                parentDir = "/lib",
                pluginNames = plug,
                sampleFilenames = samp,
                notes = "",
            )
        }
        return catalog to fts
    }

    @Test
    fun matchReturnsRelevantRows() {
        val (_, fts) = seed()
        val ids = fts.search("kick")
        assertTrue(ids.size >= 3, "expected ≥3 hits for 'kick', got ${ids.size}")
    }

    @Test
    fun ranksTokenDensityHigher() {
        // Project with three "kick" sample-filename hits + "kick" in name should outrank one
        // with only "kick" in the name.
        val (_, fts) = seed()
        val ids = fts.search("kick")
        // Lookup names for the returned rowids.
        val idToName = listOf(
            "kick_drum_lab", "punchy_kick_test", "ambient_pad", "bass_track", "kicks_sample_pack",
        ).withIndex().associate { (i, n) -> (i + 1L) to n }
        val orderedNames = ids.map { idToName[it] ?: "?" }
        // The "kicks_sample_pack" entry has 3 kick* sample filenames + "kicks" in name → high density.
        // The "kick_drum_lab" entry has 1 kick.wav + "kick" in name.
        assertTrue("kicks_sample_pack" in orderedNames.take(2), "expected dense match in top 2; got $orderedNames")
    }

    @Test
    fun deleteRemovesFromIndex() {
        val (catalog, fts) = seed()
        val rowid = catalog.catalogQueries.selectProjectIdByPath("/lib/punchy_kick_test.als").executeAsOne()
        fts.delete(rowid)
        val remaining = fts.search("kick")
        assertEquals(false, rowid in remaining)
    }

    @Test
    fun honorsExactPluginNameMatches() {
        val (_, fts) = seed()
        val ids = fts.search("Compressor2")
        assertEquals(1, ids.size)
    }
}
