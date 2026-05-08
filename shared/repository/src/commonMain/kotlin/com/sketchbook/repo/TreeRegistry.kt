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
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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
     * concurrently): re-read, re-mutate, re-write, up to [REGISTER_MAX_RETRIES] times.
     * Returns a [RegisterOutcome] so callers can pattern-match on [RegisterOutcome.Conflict]
     * (CAS retries exhausted — a meaningful domain signal) vs [RegisterOutcome.Failed] (other
     * I/O / decode errors). Cancellation propagates as `CancellationException`; structured
     * concurrency keeps working without callers having to unwrap a `Result.failure`.
     */
    suspend fun register(
        kind: TrackedTreeKind,
        scopeKey: String,
        displayName: String,
        treeId: TrackedTreeId,
        ownerUserId: UserId = UserId.DEFAULT,
        createdByHost: String,
    ): RegisterOutcome<TreeRegistryEntry>

    /**
     * Bulk-register multiple trees in a single registry-doc round-trip. The migrator (commit
     * 10) calls this when seeding the registry from `project_identity` rows so it does one
     * CAS write instead of N (one per project).
     *
     * Idempotent: entries whose `(kind, scopeKey)` already exist in the registry are
     * preserved unchanged; new entries are appended. CAS-retries on registry-doc conflict
     * up to [REGISTER_MAX_RETRIES]. Returns the resulting entries (existing + newly written)
     * in input order via [RegisterOutcome].
     */
    suspend fun registerAll(specs: List<RegisterSpec>): RegisterOutcome<List<TreeRegistryEntry>>

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

/**
 * Outcome of a [TreeRegistry.register] / [TreeRegistry.registerAll] call. Replaces
 * `kotlin.Result<T>` per #128 because the [Conflict] case is a meaningful domain signal
 * (CAS retries exhausted — caller can decide between bubbling, retrying with a longer
 * window, or surfacing to the user) that's distinct from arbitrary [Failed] I/O errors.
 *
 * Cancellation propagates as `CancellationException` from the suspend call; this type is
 * only used to model success + the two recoverable failure modes.
 */
sealed interface RegisterOutcome<out T> {
    data class Ok<T>(
        val value: T,
    ) : RegisterOutcome<T>

    /** Registry-doc CAS retries exhausted. Caller may retry the whole call later. */
    data object Conflict : RegisterOutcome<Nothing>

    /** Non-conflict error (network, decode, malformed input). */
    data class Failed(
        val cause: Throwable,
    ) : RegisterOutcome<Nothing>
}

/** Convenience: throw on Conflict / Failed for callers that have no per-failure-mode recovery. */
fun <T> RegisterOutcome<T>.getOrThrow(): T =
    when (this) {
        is RegisterOutcome.Ok -> value
        RegisterOutcome.Conflict -> throw SketchbookError.Conflict("registry CAS retries exhausted")
        is RegisterOutcome.Failed -> throw cause
    }

