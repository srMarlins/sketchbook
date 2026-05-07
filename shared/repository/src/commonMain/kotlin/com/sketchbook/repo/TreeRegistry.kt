package com.sketchbook.repo

import com.sketchbook.catalog.db.Catalog
import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.Generation
import com.sketchbook.core.CloudDocKey
import com.sketchbook.core.CollabRole
import com.sketchbook.core.Collaborator
import com.sketchbook.core.SketchbookError
import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
import com.sketchbook.core.UserId
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Cloud-backed map from `(owner, kind, scope_key) → tree_id`. The single source of truth for
 * "what trees does this user own?" Stored at `<tenant>/registry.json` as a [com.sketchbook.cloud.CloudBackend]
 * CloudDoc with CAS via [Generation]. See `docs/plans/2026-05-07-backend-generalization-design.md`
 * §"TreeRegistry shape".
 *
 * Reads update the local [Catalog.tree_registry_cache] mirror so subsequent calls and other
 * components (timeline UI, plugin scanner) can answer queries without an HTTP round-trip.
 *
 * Multi-user-ready (v1.2): permission helpers consult [TreeRegistryEntry.ownerUserId] +
 * [TreeRegistryEntry.collaborators]. v1 always sets `ownerUserId = UserId.DEFAULT` and
 * `collaborators = []`; the same checks return true for the owner today and will gate on roles
 * once v1.2 lands.
 */
interface TreeRegistry {
    /**
     * Read the registry from cloud. Updates the local cache as a side effect. Returns the
     * current snapshot + the [Generation] for read-modify-write CAS. If no registry doc exists
     * yet (fresh account), returns an empty registry with `null` generation — the first
     * [register] call will mint one with a `must-not-exist` precondition.
     */
    suspend fun fetch(): TreeRegistrySnapshot

    /**
     * Find a tree by `(kind, scopeKey)` from the local cache. Cheap; does not touch the cloud.
     * Returns `null` when the registry has not been fetched yet or the tree is unknown.
     */
    suspend fun lookup(
        kind: TrackedTreeKind,
        scopeKey: String,
    ): TreeRegistryEntry?

    /**
     * Register a new tree. CAS retries on registry-doc conflict (another machine added a tree
     * concurrently): re-read, re-mutate, re-write, up to [REGISTER_MAX_RETRIES] times. Returns
     * the entry as written.
     */
    suspend fun register(
        kind: TrackedTreeKind,
        scopeKey: String,
        displayName: String,
        treeId: TrackedTreeId,
        ownerUserId: UserId = UserId.DEFAULT,
        createdByHost: String,
    ): Result<TreeRegistryEntry>

    /** True if [userId] can read [entry]. v1: always the owner; v1.2: + collaborators with any role. */
    fun canRead(
        entry: TreeRegistryEntry,
        userId: UserId,
    ): Boolean

    /** True if [userId] can write [entry]. v1: always the owner; v1.2: + collaborators with Write/Admin. */
    fun canWrite(
        entry: TreeRegistryEntry,
        userId: UserId,
    ): Boolean

    companion object {
        const val REGISTER_MAX_RETRIES: Int = 5
        val REGISTRY_KEY: CloudDocKey = CloudDocKey("registry.json")
    }
}

/** Snapshot of the registry doc + the cloud generation token for CAS. */
data class TreeRegistrySnapshot(
    val entries: List<TreeRegistryEntry>,
    val generation: Generation?,
)

@Serializable
data class TreeRegistryEntry(
    @SerialName("tree_id") val treeId: TrackedTreeId,
    @SerialName("kind") val kind: TrackedTreeKind,
    @SerialName("scope_key") val scopeKey: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("owner_user_id") val ownerUserId: UserId,
    @SerialName("collaborators") val collaborators: List<Collaborator> = emptyList(),
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("created_by_host") val createdByHost: String,
)

