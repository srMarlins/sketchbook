package com.sketchbook.repo

import com.sketchbook.catalog.db.Catalog
import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.Generation
import com.sketchbook.core.AppScope
import com.sketchbook.core.CloudDocKey
import com.sketchbook.core.Os
import com.sketchbook.core.PluginFormat
import com.sketchbook.core.SketchbookError
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Cross-host plugin / OS profile published into `<tenant>/profile/`. Three CloudDocs:
 *
 * - `plugin_manifest_<host_id>.json` — host-sliced. Each host writes only its own file;
 *   readers list-prefix `plugin_manifest_*.json` and union. **No write conflicts.**
 * - `machines.json` — host roster (read-merge-CAS).
 * - `ableton_versions.json` — per-project Live version (read-merge-CAS, future).
 *
 * Composition rule: each host's slice is the union of `project_plugins` (commit 4) and
 * `user_library_plugins` (commit 12) on its local catalog — so plugins that only show up in
 * UL templates / default racks count toward the bootstrap "needs install" list. Dedup by
 * `(name, format)`; "installed somewhere" if any row reports installed=true.
 *
 * Wire format is intentionally narrow: just what the bootstrap UI needs. Vendor-name and
 * "first seen in project" — listed in the design doc — are deferred until the catalog
 * actually carries vendor strings.
 */
interface MachineProfileStore {
    /**
     * Build this host's slice of the plugin manifest from the local catalog and write it to
     * `<tenant>/profile/plugin_manifest_<host_id>.json`. Idempotent — every call overwrites
     * the prior slice for this host. Throws on cloud / encode failure;
     * `CancellationException` propagates.
     */
    suspend fun publishHostSlice(
        hostId: String,
        hostName: String,
        os: Os,
    ): HostPluginManifest

    /**
     * List every host's slice and union them. Used by the plugin checklist screen
     * (commit 15) to show "needs install on this OS" rows.
     */
    suspend fun composeUnion(): UnionedPluginManifest

    /**
     * Add this host to `<tenant>/profile/machines.json` (read-merge-CAS). On registration
     * conflicts, retries up to [REGISTER_MAX_RETRIES] before bailing. Throws
     * [SketchbookError.Conflict] when retries are exhausted, or the underlying cloud error
     * for non-conflict failures; `CancellationException` propagates.
     */
    suspend fun registerMachine(entry: MachineEntry)

    /** Read the machines roster — used to drive the "Windows is still on v=1" banner. */
    suspend fun listMachines(): List<MachineEntry>

    companion object {
        const val REGISTER_MAX_RETRIES: Int = 5

        fun pluginManifestKey(hostId: String): CloudDocKey = CloudDocKey("profile/plugin_manifest_$hostId.json")

        val PLUGIN_MANIFEST_PREFIX: CloudDocKey.Prefix = CloudDocKey.Prefix("profile/plugin_manifest_")
        val MACHINES_KEY: CloudDocKey = CloudDocKey("profile/machines.json")
    }
}

@Serializable
data class HostPluginManifest(
    @SerialName("v") val version: Int = 1,
    @SerialName("host_id") val hostId: String,
    @SerialName("host_name") val hostName: String,
    @SerialName("os") val os: Os,
    @SerialName("computed_at") val computedAt: Instant,
    @SerialName("plugins") val plugins: List<HostPluginEntry>,
)

@Serializable
data class HostPluginEntry(
    @SerialName("name") val name: String,
    @SerialName("format") val format: PluginFormat,
    @SerialName("installed") val installed: Boolean,
)

/**
 * Result of [MachineProfileStore.composeUnion]. [perHost] preserves the original slices so
 * the UI can answer "this plugin is installed on Mac but not Windows"; [union] is the dedupe
 * across hosts, with `installed` set if **any** host reported it installed.
 */
data class UnionedPluginManifest(
    val perHost: List<HostPluginManifest>,
    val union: List<HostPluginEntry>,
)

