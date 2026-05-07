package com.sketchbook.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ManifestTest {
    // Manifests are always serialized with defaults included so consumers never have to know
    // which fields are required vs optional on the wire. See Manifest.kdoc.
    private val json =
        Json {
            prettyPrint = false
            ignoreUnknownKeys = false
            encodeDefaults = true
        }

    private fun fixture(): Manifest =
        Manifest(
            ownerUserId = UserId("default"),
            treeId = TrackedTreeId("01HZQX5N3M8F9G2K7B1A6Y4WCE"),
            kind = TrackedTreeKind.Project,
            rev = SnapshotRev(47),
            parentRev = SnapshotRev(46),
            timestamp = Instant.parse("2026-05-05T14:22:31.412Z"),
            hostId = "macstudio-9d4c",
            hostName = "MacStudio",
            snapshotKind = SnapshotKind.Auto,
            label = null,
            selfContained = false,
            files =
                mapOf(
                    "Project.als" to
                        ManifestFile(
                            hash = BlobHash("b3:" + "1f2c".repeat(16)),
                            size = 312488,
                            mtime = Instant.parse("2026-05-05T14:22:30.000Z"),
                        ),
                    "Samples/Imported/k.wav" to
                        ManifestFile(
                            hash = BlobHash("b3:" + "9a07".repeat(16)),
                            size = 4189112,
                            mtime = Instant.parse("2026-05-05T14:00:00.000Z"),
                        ),
                ),
            stats = ManifestStats(fileCount = 217, totalBytes = 4831244012L, newBytes = 312488L),
        )

    @Test
    fun roundTripsThroughJson() {
        val original = fixture()
        val text = json.encodeToString(Manifest.serializer(), original)
        val decoded = json.decodeFromString(Manifest.serializer(), text)
        assertEquals(original, decoded)
    }

    @Test
    fun encodesShortVersionFieldName() {
        val text = json.encodeToString(Manifest.serializer(), fixture())
        // v not version — wire-stable per design doc.
        assertEquals(true, text.contains("\"v\":2"))
        assertEquals(false, text.contains("\"version\":"))
    }

    @Test
    fun encodesSnakeCaseFields() {
        val text = json.encodeToString(Manifest.serializer(), fixture())
        for (field in listOf(
            "owner_user_id",
            "tree_id",
            "tree_kind",
            "snapshot_kind",
            "parent_rev",
            "host_id",
            "host_name",
            "self_contained",
            "file_count",
            "total_bytes",
            "new_bytes",
        )) {
            assertEquals(true, text.contains("\"$field\""), "missing wire field $field")
        }
        // The renamed v=1 field names must NOT appear in v=2 output.
        assertEquals(false, text.contains("\"project_uuid\""))
    }

    @Test
    fun encodesSnapshotKindAsLowercase() {
        val text = json.encodeToString(Manifest.serializer(), fixture().copy(snapshotKind = SnapshotKind.Branch))
        assertEquals(true, text.contains("\"snapshot_kind\":\"branch\""))
    }

    @Test
    fun encodesTreeKindAsLowercase() {
        val text = json.encodeToString(Manifest.serializer(), fixture())
        assertEquals(true, text.contains("\"tree_kind\":\"project\""))
    }

    @Test
    fun tombstoneFieldRoundTrips() {
        val original =
            fixture().copy(
                files =
                    mapOf(
                        "deleted.adv" to
                            ManifestFile(
                                hash = BlobHash("b3:" + "1f2c".repeat(16)),
                                size = 0,
                                mtime = Instant.parse("2026-05-05T14:22:30.000Z"),
                                deleted = true,
                            ),
                    ),
            )
        val text = json.encodeToString(Manifest.serializer(), original)
        assertEquals(true, text.contains("\"deleted\":true"))
        val decoded = json.decodeFromString(Manifest.serializer(), text)
        assertEquals(true, decoded.files["deleted.adv"]!!.deleted)
    }

    @Test
    fun acceptsV1SampleViaBackCompatDecoder() {
        // v=1 wire format — what older binaries persist before the migration runs. The
        // v=1 decoder synthesizes `tree_kind = project` and uses `project_uuid` as the
        // tree id so the in-memory shape matches what a migrated bucket would carry.
        val sample =
            """
            {
              "v": 1,
              "owner_user_id": "default",
              "project_uuid": "01HZQX5N3M8F9G2K7B1A6Y4WCE",
              "rev": 47,
              "parent_rev": 46,
              "timestamp": "2026-05-05T14:22:31.412Z",
              "host_id": "macstudio-9d4c",
              "host_name": "MacStudio",
              "kind": "auto",
              "label": null,
              "self_contained": false,
              "files": {
                "Project.als": {"hash":"b3:${"1f2c".repeat(16)}","size":312488,"mtime":"2026-05-05T14:22:30.000Z"}
              },
              "stats": {"file_count": 217, "total_bytes": 4831244012, "new_bytes": 312488}
            }
            """.trimIndent()
        val decoded = json.decodeFromString(Manifest.serializer(), sample)
        assertEquals(SnapshotRev(47), decoded.rev)
        assertEquals(SnapshotKind.Auto, decoded.snapshotKind)
        assertEquals(TrackedTreeKind.Project, decoded.kind)
        assertEquals(TrackedTreeId("01HZQX5N3M8F9G2K7B1A6Y4WCE"), decoded.treeId)
        assertEquals(1, decoded.files.size)
    }

    @Test
    fun decodesV2Roundtrip() {
        val original = fixture()
        val text = json.encodeToString(Manifest.serializer(), original)
        val decoded = json.decodeFromString(Manifest.serializer(), text)
        assertEquals(original, decoded)
    }
}