/** Wire shape of `<tenant>/registry.json`. */
@Serializable
internal data class TreeRegistryDoc(
    @SerialName("v") val version: Int = 1,
    @SerialName("owner_user_id") val ownerUserId: UserId,
    @SerialName("trees") val trees: List<TreeRegistryEntry> = emptyList(),
)

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class CloudTreeRegistry(
    private val cloud: CloudBackend,
    private val catalog: Catalog,
    private val clock: Clock = Clock.System,
    private val ownerUserId: UserId = UserId.DEFAULT,
) : TreeRegistry {
    override suspend fun fetch(): TreeRegistrySnapshot {
        val read = cloud.readDoc(TreeRegistry.REGISTRY_KEY)
        val (entries, gen) =
            if (read == null) {
                emptyList<TreeRegistryEntry>() to null
            } else {
                val doc = JSON.decodeFromString(TreeRegistryDoc.serializer(), read.bytes.decodeToString())
                doc.trees to read.generation
            }
        refreshCache(entries)
        return TreeRegistrySnapshot(entries, gen)
    }

    override suspend fun lookup(
        kind: TrackedTreeKind,
        scopeKey: String,
    ): TreeRegistryEntry? {
        val rows =
            catalog.catalogQueries
                .selectTreeRegistryByKindScope(tree_kind = kind.wireName, scope_key = scopeKey)
                .executeAsList()
        val row = rows.firstOrNull() ?: return null
        return TreeRegistryEntry(
            treeId = TrackedTreeId(row.tree_id),
            kind = TrackedTreeKind.fromWire(row.tree_kind),
            scopeKey = row.scope_key.orEmpty(),
            displayName = row.display_name.orEmpty(),
            ownerUserId = UserId(row.owner_user_id),
            collaborators = emptyList(),
            createdAt = Instant.fromEpochMilliseconds(row.updated_at),
            createdByHost = "",
        )
    }

    override suspend fun register(
        kind: TrackedTreeKind,
        scopeKey: String,
        displayName: String,
        treeId: TrackedTreeId,
        ownerUserId: UserId,
        createdByHost: String,
    ): Result<TreeRegistryEntry> {
        var attempt = 0
        while (attempt < TreeRegistry.REGISTER_MAX_RETRIES) {
            attempt += 1
            val current = fetch()
            // Idempotency: same (kind, scopeKey) → return existing.
            current.entries.firstOrNull { it.kind == kind && it.scopeKey == scopeKey }?.let {
                return Result.success(it)
            }
            val newEntry =
                TreeRegistryEntry(
                    treeId = treeId,
                    kind = kind,
                    scopeKey = scopeKey,
                    displayName = displayName,
                    ownerUserId = ownerUserId,
                    createdAt = clock.now(),
                    createdByHost = createdByHost,
                )
            val doc = TreeRegistryDoc(ownerUserId = ownerUserId, trees = current.entries + newEntry)
            val expected = current.generation ?: Generation.ZERO
            val bytes = JSON.encodeToString(TreeRegistryDoc.serializer(), doc).encodeToByteArray()
            val result = cloud.writeDoc(TreeRegistry.REGISTRY_KEY, expected, bytes)
            result
                .onSuccess {
                    refreshCache(doc.trees)
                    return Result.success(newEntry)
                }.onFailure { err ->
                    if (err !is SketchbookError.Conflict) return Result.failure(err)
                    // Retry on CAS conflict.
                }
        }
        return Result.failure(SketchbookError.Conflict("registry CAS retries exhausted"))
    }

    override fun canRead(
        entry: TreeRegistryEntry,
        userId: UserId,
    ): Boolean {
        if (entry.ownerUserId == userId) return true
        return entry.collaborators.any { it.userId == userId }
    }

    override fun canWrite(
        entry: TreeRegistryEntry,
        userId: UserId,
    ): Boolean {
        if (entry.ownerUserId == userId) return true
        return entry.collaborators.any { it.userId == userId && it.role != CollabRole.Read }
    }

    private fun refreshCache(entries: List<TreeRegistryEntry>) {
        val now = clock.now().toEpochMilliseconds()
        catalog.transaction {
            for (e in entries) {
                catalog.catalogQueries.upsertTreeRegistryEntry(
                    tree_id = e.treeId.value,
                    tree_kind = e.kind.wireName,
                    scope_key = e.scopeKey,
                    display_name = e.displayName,
                    owner_user_id = e.ownerUserId.value,
                    updated_at = now,
                )
            }
        }
    }

    private companion object {
        val JSON =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
                prettyPrint = false
            }
    }
}
