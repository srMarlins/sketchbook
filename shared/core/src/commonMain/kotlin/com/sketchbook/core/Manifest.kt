package com.sketchbook.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/**
 * Serialized snapshot manifest. Wire format — see design doc §"Wire format: Manifest" in
 * `docs/plans/2026-05-07-backend-generalization-design.md`.
 *
 * One file in the cloud bucket per snapshot. Named
 * `trees/<kind>/<tree_id>/manifests/<rev:08d>-<timestamp>-<host>.json` from v=2 forward.
 *
 * **Wire compatibility.** [version] is `"v"` on the wire. v=1 manifests have `project_uuid`
 * + `kind` (a [SnapshotKind]); v=2 introduces `tree_id`, `tree_kind`, and `snapshot_kind`.
 * Decoding accepts both via [ManifestSerializer] which peeks `v` and dispatches; encoding
 * always emits v=2. The v=1 fallback exists to bridge the rolling-upgrade window where one
 * machine has migrated and a sibling host still writes v=1 — see the design doc's
 * "Migration UX" section.
 *
 * **Encoding contract:** always serialize with `Json { encodeDefaults = true }` so consumers
 * never have to know which fields are required vs optional on the wire — every field is
 * present.
 */
@Serializable(with = ManifestSerializer::class)
data class Manifest(
    val version: Int = WIRE_VERSION,
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
) {
    companion object {
        /** Wire-format version emitted by every encoder in this codebase. v=1 is read-only. */
        const val WIRE_VERSION: Int = 2
    }
}

/**
 * One file's manifest entry. [deleted] tombstones survive merges so `Merge` conflict mode
 * (UserLibrary) preserves intentional deletions. v=1 manifests never set [deleted]; the
 * field is an additive default so existing producers and decoders continue to round-trip
 * unchanged.
 *
 * **Wire shape on tombstones (v=2):** [hash] is null when [deleted] is true. v=1 manifests
 * always carried a hash; the field stays nullable in the v=2 shape but project producers
 * keep emitting hashes for non-deleted files and reject deserializing tombstones into v=1
 * encoders (we never write v=1 from this codebase post-commit-9).
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
 * Polymorphic [Manifest] serializer. Encode emits v=2; decode accepts both v=1 and v=2 by
 * peeking the `v` discriminator on the JSON element. Json-only — non-JSON encoders fall
 * back to writing the v=2 surrogate which works the same way the auto-generated serializer
 * would.
 */
internal object ManifestSerializer : KSerializer<Manifest> {
    private val v2 = ManifestV2.serializer()
    private val v1 = ManifestV1.serializer()

    override val descriptor: SerialDescriptor = v2.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Manifest,
    ) {
        v2.serialize(encoder, value.toV2())
    }

    override fun deserialize(decoder: Decoder): Manifest {
        if (decoder !is JsonDecoder) {
            // Non-JSON formats: assume v=2 wire shape. Sketchbook only persists manifests
            // through JSON today; this branch exists so the auto-generated serializer
            // contract still composes (e.g. tooling that walks descriptors) without
            // requiring a JSON dependency.
            return v2.deserialize(decoder).toDomain()
        }
        val element: JsonElement = decoder.decodeJsonElement()
        val obj = element.jsonObject
        val version = obj["v"]?.jsonPrimitive?.intOrNull ?: 1
        return if (version <= 1) {
            FALLBACK_JSON.decodeFromJsonElement(v1, element).toDomain()
        } else {
            FALLBACK_JSON.decodeFromJsonElement(v2, element).toDomain()
        }
    }

    /**
     * Used only inside [deserialize] when we already have the JsonElement. Not the JSON
     * instance the caller is using — peeking the element doesn't preserve the parent
     * decoder's options, so we use a permissive instance for the synthetic re-parse.
     */
    private val FALLBACK_JSON: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
}

/**
 * Wire shape for v=2 manifests. The Kotlin field names match the design doc; the
 * `@SerialName` annotations pin the snake-case JSON keys.
 */
@Serializable
private data class ManifestV2(
    @SerialName("v") val version: Int = Manifest.WIRE_VERSION,
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

/**
 * Wire shape for v=1 manifests — read-only. Decoded into the v=2 in-memory [Manifest] by
 * synthesizing `kind = TrackedTreeKind.Project` (v=1 only ever held projects) and reusing
 * the project_uuid as the tree id (the migration tool keeps the same id when it rewrites
 * paths to the v=2 layout).
 */
@Serializable
private data class ManifestV1(
    @SerialName("v") val version: Int = 1,
    @SerialName("owner_user_id") val ownerUserId: UserId = UserId.DEFAULT,
    @SerialName("project_uuid") val projectUuid: ProjectUuid,
    @SerialName("rev") val rev: SnapshotRev,
    @SerialName("parent_rev") val parentRev: SnapshotRev? = null,
    @SerialName("timestamp") val timestamp: Instant,
    @SerialName("host_id") val hostId: String,
    @SerialName("host_name") val hostName: String,
    @SerialName("kind") val snapshotKind: SnapshotKind,
    @SerialName("label") val label: String? = null,
    @SerialName("self_contained") val selfContained: Boolean = false,
    @SerialName("files") val files: Map<String, ManifestFile>,
    @SerialName("stats") val stats: ManifestStats,
)

private fun Manifest.toV2(): ManifestV2 =
    ManifestV2(
        version = Manifest.WIRE_VERSION,
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

private fun ManifestV2.toDomain(): Manifest =
    Manifest(
        version = Manifest.WIRE_VERSION,
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

private fun ManifestV1.toDomain(): Manifest =
    Manifest(
        version = Manifest.WIRE_VERSION,
        ownerUserId = ownerUserId,
        treeId = TrackedTreeId(projectUuid.value),
        kind = TrackedTreeKind.Project,
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
