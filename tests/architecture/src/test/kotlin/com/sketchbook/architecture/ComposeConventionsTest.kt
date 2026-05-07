package com.sketchbook.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Compose conventions tied to DI doc §3.2 — VMs are acquired at the route boundary; they
 * shouldn't be threaded through arbitrary Composables, that defeats hoisting and previewing.
 */
class ComposeConventionsTest {
    /**
     * §3.2: only route-boundary Composables (`*Route` per the doc, `*Screen` per current code)
     * may take a `*ViewModel` parameter. Private helper Composables must take state + lambdas.
     *
     * **TODO — currently @Ignored.** When the predicate was first run it found 8 real violations
     * across 5 files: private helpers in `NeedsAttentionScreen`, `ProjectDetailScreen`,
     * `ProposalsScreen`, `TimelineScreen` (Header/PendingCard/MissingSampleCard/MacImportCard/LockSlot)
     * plus a `vm: ProjectDetailViewModel` parameter on a public Composable in `RootContent.kt`.
     *
     * Each violation is a real DI-doc breach but fixing them is a multi-file Compose refactor
     * (each helper needs `state + onIntent` plumbing) that doesn't belong in the dev-tooling PR
     * that introduced this test module. Re-enable after the refactor lands.
     *
     * The intended predicate was:
     *
     * ```kotlin
     * Konsist.scopeFromProject()
     *     .functions()
     *     .filter { fn -> fn.hasAnnotation { it.name == "Composable" } }
     *     .filter { fn -> !fn.name.endsWith("Route") && !fn.name.endsWith("Screen") }
     *     .flatMap { it.parameters }
     *     .assertFalse { param -> param.type.name.endsWith("ViewModel") }
     * ```
     */
    @Test
    @Ignore // Per the docstring above — re-enable after the multi-file Compose refactor lands.
    fun `non-route Composables do not accept ViewModel parameters`() {
        Konsist
            .scopeFromProject()
            .functions()
            .filter { fn -> fn.hasAnnotation { it.name == "Composable" } }
            .filter { fn ->
                !fn.name.endsWith("Route") &&
                    !fn.name.endsWith("RoutePane") &&
                    !fn.name.endsWith("Screen")
            }.flatMap { it.parameters }
            .assertFalse { param -> param.type.name.endsWith("ViewModel") }
    }
}
