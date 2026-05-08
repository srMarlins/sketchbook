package com.sketchbook.repo

import com.sketchbook.catalog.db.Catalog
import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.Generation
import com.sketchbook.core.CloudDocKey
import com.sketchbook.core.SketchbookError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
     * the prior slice for this host.
     */
    suspend fun publishHostSlice(
        hostId: String,
        hostName: String,
        os: String,
    ): Result<HostPluginManifest>

    /**
     * List every host's slice and union them. Used by the plugin checklist screen
     * (commit 15) to show "needs install on this OS" rows.
     */
    suspend fun composeUnion(): UnionedPluginManifest

    /**
     * Add this host to `<tenant>/profile/machines.json` (read-merge-CAS). On registration
     * conflicts, retries up to [REGISTER_MAX_RETRIES] before bailing.
     */
    suspend fun registerMachine(entry: MachineEntry): Result<Unit>

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
    @SerialName("os") val os: String,
    @SerialName("computed_at") val computedAt: Instant,
    @SerialName("plugins") val plugins: List<HostPluginEntry>,
)

@Serializable
data class HostPluginEntry(
    @SerialName("name") val name: String,
    @SerialName("format") val format: String,
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
    @SerialName("os") val os: String,
    @SerialName("last_seen_at") val lastSeenAt: Instant,
    @SerialName("binary_version") val binaryVersion: String,
)

@Serializable
internal data class MachinesDoc(
    @SerialName("v") val version: Int = 1,
    @SerialName("machines") val machines: List<MachineEntry> = emptyList(),
)

/**
 * Constructed at the call site (the desktop bootstrap wiring) once a [CloudBackend] is
 * available. Not DI-bound at AppScope today because the cloud handle is per-user via
 * `SwappableSyncQueue`; promoted to a `UserScope` binding once
 * https://github.com/srMarlins/sketchbook/issues/130 lands.
 */
class CloudMachineProfileStore(
    private val cloud: CloudBackend,
    private val catalog: Catalog,
    private val clock: Clock = Clock.System,
) : MachineProfileStore {
    override suspend fun publishHostSlice(
        hostId: String,
        hostName: String,
        os: String,
    ): Result<HostPluginManifest> {
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
        // Per-host file — overwrites unconditionally. Two hosts publishing concurrently
        // touch different keys, so there's no conflict to retry against.
        val existing = cloud.readDoc(MachineProfileStore.pluginManifestKey(hostId))
        val writeResult =
            cloud.writeDoc(
                key = MachineProfileStore.pluginManifestKey(hostId),
                expected = existing?.generation,
                bytes = bytes,
            )
        return writeResult.map { manifest }
    }

    override suspend fun composeUnion(): UnionedPluginManifest {
        val refs = cloud.listDocs(MachineProfileStore.PLUGIN_MANIFEST_PREFIX)
        val perHost =
            refs.mapNotNull { ref ->
                val read = cloud.readDoc(ref.key) ?: return@mapNotNull null
                runCatching {
                    JSON.decodeFromString(HostPluginManifest.serializer(), read.bytes.decodeToString())
                }.getOrNull()
            }
        val union = mutableMapOf<Pair<String, String>, HostPluginEntry>()
        for (slice in perHost) {
            for (entry in slice.plugins) {
                val key = entry.name to entry.format
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

    override suspend fun registerMachine(entry: MachineEntry): Result<Unit> {
        var attempt = 0
        while (attempt < MachineProfileStore.REGISTER_MAX_RETRIES) {
            attempt += 1
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
                .onSuccess { return Result.success(Unit) }
                .onFailure { err ->
                    if (err !is SketchbookError.Conflict) return Result.failure(err)
                }
        }
        return Result.failure(SketchbookError.Conflict("machines.json CAS retries exhausted"))
    }

    override suspend fun listMachines(): List<MachineEntry> {
        val read = cloud.readDoc(MachineProfileStore.MACHINES_KEY) ?: return emptyList()
        return runCatching {
            JSON.decodeFromString(MachinesDoc.serializer(), read.bytes.decodeToString()).machines
        }.getOrDefault(emptyList())
    }

    /**
     * Internal helper: build this host's plugin slice from the local catalog, unioning
     * `project_plugins` and `user_library_plugins` and applying OR-semantics on
     * `is_installed`.
     */
    private fun composeHostSlice(): List<HostPluginEntry> {
        val byKey = mutableMapOf<Pair<String, String>, HostPluginEntry>()
        val projectRows = catalog.catalogQueries.selectAllDistinctProjectPluginsWithInstalled().executeAsList()
        for (row in projectRows) {
            val key = row.plugin_name to row.plugin_type
            byKey[key] =
                HostPluginEntry(
                    name = row.plugin_name,
                    format = row.plugin_type,
                    installed = (row.is_installed ?: 0L) > 0L,
                )
        }
        val ulRows = catalog.catalogQueries.selectAllDistinctUserLibraryPluginsWithInstalled().executeAsList()
        for (row in ulRows) {
            val key = row.plugin_name to row.plugin_type
            val existing = byKey[key]
            byKey[key] =
                HostPluginEntry(
                    name = row.plugin_name,
                    format = row.plugin_type,
                    installed = (existing?.installed ?: false) || ((row.is_installed ?: 0L) > 0L),
                )
        }
        return byKey.values.toList()
    }

    private companion object {
        val JSON =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
                prettyPrint = false
            }

        // Suppress the "unused" warning on ListSerializer — it's part of the Json import surface
        // even though we don't reach for it directly here. (Kept for symmetry with TreeRegistry.)
        @Suppress("unused")
        val ListSerializerKeepImport = ListSerializer(HostPluginEntry.serializer())
    }
}