/** Argument shape for [TreeRegistry.registerAll]. */
data class RegisterSpec(
    val kind: TrackedTreeKind,
    val scopeKey: String,
    val displayName: String,
    val treeId: TrackedTreeId,
    val ownerUserId: UserId = UserId.DEFAULT,
    val createdByHost: String,
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
    // Per-user lookup, not the backend itself. The AppScope binding caches this store as a
    // singleton; without the indirection it would also cache whichever CloudBackend was
    // current at first resolution, never refreshing after sign-out / sign-in.
    private val cloudProvider: CloudBackendProvider,
    private val catalog: Catalog,
    private val clock: Clock,
    // Default user-id for a freshly-created registry doc (when no entries exist yet). Real
    // per-user identity flows in via [register]'s `ownerUserId` argument; this is only used to
    // stamp the wire-format `owner_user_id` field on the empty-doc case. Will move into a
    // UserScope subgraph once #130 lands.
    private val ownerUserId: UserId,
) : TreeRegistry {
    private fun cloud(): CloudBackend =
        cloudProvider() ?: throw IllegalStateException("cloud not configured (no signed-in user / bucket)")

    override suspend fun fetch(): TreeRegistrySnapshot {
        val read = cloud().readDoc(TreeRegistry.REGISTRY_KEY)
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
        // Decode the collaborators_json column so [canRead] / [canWrite] checks against a
        // cached entry get the same answer they'd get against a freshly-fetched cloud doc.
        // Malformed JSON (shouldn't happen in practice) falls back to empty rather than
        // throwing — losing collaborators is a permission tightening, not a correctness break.
        // Try/catch instead of `runCatching` per CLAUDE.md: runCatching at suspend boundaries
        // catches `CancellationException` and breaks structured concurrency.
        val collaborators =
            try {
                JSON.decodeFromString(CollaboratorListSerializer, row.collaborators_json)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                emptyList()
            }
        return TreeRegistryEntry(
            treeId = TrackedTreeId(row.tree_id),
            kind = TrackedTreeKind.fromWire(row.tree_kind),
            scopeKey = row.scope_key,
            displayName = row.display_name.orEmpty(),
            ownerUserId = UserId(row.owner_user_id),
            collaborators = collaborators,
            createdAt = Instant.fromEpochMilliseconds(row.created_at),
            createdByHost = row.created_by_host,
        )
    }

    @Suppress("ReturnCount")
    // CAS retry loop has one early-return per failure mode (fetch error, idempotent hit,
    // write success, non-conflict write error) plus the loop-exhausted Conflict at the end.
    // Collapsing them through a single accumulator would obscure the control flow.
    override suspend fun register(
        kind: TrackedTreeKind,
        scopeKey: String,
        displayName: String,
        treeId: TrackedTreeId,
        ownerUserId: UserId,
        createdByHost: String,
    ): RegisterOutcome<TreeRegistryEntry> {
        repeat(TreeRegistry.REGISTER_MAX_RETRIES) {
            val current =
                try {
                    fetch()
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    return RegisterOutcome.Failed(t)
                }
            // Idempotency: same (kind, scopeKey) → return existing.
            current.entries.firstOrNull { it.kind == kind && it.scopeKey == scopeKey }?.let {
                return RegisterOutcome.Ok(it)
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
            val writeResult = cloud().writeDoc(TreeRegistry.REGISTRY_KEY, expected, bytes)
            writeResult
                .onSuccess {
                    refreshCache(doc.trees)
                    return RegisterOutcome.Ok(newEntry)
                }.onFailure { err ->
                    if (err !is SketchbookError.Conflict) return RegisterOutcome.Failed(err)
                    // Retry on CAS conflict.
                }
        }
        return RegisterOutcome.Conflict
    }

    @Suppress("ReturnCount")
    // Same shape as [register]: empty-input, fetch error, no-write idempotent path, write
    // success, non-conflict write error, plus loop-exhausted Conflict. Each early return
    // names a distinct outcome — flattening is worse than the rule violation.
    override suspend fun registerAll(specs: List<RegisterSpec>): RegisterOutcome<List<TreeRegistryEntry>> {
        if (specs.isEmpty()) return RegisterOutcome.Ok(emptyList())
        // The registry doc carries a single `owner_user_id` for the whole user. Specs with
        // mismatched owners would have silently inherited the first spec's owner and written
        // it into the doc — surface the misuse loudly.
        val owners = specs.map { it.ownerUserId }.toSet()
        require(owners.size == 1) {
            "registerAll specs must share an ownerUserId; got ${owners.map { it.value }}"
        }
        repeat(TreeRegistry.REGISTER_MAX_RETRIES) {
            val current =
                try {
                    fetch()
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    return RegisterOutcome.Failed(t)
                }
            val byKey = current.entries.associateBy { it.kind to it.scopeKey }.toMutableMap()
            val results = mutableListOf<TreeRegistryEntry>()
            var added = false
            for (spec in specs) {
                val k = spec.kind to spec.scopeKey
                val existing = byKey[k]
                if (existing != null) {
                    results += existing
                    continue
                }
                val entry =
                    TreeRegistryEntry(
                        treeId = spec.treeId,
                        kind = spec.kind,
                        scopeKey = spec.scopeKey,
                        displayName = spec.displayName,
                        ownerUserId = spec.ownerUserId,
                        createdAt = clock.now(),
                        createdByHost = spec.createdByHost,
                    )
                byKey[k] = entry
                results += entry
                added = true
            }
            if (!added) {
                // Nothing to write — every spec already had an entry; refresh cache so
                // observers see a consistent view and return.
                refreshCache(current.entries)
                return RegisterOutcome.Ok(results)
            }
            val merged = byKey.values.toList()
            val owner = specs.first().ownerUserId
            // Single-owner invariant enforced above — `specs.first().ownerUserId` is the
            // shared value, not a silent pick.
            val doc = TreeRegistryDoc(ownerUserId = owner, trees = merged)
            val expected = current.generation ?: Generation.ZERO
            val bytes = JSON.encodeToString(TreeRegistryDoc.serializer(), doc).encodeToByteArray()
            val writeResult = cloud().writeDoc(TreeRegistry.REGISTRY_KEY, expected, bytes)
            writeResult
                .onSuccess {
                    refreshCache(merged)
                    return RegisterOutcome.Ok(results)
                }.onFailure { err ->
                    if (err !is SketchbookError.Conflict) return RegisterOutcome.Failed(err)
                    // CAS conflict: re-read and retry.
                }
        }
        return RegisterOutcome.Conflict
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
            // Drop phantom entries: trees that used to be in the cloud doc but aren't anymore
            // (collab revoked, tree deleted upstream, etc.). Without this, lookup() returns
            // ghosts that pass permission checks for resources the user no longer owns. The
            // FK on dependent tree_* tables cascades.
            val keep = entries.map { it.treeId.value }
            catalog.catalogQueries.deleteTreeRegistryEntriesNotIn(keep)
            for (e in entries) {
                val collaboratorsJson =
                    JSON.encodeToString(CollaboratorListSerializer, e.collaborators)
                catalog.catalogQueries.upsertTreeRegistryEntry(
                    tree_id = e.treeId.value,
                    tree_kind = e.kind.wireName,
                    scope_key = e.scopeKey,
                    display_name = e.displayName,
                    owner_user_id = e.ownerUserId.value,
                    collaborators_json = collaboratorsJson,
                    created_at = e.createdAt.toEpochMilliseconds(),
                    created_by_host = e.createdByHost,
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

        val CollaboratorListSerializer = ListSerializer(Collaborator.serializer())
    }
}
