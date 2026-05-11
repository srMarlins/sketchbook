package com.sketchbook.liveit

import com.sketchbook.cloud.Generation
import com.sketchbook.cloud.metadata.DocPath
import com.sketchbook.cloud.metadata.TreeDoc
import com.sketchbook.core.Manifest
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SnapshotKind
import com.sketchbook.sync.PipelineInput
import com.sketchbook.sync.SnapshotPipeline
import com.sketchbook.sync.SnapshotProgress
import com.sketchbook.syncio.JvmWorkingTree
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.absolute
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private const val HEX_BYTE_MASK = 0xff
private const val HEX_RADIX = 16
private const val HEX_PAD = 2

/**
 * One process, two simulated clients. They share auth (same UID — they're "the same user
 * on two machines") but differ on the dimensions that distinguish machines in the data
 * model: `hostId` (lock holder, manifest provenance, tree-doc `head_updated_by_host`),
 * `hostName` (UI label), and working-directory path.
 *
 * Constructed once per `liveTestTwoClient` invocation. Scenarios reuse the same harness;
 * each scenario uses a fresh [ProjectUuid] to avoid cross-scenario state.
 */
data class ClientHandle(
    val name: String,
    val hostId: String,
    val hostName: String,
    val workDir: Path,
)

/**
 * Wraps a [LiveCloudGraph] plus two [ClientHandle]s. The harness owns the per-scenario
 * scratch dirs; cleanup is the caller's responsibility (sweep deletes Firestore docs;
 * scratch dirs are left for inspection).
 */
class TwoClientHarness(
    val graph: LiveCloudGraph,
    val clientA: ClientHandle,
    val clientB: ClientHandle,
) {
    /**
     * Build a [SnapshotPipeline] for the named client. Each scenario gets a fresh pipeline
     * so per-pipeline state (e.g., the lock heartbeat coroutine) is scoped to a single
     * `run(...)`. [leaseTtl] is overridable for the lockExpiry scenario.
     */
    fun pipelineFor(
        client: ClientHandle,
        leaseTtl: Duration = DEFAULT_LEASE_TTL,
        heartbeatInterval: Duration = DEFAULT_HEARTBEAT_INTERVAL,
        clock: Clock = Clock.System,
    ): SnapshotPipeline =
        SnapshotPipeline(
            cloud = graph.cloud,
            metadataStore = graph.metadataStore,
            ownerUserId = graph.userId,
            hostId = client.hostId,
            hostName = client.hostName,
            clock = clock,
            leaseTtl = leaseTtl,
            heartbeatInterval = heartbeatInterval,
        )

    /**
     * Subscribe to the tree doc for [uuid]. Emits `null` until the doc exists, then the
     * decoded [TreeDoc] on every change. Used by scenarios to detect listener fires.
     */
    fun observeTreeDoc(uuid: ProjectUuid): Flow<TreeDoc?> =
        graph.metadataStore.observeDoc(
            DocPath.tree(graph.userId.value, uuid.value),
            TreeDoc.serializer(),
        )

    suspend fun readTreeDoc(uuid: ProjectUuid): TreeDoc? =
        LiveCloudIo.readTreeDoc(graph.metadataStore, graph.userId, uuid)

    suspend fun readHeadManifest(uuid: ProjectUuid): Manifest =
        LiveCloudIo.readHeadManifest(graph.metadataStore, graph.cloud, graph.userId, uuid)

    /**
     * Materialize the head manifest into [client]'s workDir. Replaces existing files —
     * scenarios use this on the receiver to pick up an updated rev from the cloud, and the
     * receiver's workDir typically already holds the previous rev's bytes.
     */
    suspend fun materializeInto(
        client: ClientHandle,
        uuid: ProjectUuid,
    ): Manifest {
        val manifest = readHeadManifest(uuid)
        LiveCloudIo.materialize(
            cloud = graph.cloud,
            manifest = manifest,
            destDir = client.workDir,
            uuid = uuid,
            overwriteMode = LiveCloudIo.OverwriteMode.ReplaceExisting,
        )
        return manifest
    }

    /**
     * Run a snapshot through the pipeline and collect events. Returns the terminal event —
     * either [SnapshotProgress.Saved] or [SnapshotProgress.Failed].
     */
    suspend fun snapshot(
        client: ClientHandle,
        uuid: ProjectUuid,
        lastKnownManifest: Manifest?,
        expectedHeadGeneration: Generation?,
        leaseTtl: Duration = DEFAULT_LEASE_TTL,
        heartbeatInterval: Duration = DEFAULT_HEARTBEAT_INTERVAL,
        kind: SnapshotKind = SnapshotKind.Auto,
        label: String? = null,
    ): SnapshotProgress {
        val pipeline = pipelineFor(client, leaseTtl = leaseTtl, heartbeatInterval = heartbeatInterval)
        val events =
            pipeline
                .run(
                    PipelineInput(
                        uuid = uuid,
                        tree = JvmWorkingTree(client.workDir),
                        lastKnownManifest = lastKnownManifest,
                        expectedHeadGeneration = expectedHeadGeneration,
                        kind = kind,
                        label = label,
                    ),
                ).toList()
        return events.lastOrNull { it is SnapshotProgress.Saved || it is SnapshotProgress.Failed }
            ?: error("pipeline emitted no terminal event; events=$events")
    }

    /**
     * Sweep this scenario's Firestore docs. Best-effort — failures here don't fail the
     * scenario, since the broader [LiveTestSweepKt] catches any leftovers.
     */
    suspend fun cleanup(uuid: ProjectUuid) {
        runCatching { graph.metadataStore.deleteDoc(DocPath.lock(graph.userId.value, uuid.value)) }
        runCatching { graph.metadataStore.deleteDoc(DocPath.tree(graph.userId.value, uuid.value)) }
    }

    companion object {
        // Production default is 15 minutes — same here so scenarios that aren't specifically
        // testing TTL behaviour see realistic lock semantics.
        val DEFAULT_LEASE_TTL: Duration = 15.minutes
        val DEFAULT_HEARTBEAT_INTERVAL: Duration = 5.minutes
    }
}

