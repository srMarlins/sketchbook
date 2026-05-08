package com.sketchbook.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant

/**
 * Serialized snapshot manifest. Wire format — see design doc §"Wire format: Manifest" in
 * `docs/plans/2026-05-07-backend-generalization-design.md`.
 *
 * One file in the cloud bucket per snapshot. Named
 * `trees/<kind>/<tree_id>/manifests/<rev:08d>-<timestamp>-<host>.json`.
 *
 * **Encoding contract:** always serialize with `Json { encodeDefaults = true }` so consumers
 * never have to know which fields are required vs optional on the wire — every field is
 * present.
 */
@Serializable(with = ManifestSerializer::class)
data class Manifest(
    val ownerUserId: UserId = UserId.DEFAULT,
    val treeId: TrackedTreeId,
    val kind: TrackedTreeKind = TrackedTreeKind.Project,
    val rev: SnapshotRev,
    val parentRev: SnapshotRev? = null,
    val timestamp: Instant,
    val hostId: String,
    val hostName: String,
    val snapshotKind: SnapshotKind,
    val label: String? = null,
    val selfContained: Boolean = false,
    val files: Map<String, ManifestFile>,
    val stats: ManifestStats,
)

/**
 * One file's manifest entry. [deleted] tombstones survive merges so `Merge` conflict mode
 * (UserLibrary) preserves intentional deletions. [hash] is null when [deleted] is true.
 */
@Serializable
data class ManifestFile(
    @SerialName("hash") val hash: BlobHash? = null,
    @SerialName("size") val size: Long = 0,
    @SerialName("mtime") val mtime: Instant,
    @SerialName("deleted") val deleted: Boolean = false,
)

@Serializable
data class ManifestStats(
    @SerialName("file_count") val fileCount: Int,
    @SerialName("total_bytes") val totalBytes: Long,
    @SerialName("new_bytes") val newBytes: Long,
)

/**
 * Convenience accessor for callers that only deal with project trees and want a typed
 * [ProjectUuid] without manually unwrapping the [TrackedTreeId]. Throws on non-project
 * kinds — non-project callers should read [Manifest.treeId] directly.
 */
val Manifest.projectUuid: ProjectUuid
    get() {
        require(kind is TrackedTreeKind.Project) {
            "Manifest.projectUuid called on non-project kind=$kind; use treeId for non-project trees"
        }
        return ProjectUuid(treeId.value)
    }

/**
 * [Manifest] serializer that pins the wire JSON key names without leaking the field-rename
 * boilerplate into the user-facing data class. The wire shape carries a `"v": 2` discriminator
 * for forward-compat detection by future readers; this codebase only emits and reads v=2.
 */
internal object ManifestSerializer : KSerializer<Manifest> {
    private val wire = ManifestWire.serializer()

    override val descriptor: SerialDescriptor = wire.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Manifest,
    ) {
        wire.serialize(encoder, value.toWire())
    }

    override fun deserialize(decoder: Decoder): Manifest = wire.deserialize(decoder).toDomain()
}

@Serializable
private data class ManifestWire(
    @SerialName("v") val version: Int = 2,
    @SerialName("owner_user_id") val ownerUserId: UserId = UserId.DEFAULT,
    @SerialName("tree_id") val treeId: TrackedTreeId,
    @SerialName("tree_kind") val kind: TrackedTreeKind,
    @SerialName("rev") val rev: SnapshotRev,
    @SerialName("parent_rev") val parentRev: SnapshotRev? = null,
    @SerialName("timestamp") val timestamp: Instant,
    @SerialName("host_id") val hostId: String,
    @SerialName("host_name") val hostName: String,
    @SerialName("snapshot_kind") val snapshotKind: SnapshotKind,
    @SerialName("label") val label: String? = null,
    @SerialName("self_contained") val selfContained: Boolean = false,
    @SerialName("files") val files: Map<String, ManifestFile>,
    @SerialName("stats") val stats: ManifestStats,
)

private fun Manifest.toWire(): ManifestWire =
    ManifestWire(
        ownerUserId = ownerUserId,
        treeId = treeId,
        kind = kind,
        rev = rev,
        parentRev = parentRev,
        timestamp = timestamp,
        hostId = hostId,
        hostName = hostName,
        snapshotKind = snapshotKind,
        label = label,
        selfContained = selfContained,
        files = files,
        stats = stats,
    )

private fun ManifestWire.toDomain(): Manifest =
    Manifest(
        ownerUserId = ownerUserId,
        treeId = treeId,
        kind = kind,
        rev = rev,
        parentRev = parentRev,
        timestamp = timestamp,
        hostId = hostId,
        hostName = hostName,
        snapshotKind = snapshotKind,
        label = label,
        selfContained = selfContained,
        files = files,
        stats = stats,
    )
