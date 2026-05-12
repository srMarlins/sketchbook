package com.sketchbook.liveit

import com.sketchbook.cloud.Generation
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SnapshotKind
import com.sketchbook.sync.PipelineInput
import com.sketchbook.sync.SnapshotPipeline
import com.sketchbook.sync.SnapshotProgress
import com.sketchbook.syncio.JvmWorkingTree
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.nio.file.Files
import java.security.SecureRandom
import kotlin.io.path.extension
import kotlin.io.path.walk
import kotlin.system.exitProcess
import kotlin.time.Clock

private const val HASH_DISPLAY_PREFIX = 12
private const val RAND_HEX_RANGE = 0xffff
private const val RAND_HEX_WIDTH = 4
private const val HEX_RADIX = 16


/**
 * Snapshot a real Ableton project folder to the live Firebase project via the production
 * [SnapshotPipeline]. Inputs:
 *   - `-PprojectDir=<abs path>` (required) — folder containing at least one `.als`
 *   - `-PdisplayName=<label>` (optional) — defaults to the folder name
 *   - `-PtreeIdSuffix=<slug>` (optional) — appended to the test UUID for human grep-ability
 *
 * Mints a fresh `ProjectUuid` of the form `liveit-<epochSec>-<rand4>[-<suffix>]`. The
 * `liveit-` prefix is load-bearing for [LiveTestSweepKt] which scopes its deletes to that
 * prefix. We deliberately do NOT reuse a tree id across runs — the post-CAS head-publish
 * in `SnapshotPipeline` would either skip work (if the manifest is identical) or branch on
 * conflict (if it isn't), both of which obscure the actual cross-OS round-trip we care
 * about. Fresh UUID per push, then sweep at the end.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
fun main() =
    runBlocking {
        val projectDir = LiveTestEnv.pushProjectDir()
        check(Files.isDirectory(projectDir)) { "projectDir is not a directory: $projectDir" }

        val alsCount =
            projectDir
                .walk()
                .count { it.extension.equals("als", ignoreCase = true) }
        check(alsCount > 0) {
            "projectDir contains no .als files — refusing to push. " +
                "If you meant to push an arbitrary folder, point at the parent Ableton project. " +
                "Path: $projectDir"
        }
        val displayName = LiveTestEnv.pushDisplayName(projectDir)
        println("[liveTestPush] projectDir=$projectDir alsCount=$alsCount displayName=$displayName")

        val uuid = mintTestUuid(LiveTestEnv.pushTreeIdSuffix())
        val hostId = stableHostId()
        val hostName = stableHostName()
        println("[liveTestPush] uuid=${uuid.value} hostId=$hostId hostName=$hostName")

        LiveTestBootstrap.bootstrapForCloudOps().let { graph ->
            try {
                println("[liveTestPush] signed in as ${graph.signedInEmail} (uid=${graph.userId.value})")
                println("[liveTestPush] firebase project=${graph.config.projectId} bucket=${graph.config.storageBucket}")
                val pipeline =
                    SnapshotPipeline(
                        cloud = graph.cloud,
                        metadataStore = graph.metadataStore,
                        ownerUserId = graph.userId,
                        hostId = hostId,
                        hostName = hostName,
                        clock = Clock.System,
                    )

                var saved: SnapshotProgress.Saved? = null
                var failed: SnapshotProgress.Failed? = null
                pipeline
                    .run(
                        PipelineInput(
                            uuid = uuid,
                            tree = JvmWorkingTree(projectDir),
                            lastKnownManifest = null,
                            expectedHeadGeneration = Generation.ZERO,
                            kind = SnapshotKind.Named,
                            label = "live-integration push: $displayName",
                        ),
                    ).collect { event ->
                        when (event) {
                            is SnapshotProgress.LeaseAcquired -> println("  lease acquired")
                            is SnapshotProgress.LeaseHeld -> println("  lease held by ${event.ownerHostName}")
                            is SnapshotProgress.Hashing ->
                                if (event.done == event.total) println("  hashed ${event.total} files")
                            is SnapshotProgress.Uploading ->
                                if (event.bytesDone == event.bytesTotal) {
                                    println("  uploaded blob ${event.hash.value.take(HASH_DISPLAY_PREFIX)}… (${event.bytesTotal} B)")
                                }
                            is SnapshotProgress.WritingManifest -> println("  writing manifest rev=${event.rev.value}")
                            is SnapshotProgress.Saved -> saved = event
                            is SnapshotProgress.Failed -> failed = event
                        }
                    }

                failed?.let { f ->
                    System.err.println("[liveTestPush] FAILED: ${f.reason}")
                    graph.shutdown()
                    exitProcess(2)
                }
                val s =
                    saved ?: run {
                        System.err.println("[liveTestPush] FAILED: pipeline ended without Saved or Failed event")
                        graph.shutdown()
                        exitProcess(3)
                    }
                println("[liveTestPush] OK")
                // Last two lines of stdout are machine-parseable (KEY=value) so the user — or a
                // wrapper script — can copy them straight into the pull invocation on the other
                // machine.
                println("UUID=${uuid.value}")
                println("HEAD_REV=${s.rev.value}")
            } finally {
                graph.shutdown()
            }
        }
    }

private fun mintTestUuid(suffix: String?): ProjectUuid {
    val epochSec = Clock.System.now().epochSeconds
    val rand =
        SecureRandom()
            .nextInt(RAND_HEX_RANGE)
            .toString(HEX_RADIX)
            .padStart(RAND_HEX_WIDTH, '0')
    val tail = if (suffix.isNullOrBlank()) "" else "-${suffix.replace(Regex("[^A-Za-z0-9_-]"), "_")}"
    return ProjectUuid("${LiveTestEnv.TEST_UUID_PREFIX}$epochSec-$rand$tail")
}

private fun stableHostId(): String {
    // Lowercased hostname is good enough for these tests — uniqueness across Mac + Windows
    // is the goal; we're not trying to be globally unique like a UUID.
    return runCatching { InetAddress.getLocalHost().hostName }
        .getOrNull()
        ?.lowercase()
        ?.replace(Regex("[^a-z0-9_-]"), "-")
        ?: "live-integration-host"
}

private fun stableHostName(): String =
    runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: "live-integration"