@Serializable
data class MachineEntry(
    @SerialName("host_id") val hostId: String,
    @SerialName("host_name") val hostName: String,
    @SerialName("os") val os: Os,
    @SerialName("last_seen_at") val lastSeenAt: Instant,
    @SerialName("binary_version") val binaryVersion: String,
)

@Serializable
internal data class MachinesDoc(
    @SerialName("v") val version: Int = 1,
    @SerialName("machines") val machines: List<MachineEntry> = emptyList(),
)

/**
 * Bound at AppScope today because every other repository in the desktop graph is. The
 * `CloudBackend` dep is per-user (`SwappableSyncQueue`-driven), and Metro will only attempt
 * to resolve this binding when something downstream requests `MachineProfileStore` — at
 * which point the graph needs a `CloudBackend` provider too.
 *
 * Promoted to a true `UserScope` binding once
 * https://github.com/srMarlins/sketchbook/issues/130 lands.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class CloudMachineProfileStore(
    private val cloud: CloudBackend,
    private val catalog: Catalog,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher,
) : MachineProfileStore {
    override suspend fun publishHostSlice(
        hostId: String,
        hostName: String,
        os: Os,
    ): HostPluginManifest {
        val plugins = composeHostSlice()
        val manifest =
            HostPluginManifest(
                hostId = hostId,
                hostName = hostName,
                os = os,
                computedAt = clock.now(),
                plugins = plugins,
            )
        val bytes = JSON.encodeToString(HostPluginManifest.serializer(), manifest).encodeToByteArray()
        // Per-host file — overwrites unconditionally. Two hosts publishing concurrently touch
        // different keys, so there's no conflict to retry against. Skipping the read here
        // saves a round-trip per call; the previous "read then write with expected = existing
        // generation" was a half-CAS that failed silently on a same-host race rather than
        // actually protecting anything.
        cloud
            .writeDoc(
                key = MachineProfileStore.pluginManifestKey(hostId),
                expected = null,
                bytes = bytes,
            ).getOrThrow()
        return manifest
    }

    override suspend fun composeUnion(): UnionedPluginManifest {
        val refs = cloud.listDocs(MachineProfileStore.PLUGIN_MANIFEST_PREFIX)
        // Fan out the per-host doc reads in parallel — N round-trips serialized was a wall-time
        // floor of `N * latency` on accounts with several machines registered. Structured
        // concurrency cancels siblings if any read throws; decode errors get downgraded to
        // null per-host so a single corrupt slice doesn't poison the whole union.
        val perHost =
            coroutineScope {
                refs
                    .map { ref -> async { readAndDecodeHostManifest(ref) } }
                    .awaitAll()
                    .filterNotNull()
            }
        val union = mutableMapOf<PluginKey, HostPluginEntry>()
        for (slice in perHost) {
            for (entry in slice.plugins) {
                val key = PluginKey(entry.name, entry.format)
                val current = union[key]
                union[key] =
                    if (current == null) {
                        entry
                    } else {
                        // OR semantics: installed anywhere = installed in the union.
                        current.copy(installed = current.installed || entry.installed)
                    }
            }
        }
        return UnionedPluginManifest(perHost = perHost, union = union.values.toList())
    }

    private suspend fun readAndDecodeHostManifest(ref: com.sketchbook.cloud.CloudDocRef): HostPluginManifest? {
        val read = cloud.readDoc(ref.key) ?: return null
        return try {
            JSON.decodeFromString(HostPluginManifest.serializer(), read.bytes.decodeToString())
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // A corrupt slice is forward-compat fodder (older binary reading newer fields it
            // can't parse) or a one-off bad write. Drop it from the union rather than failing
            // the whole compose; surfacing it via the journal is tracked in #132.
            null
        }
    }

    override suspend fun registerMachine(entry: MachineEntry) {
        repeat(MachineProfileStore.REGISTER_MAX_RETRIES) { attempt ->
            val current = cloud.readDoc(MachineProfileStore.MACHINES_KEY)
            val doc =
                if (current == null) {
                    MachinesDoc(machines = listOf(entry))
                } else {
                    val parsed = JSON.decodeFromString(MachinesDoc.serializer(), current.bytes.decodeToString())
                    val withoutSelf = parsed.machines.filterNot { it.hostId == entry.hostId }
                    parsed.copy(machines = withoutSelf + entry)
                }
            val expected = current?.generation ?: Generation.ZERO
            val bytes = JSON.encodeToString(MachinesDoc.serializer(), doc).encodeToByteArray()
            val result = cloud.writeDoc(MachineProfileStore.MACHINES_KEY, expected, bytes)
            result
                .onSuccess { return }
                .onFailure { err ->
                    if (err !is SketchbookError.Conflict) throw err
                    // CAS conflict: jittered exponential backoff before re-reading. Without
                    // this, a herd of machines waking together (mass software-update day) hot-
                    // loops on contention and starves out the cluster. Caps at attempt index 5
                    // to keep the worst-case latency bounded if the cloud is genuinely busy.
                    delay(backoffDelayMillis(attempt))
                }
        }
        throw SketchbookError.Conflict("machines.json CAS retries exhausted")
    }

    override suspend fun listMachines(): List<MachineEntry> {
        val read = cloud.readDoc(MachineProfileStore.MACHINES_KEY) ?: return emptyList()
        return try {
            JSON.decodeFromString(MachinesDoc.serializer(), read.bytes.decodeToString()).machines
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Internal helper: build this host's plugin slice from the local catalog, unioning
     * `project_plugins` and `user_library_plugins` and applying OR-semantics on
     * `is_installed`. Wrapped in `withContext(ioDispatcher)` because SqlDelight
     * `executeAsList` is blocking and `publishHostSlice` is a `suspend fun` reachable from
     * UI dispatchers.
     */
    private suspend fun composeHostSlice(): List<HostPluginEntry> =
        withContext(ioDispatcher) {
            val byKey = mutableMapOf<PluginKey, HostPluginEntry>()
            val projectRows = catalog.catalogQueries.selectAllDistinctProjectPluginsWithInstalled().executeAsList()
            for (row in projectRows) {
                // Map the SQL `plugin_type` text column to the typed PluginFormat enum at the
                // boundary. Live's `"component"` alias for AU shows up here for legacy rows.
                val format = PluginFormat.fromWireWithAliases(row.plugin_type)
                val key = PluginKey(row.plugin_name, format)
                byKey[key] =
                    HostPluginEntry(
                        name = row.plugin_name,
                        format = format,
                        installed = (row.is_installed ?: 0L) > 0L,
                    )
            }
            val ulRows = catalog.catalogQueries.selectAllDistinctUserLibraryPluginsWithInstalled().executeAsList()
            for (row in ulRows) {
                val format = PluginFormat.fromWireWithAliases(row.plugin_type)
                val key = PluginKey(row.plugin_name, format)
                val existing = byKey[key]
                byKey[key] =
                    HostPluginEntry(
                        name = row.plugin_name,
                        format = format,
                        installed = (existing?.installed ?: false) || ((row.is_installed ?: 0L) > 0L),
                    )
            }
            byKey.values.toList()
        }

    private companion object {
        val JSON =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
                prettyPrint = false
            }

        /**
         * Backoff for [registerMachine]'s CAS retry loop. Base 50 ms doubled per attempt
         * (capped at attempt index 5 → 1.6 s) with up to 50 ms of random jitter to break
         * herd patterns on mass-update events. Pattern follows
         * https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/.
         */
        fun backoffDelayMillis(attempt: Int): Long {
            val capped = attempt.coerceAtMost(5)
            val base = 50L shl capped
            return base + Random.nextLong(0, 50)
        }
    }
}

/**
 * Internal map key for `(plugin_name, format)` rows. Promoted from `Pair<String,String>`
 * so the field names show up in stack traces and `equals`/`hashCode` come from the data class
 * for free.
 */
internal data class PluginKey(
    val name: String,
    val format: PluginFormat,
)