/**
 * Filesystem helpers for the two-client harness. Kept as top-level functions (not on the
 * harness) so scenarios can compose them freely without going through a single facade.
 */
object TwoClientFs {
    /**
     * Copy every file under [template] into [dest], preserving relative paths. Skips Live's
     * regenerable cruft (matches [JvmWorkingTree]'s SKIP_DIRS) and dotfiles + `.als.bak` —
     * so the seeded tree is exactly what would land in a snapshot. This means
     * `template = "<foo> Project"` and `dest = workDir` produce a manifest identical to
     * what the desktop app would produce against `<foo> Project` directly.
     */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun seedFromTemplate(
        template: Path,
        dest: Path,
    ) {
        Files.createDirectories(dest)
        template.walk().forEach { src ->
            if (!src.isRegularFile()) return@forEach
            if (src.fileName.toString().startsWith(".")) return@forEach
            if (src.fileName.toString().endsWith(".als.bak", ignoreCase = true)) return@forEach
            val rel = src.relativeTo(template)
            if (rel.any { it.toString() in SKIP_DIRS }) return@forEach
            val out = dest.resolve(rel)
            Files.createDirectories(out.parent)
            Files.copy(src, out, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Drop a deterministic edit file at the project root. Picks a filename that won't
     * collide with anything Live writes (`live-integration-edit-rev-<N>.txt`) and content
     * that's a function of [rev] so the SHA differs between revs but is reproducible.
     */
    fun addEdit(
        workDir: Path,
        rev: Int,
    ) {
        val path = workDir.resolve("live-integration-edit-rev-$rev.txt")
        val content =
            buildString {
                append("live-integration edit\nrev=").append(rev).append('\n')
                // pad to a known size so the manifest entry's `size` field is predictable
                // if a scenario wants to assert on it
                while (length < EDIT_PAYLOAD_SIZE) append("x")
            }
        Files.writeString(path, content)
    }

    /**
     * SHA-256 every file under [root] (with [JvmWorkingTree]'s exclusion list) and return
     * `relativePath → sha256` so two trees can be compared deterministically.
     */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun hashTree(root: Path): Map<String, String> {
        val out = sortedMapOf<String, String>()
        root.walk().forEach { p ->
            if (!p.isRegularFile()) return@forEach
            if (p.fileName.toString().startsWith(".")) return@forEach
            if (p.fileName.toString().endsWith(".als.bak", ignoreCase = true)) return@forEach
            val rel = p.relativeTo(root)
            if (rel.any { it.toString() in SKIP_DIRS }) return@forEach
            out[rel.toString().replace('\\', '/')] = sha256Hex(p)
        }
        return out
    }

    private fun sha256Hex(p: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(p).use { input ->
            val buf = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") {
            (it.toInt() and HEX_BYTE_MASK).toString(HEX_RADIX).padStart(HEX_PAD, '0')
        }
    }

    // Mirrors JvmWorkingTree's SKIP_DIRS so the seeded tree + the snapshotted tree agree.
    private val SKIP_DIRS = setOf("Backup", "Samples", "Ableton Project Info")
    private const val EDIT_PAYLOAD_SIZE = 256
    private const val HASH_BUFFER_BYTES = 64 * 1024
}
