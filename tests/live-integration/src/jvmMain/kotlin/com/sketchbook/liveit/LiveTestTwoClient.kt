package com.sketchbook.liveit

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.system.exitProcess
import kotlin.time.Clock

/**
 * Single-machine, two-simulated-client integration test. One process spawns two
 * [ClientHandle]s with different `hostId`s and runs a battery of scenarios against the
 * live dev Firebase project. Designed to be invoked as a single Gradle command and to
 * exit non-zero if anything fails.
 *
 * Inputs:
 *  - `-PtemplateDir=<abs path>` (required) — a small real Live project folder used as the
 *    starting tree for each scenario. Picking a small one (e.g. `super_minimal Project`)
 *    keeps the test fast since each scenario re-copies + re-uploads it.
 *  - `-Pscenario=<name|all>` (optional, default `all`) — picks specific scenarios. Names
 *    listed in [TwoClientScenarios.ALL].
 *
 * Outputs:
 *  - Per-scenario pass/fail + narrative + key timings.
 *  - Final summary table; exit code `1` if any scenario failed.
 */
fun main() =
    runBlocking {
        val templateDir = resolveTemplateDir()
        val picked = resolvePickedScenarios()
        val runBase = makeRunBase()
        println("[liveTestTwoClient] template=$templateDir")
        println("[liveTestTwoClient] runBase=$runBase")
        println("[liveTestTwoClient] scenarios=${picked.joinToString(",")}")

        LiveTestBootstrap.bootstrapForCloudOps().let { graph ->
            try {
                println("[liveTestTwoClient] signed in as ${graph.signedInEmail} (uid=${graph.userId.value})")
                println(
                    "[liveTestTwoClient] firebase project=${graph.config.projectId} " +
                        "bucket=${graph.config.storageBucket}",
                )

                val results = mutableListOf<ScenarioResult>()
                for (name in picked) {
                    println()
                    println("=== running $name ===")
                    val scenarioDir = runBase.resolve(name)
                    Files.createDirectories(scenarioDir.resolve("a"))
                    Files.createDirectories(scenarioDir.resolve("b"))
                    val harness =
                        TwoClientHarness(
                            graph = graph,
                            clientA =
                                ClientHandle(
                                    name = "A",
                                    hostId = "two-client-a",
                                    hostName = "TwoClient-A",
                                    workDir = scenarioDir.resolve("a"),
                                ),
                            clientB =
                                ClientHandle(
                                    name = "B",
                                    hostId = "two-client-b",
                                    hostName = "TwoClient-B",
                                    workDir = scenarioDir.resolve("b"),
                                ),
                        )
                    val r = TwoClientScenarios.run(name, harness, templateDir)
                    results += r
                    printResult(r)
                }

                println()
                println("=== Summary ===")
                val maxName = (results.map { it.name.length } + 8).max()
                for (r in results) {
                    val tag = if (r.success) "PASS" else "FAIL"
                    println("  $tag  ${r.name.padEnd(maxName)}  ${r.narrative}")
                }
                graph.shutdown()
                if (results.any { !it.success }) exitProcess(1)
            } catch (t: Throwable) {
                runCatching { graph.shutdown() }
                throw t
            }
        }
    }

private fun resolveTemplateDir(): Path {
    val raw =
        System.getProperty("live.templateDir")
            ?: System.getenv("SKETCHBOOK_LIVE_TEMPLATE_DIR")
            ?: error(
                "Missing templateDir. Pass `-PtemplateDir=\"<abs-path-to-Live-project>\"` " +
                    "or set SKETCHBOOK_LIVE_TEMPLATE_DIR. Pick a small project — the test re-copies " +
                    "it for each scenario.",
            )
    val path = Paths.get(raw)
    check(path.isAbsolute && path.exists() && Files.isDirectory(path)) {
        "templateDir must be an absolute path to an existing directory: $raw"
    }
    return path
}

private fun resolvePickedScenarios(): List<String> {
    val raw = System.getProperty("live.scenario") ?: "all"
    return if (raw.equals("all", ignoreCase = true)) {
        TwoClientScenarios.ALL
    } else {
        val names = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val unknown = names - TwoClientScenarios.ALL.toSet()
        check(unknown.isEmpty()) { "unknown scenario(s): $unknown; known: ${TwoClientScenarios.ALL}" }
        names
    }
}

private fun makeRunBase(): Path {
    val runId = Clock.System.now().epochSeconds.toString()
    val base = Paths.get(System.getProperty("user.home"), ".sketchbook-test", "two-client-runs", runId)
    Files.createDirectories(base)
    return base
}

private fun printResult(r: ScenarioResult) {
    val tag = if (r.success) "PASS" else "FAIL"
    println("[$tag] ${r.name} — ${r.narrative}")
    if (r.timings.isNotEmpty()) {
        for ((k, v) in r.timings) println("    $k = ${v}ms")
    }
    if (!r.success && r.failure != null) {
        // Print only first 30 lines of stack to keep the report readable.
        val head = r.failure.lineSequence().take(STACK_HEAD_LINES).joinToString("\n")
        println(head)
    }
}

private const val STACK_HEAD_LINES = 30
