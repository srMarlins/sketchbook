package com.sketchbook.cloud

import com.sketchbook.core.Manifest
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Bridges the rolling-upgrade window where one host has migrated to v=2 paths and a sibling
 * still writes v=1 manifests. The decoder lives in the [Manifest] custom serializer; this
 * test pins the wire-level behavior end-to-end through the same [ManifestJson] instance the
 * cloud layer uses on the read path.
 */
class V1ManifestBackCompatTest {
    @Test
    fun decodesV1ManifestIntoV2InMemoryShape() {
        val v1 =
            """
            {
              "v": 1,
              "owner_user_id": "default",
              "project_uuid": "01HZQX5N3M8F9G2K7B1A6Y4WCE",
              "rev": 12,
              "parent_rev": 11,
              "timestamp": "2026-04-01T10:00:00Z",
              "host_id": "windows-jared",
              "host_name": "Windows-Jared",
              "kind": "auto",
              "label": null,
              "self_contained": false,
              "files": {
                "Project.als": {
                  "hash": "b3:${"ab".repeat(32)}",
                  "size": 1024,
                  "mtime": "2026-04-01T09:59:00Z"
                }
              },
              "stats": {"file_count": 1, "total_bytes": 1024, "new_bytes": 1024}
            }
            """.trimIndent()

        val decoded = ManifestJson.decodeFromString(Manifest.serializer(), v1)

        // v=1 only ever held projects: synthesized kind = Project, treeId from project_uuid.
        assertEquals(TrackedTreeKind.Project, decoded.kind)
        assertEquals(TrackedTreeId("01HZQX5N3M8F9G2K7B1A6Y4WCE"), decoded.treeId)
        assertEquals(SnapshotRev(12), decoded.rev)
        assertEquals(SnapshotKind.Auto, decoded.snapshotKind)
        assertEquals(1, decoded.files.size)
    }

    @Test
    fun reEncodesAsV2() {
        // Round-trip: read a v=1 sample, encode the in-memory result. The wire format
        // *out* is always v=2 — the legacy field names must not appear.
        val v1 =
            """
            {
              "v": 1,
              "owner_user_id": "default",
              "project_uuid": "01HZQX5N3M8F9G2K7B1A6Y4WCE",
              "rev": 1,
              "timestamp": "2026-05-05T12:00:00Z",
              "host_id": "h",
              "host_name": "H",
              "kind": "auto",
              "self_contained": false,
              "files": {},
              "stats": {"file_count": 0, "total_bytes": 0, "new_bytes": 0}
            }
            """.trimIndent()
        val decoded = ManifestJson.decodeFromString(Manifest.serializer(), v1)
        val reEncoded = ManifestJson.encodeToString(Manifest.serializer(), decoded)

        assertTrue(reEncoded.contains("\"v\":2"))
        assertTrue(reEncoded.contains("\"tree_id\":\"01HZQX5N3M8F9G2K7B1A6Y4WCE\""))
        assertTrue(reEncoded.contains("\"tree_kind\":\"project\""))
        assertTrue(reEncoded.contains("\"snapshot_kind\":\"auto\""))
        assertFalse(reEncoded.contains("\"project_uuid\""))
    }

    @Test
    fun decodesV2ManifestUnchanged() {
        // The decoder must accept v=2 too — when a sibling host has already migrated and
        // is producing v=2, our decoder is the same path as the v=1 fallback.
        val v2 =
            """
            {
              "v": 2,
              "owner_user_id": "default",
              "tree_id": "tt-01HZUL",
              "tree_kind": "user_library",
              "rev": 5,
              "timestamp": "2026-05-07T12:00:00Z",
              "host_id": "macstudio",
              "host_name": "MacStudio",
              "snapshot_kind": "auto",
              "self_contained": false,
              "files": {},
              "stats": {"file_count": 0, "total_bytes": 0, "new_bytes": 0}
            }
            """.trimIndent()
        val decoded = ManifestJson.decodeFromString(Manifest.serializer(), v2)

        assertEquals(TrackedTreeKind.UserLibrary, decoded.kind)
        assertEquals(TrackedTreeId("tt-01HZUL"), decoded.treeId)
    }
}
