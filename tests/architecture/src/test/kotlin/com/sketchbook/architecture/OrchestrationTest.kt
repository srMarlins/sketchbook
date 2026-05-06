package com.sketchbook.architecture

import com.lemonappdev.konsist.api.Konsist
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Encodes rules from `docs/architecture/dependency-injection.md` §4 and §6 — the orchestration /
 * graph-anatomy boundary.
 *
 * §4 says RootContent is rendering-only; observe-and-side-effect orchestration belongs in a
 * `@SingleIn(AppScope::class)` coordinator. §6 says `DesktopAppGraph` is the reference assembly:
 * `@DependencyGraph(AppScope::class)` and extends `ViewModelGraph` so the contributed VM map is
 * plumbed through `MetroViewModelFactory`.
 */
class OrchestrationTest {

    // ---------- §4 — RootContent is rendering-only ----------

    /**
     * §4: "RootContent should be rendering-only; if it had `LaunchedEffect { observe + side-effect }`
     * blocks, those move to a coordinator."
     *
     * **Loosened predicate.** Konsist 0.17.3 has no function-call-tree API; we only get raw text
     * via `KoTextProvider.text`. So the test scans the textual body of the top-level `RootContent`
     * function for `LaunchedEffect` blocks that contain `.collect(`, after stripping out the
     * `entryProvider = { ... }` lambda where per-NavEntry effects (a route's `vm.load(id)` and
     * `vm.effects.collect`) legitimately live per §3.1.
     *
     * False-negatives this allows: an orchestration block that doesn't `.collect` (e.g. a one-shot
     * `LaunchedEffect(Unit) { repository.refresh() }`). Those are easy to spot in review and
     * don't fit the doc's specific anti-pattern wording, so we accept the gap.
     */
    @Test
    fun `RootContent has no observe+side-effect LaunchedEffect at top level`() {
        val rootContent = Konsist.scopeFromProject()
            .functions()
            .firstOrNull { it.name == "RootContent" && it.isTopLevel }
        assertNotNull(rootContent, "RootContent function not found in project scope.")

        val body = rootContent.text
        val stripped = stripEntryProvider(body)

        // Find every `LaunchedEffect(...) { ... }` block in the stripped body and flag any whose
        // body contains `.collect(`. Brace-matching keeps multi-line lambdas intact.
        val violations = findOrchestrationLaunchedEffects(stripped)
        assertTrue(
            violations.isEmpty(),
            "RootContent contains LaunchedEffect orchestration outside `entryProvider`. " +
                "Per DI doc §4, observe+side-effect blocks belong in a coordinator. " +
                "Offenders (first 200 chars each):\n" +
                violations.joinToString("\n---\n") { it.take(200) },
        )
    }

    // ---------- §6 — DesktopAppGraph anatomy ----------

    /**
     * §6.a: `DesktopAppGraph` is annotated `@DependencyGraph(AppScope::class)`.
     *
     * The annotation parameter (`scope = AppScope::class`) isn't introspected; we only check
     * the annotation name. If a future graph drops the scope param, that's a Metro compile error
     * — this test catches the looser shape (annotation present at all).
     */
    @Test
    fun `DesktopAppGraph is annotated DependencyGraph`() {
        val graph = Konsist.scopeFromProject()
            .interfaces()
            .firstOrNull { it.name == "DesktopAppGraph" }
        assertNotNull(graph, "DesktopAppGraph interface not found.")
        assertTrue(
            graph.hasAnnotation { it.name == "DependencyGraph" },
            "DesktopAppGraph must be `@DependencyGraph(AppScope::class)` per DI doc §6.",
        )
    }

    /**
     * §6.b: `DesktopAppGraph extends ViewModelGraph` so the contributed VM multibinding map is
     * plumbed through `MetroViewModelFactory` (inherited accessor `metroViewModelFactory`).
     */
    @Test
    fun `DesktopAppGraph extends ViewModelGraph`() {
        val graph = Konsist.scopeFromProject()
            .interfaces()
            .firstOrNull { it.name == "DesktopAppGraph" }
        assertNotNull(graph, "DesktopAppGraph interface not found.")
        // `parents()` returns both `extends` and `implements` for classes; for an interface, only
        // the extended interface list. Match by simple name to avoid pulling Metro types onto the
        // tests classpath.
        val parentNames = graph.parents().map { it.name }
        assertTrue(
            "ViewModelGraph" in parentNames,
            "DesktopAppGraph must extend ViewModelGraph per DI doc §6.1. " +
                "Saw parents: $parentNames",
        )
    }

    // ---------- helpers ----------

    /**
     * Remove the body of `entryProvider = { ... }` (or `entryProvider = { lambda → ... }`) from
     * a function-text blob, leaving the rest of the function body intact for orchestration
     * scanning. Per-NavEntry `LaunchedEffect`s for `vm.load(...)` / `vm.effects.collect` are
     * the canonical pattern from DI doc §3.1 and aren't violations of §4.
     */
    private fun stripEntryProvider(text: String): String {
        val anchor = "entryProvider"
        val idx = text.indexOf(anchor)
        if (idx == -1) return text
        // Find the `{` after `entryProvider = `.
        val brace = text.indexOf('{', startIndex = idx)
        if (brace == -1) return text
        // Skip to the matching `}`.
        val end = matchingBrace(text, brace)
        if (end == -1) return text
        return text.substring(0, idx) + text.substring(end + 1)
    }

    /** Index of the `}` matching the `{` at [openIndex], or -1 if unbalanced. */
    private fun matchingBrace(text: String, openIndex: Int): Int {
        var depth = 0
        var i = openIndex
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++

                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /**
     * Return the textual bodies of every `LaunchedEffect(...) { ... }` block in [text] whose
     * lambda contains `.collect(` — the doc's "observe + side-effect" shape.
     */
    private fun findOrchestrationLaunchedEffects(text: String): List<String> {
        val out = mutableListOf<String>()
        var search = 0
        while (true) {
            val call = text.indexOf("LaunchedEffect(", search)
            if (call == -1) break
            // Find the `{` opening the lambda after the parens balance.
            val lambdaStart = findLambdaStart(text, call)
            if (lambdaStart == -1) {
                search = call + 1
                continue
            }
            val lambdaEnd = matchingBrace(text, lambdaStart)
            if (lambdaEnd == -1) break
            val lambdaBody = text.substring(lambdaStart, lambdaEnd + 1)
            if (".collect(" in lambdaBody) {
                out += lambdaBody
            }
            search = lambdaEnd + 1
        }
        return out
    }

    /**
     * Given the index of `LaunchedEffect(`, find the opening `{` of the trailing lambda.
     * Walks past the parenthesised args (with paren-depth tracking) and then expects `{`.
     * Returns -1 if the lambda is missing or the parens are unbalanced.
     */
    private fun findLambdaStart(text: String, callIndex: Int): Int {
        val parenStart = text.indexOf('(', callIndex)
        if (parenStart == -1) return -1
        var depth = 0
        var i = parenStart
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++

                ')' -> {
                    depth--
                    if (depth == 0) {
                        // After the closing paren, the next non-whitespace char should be `{`.
                        var j = i + 1
                        while (j < text.length && text[j].isWhitespace()) j++
                        return if (j < text.length && text[j] == '{') j else -1
                    }
                }
            }
            i++
        }
        return -1
    }
}
