package com.sketchbook.liveit

import com.sketchbook.core.ProjectUuid
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val PROGRESS_INTERVAL = 25

/**
 * Pull a previously-pushed test UUID from the live Firebase project and run the assertion
 * battery defined in [LiveProjectAssertions].
 *
 * Inputs:
 *  - `-Puuid=<liveit-...>` — UUID printed by the earlier [LiveTestPushKt] run
 *  - `-PdestDir=<abs path>` — destination directory. Must not already contain files (we
 *    refuse to overwrite to keep the assertion semantically meaningful)
 *
 * Pull path is deliberately simple: read the tree doc to find `head_rev`, read the manifest
 * for that rev, walk every entry and stream its blob to disk via [LiveCloudIo]. We do NOT
 * use [com.sketchbook.sync.PullPoller] here because PullPoller writes into the local SQL
 * snapshot repository — that's the right surface for the desktop app but adds a SQLDelight
 * dependency to this test module for no additional cross-OS coverage. The byte-equality +
 * parser assertions cover what we care about; using a leaner pull path keeps the failure
 * modes easy to attribute.
 */
fun main() =
    runBlocking {
        val uuidStr = LiveTestEnv.pullUuid()
        val destDir = LiveTestEnv.pullDestDir()
        prepareDestDir(destDir)

        LiveTestBootstrap.bootstrapForCloudOps().let { graph ->
            try {
                println("[liveTestPull] signed in as ${graph.signedInEmail} (uid=${graph.userId.value})")
                println("[liveTestPull] firebase project=${graph.config.projectId} bucket=${graph.config.storageBucket}")
                println("[liveTestPull] uuid=$uuidStr destDir=$destDir")

                val uuid = ProjectUuid(uuidStr)
                val manifest = LiveCloudIo.readHeadManifest(graph.metadataStore, graph.cloud, graph.userId, uuid)
                println(
                    "[liveTestPull] manifest rev=${manifest.rev.value} " +
                        "files=${manifest.files.size} self_contained=${manifest.selfContained}",
                )

                LiveCloudIo.materialize(
                    cloud = graph.cloud,
                    manifest = manifest,
                    destDir = destDir,
                    uuid = uuid,
                ) { done, total, rel ->
                    if (done == 1 || done == total || done % PROGRESS_INTERVAL == 0) {
                        println("  materialized $done/$total — $rel")
                    }
                }

                val report = LiveProjectAssertions.checkAll(destDir, manifest)
                report.printSummary()
                graph.shutdown()
                if (!report.success) exitProcess(1)
            } catch (t: Throwable) {
                runCatching { graph.shutdown() }
                throw t
            }
        }
    }

private fun prepareDestDir(destDir: Path) {
    if (Files.exists(destDir)) {
        val isEmpty =
            Files
                .newDirectoryStream(destDir)
                .use { it.iterator().hasNext().not() }
        check(isEmpty) {
            "destDir already contains files: $destDir. Pull refuses to overwrite — the " +
                "assertion battery only makes sense against a freshly-materialized tree."
        }
    } else {
        Files.createDirectories(destDir)
    }
}
