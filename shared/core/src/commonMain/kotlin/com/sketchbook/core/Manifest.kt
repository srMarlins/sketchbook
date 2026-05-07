package com.sketchbook.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Serialized snapshot manifest. Wire format — see design doc §3.1.
 *
 * One file in the cloud bucket per snapshot. Named
 * `manifests/<project_uuid>/<rev:08d>-<timestamp>-<host>.json`.
 *
 * **Compatibility:** [version] is `"v"` on the wire (short to keep manifests cheap). Bumping
 * the version is a wire break; current value is `1`.
 *
 * **Serialization:** always encode with `Json { encodeDefaults = true }` so consumers never have
 * to know which fields are required vs optional on the wire — every field is present.
 */
@Serializable
data class Manifest(
    @SerialName("v") val version: Int = 1,
    @SerialName("owner_user_id") val ownerUserId: UserId = UserId.DEFAULT,
    @SerialName("project_uuid") val projectUuid: ProjectUuid,
    @SerialName("rev") val rev: SnapshotRev,
    @SerialName("parent_rev") val parentRev: SnapshotRev? = null,
    @SerialName("timestamp") val timestamp: Instant,
    @SerialName("host_id") val hostId: String,
    @SerialName("host_name") val hostName: String,
    @SerialName("kind") val kind: SnapshotKind,
    @SerialName("label") val label: String? = null,
    @SerialName("self_contained") val selfContained: Boolean = false,
    @SerialName("files") val files: Map<String, ManifestFile>,
    @SerialName("stats") val stats: ManifestStats,
)

/**
 * One file's manifest entry. [deleted] tombstones survive merges so `Merge` conflict mode (v=2,
 * UserLibrary) preserves intentional deletions. v=1 manifests never set [deleted]; the field is
 * an additive default so existing producers and decoders continue to round-trip unchanged.
 *
 * **Wire shape on tombstones (v=2):** `hash` becomes nullable when `deleted=true`. v=1 manifests
 * still always carry a hash; [hash] stays non-null in the v=1 era and gets relaxed when the
 * v=2 wire format lands (see design §"Wire format: Manifest").
 */
@Serializable
data class ManifestFile(
    @SerialName("hash") val hash: BlobHash,
    @SerialName("size") val size: Long,
    @SerialName("mtime") val mtime: Instant,
    @SerialName("deleted") val deleted: Boolean = false,
)

@Serializable
data class ManifestStats(
    @SerialName("file_count") val fileCount: Int,
    @SerialName("total_bytes") val totalBytes: Long,
    @SerialName("new_bytes") val newBytes: Long,
)
