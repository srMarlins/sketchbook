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
            version = 1,
            ownerUserId = UserId("default"),
            projectUuid = ProjectUuid("01HZQX5N3M8F9G2K7B1A6Y4WCE"),
            rev = SnapshotRev(47),
            parentRev = SnapshotRev(46),
            timestamp = Instant.parse("2026-05-05T14:22:31.412Z"),
            hostId = "macstudio-9d4c",
            hostName = "MacStudio",
            kind = SnapshotKind.Auto,
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
        // v not version — wire-stable per design doc §3.1.
        assertEquals(true, text.contains("\"v\":1"))
        assertEquals(false, text.contains("\"version\":"))
    }

    @Test
    fun encodesSnakeCaseFields() {
        val text = json.encodeToString(Manifest.serializer(), fixture())
        for (field in listOf(
            "owner_user_id",
            "project_uuid",
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
    }

    @Test
    fun encodesKindAsLowercase() {
        val text = json.encodeToString(Manifest.serializer(), fixture().copy(kind = SnapshotKind.Branch))
        assertEquals(true, text.contains("\"kind\":\"branch\""))
    }

    @Test
    fun acceptsKnownDesignDocSample() {
        // Subset of the example in design doc §3.1, with 64-char hashes.
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
        assertEquals(SnapshotKind.Auto, decoded.kind)
        assertEquals(1, decoded.files.size)
    }
}
