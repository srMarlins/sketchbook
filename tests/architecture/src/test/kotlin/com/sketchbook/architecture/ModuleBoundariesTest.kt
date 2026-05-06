package com.sketchbook.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import kotlin.test.Test

/**
 * Module-boundary rules implied by the DI doc §1 (one AppScope, app shells own roots) and
 * §3.2 (chrome reads repository flows directly — features don't reach into app code).
 *
 * Konsist scans `.kt` files via path; we filter by source-path tokens rather than KMP
 * source-set names because `scopeFromProject()` returns everything regardless.
 */
class ModuleBoundariesTest {

    /** :shared:* features must not import com.sketchbook.app.* — that would invert the dep graph. */
    @Test
    fun `shared modules do not import app code`() {
        Konsist.scopeFromProject()
            .files
            .filter {
                val p = it.path.replace('\\', '/')
                "/shared/" in p
            }
            .assertFalse { file ->
                file.imports.any { imp -> imp.name.startsWith("com.sketchbook.app.") }
            }
    }

    /** commonMain code must not depend on JVM-only types — that breaks the KMP seam (DI doc §2 sub-section). */
    @Test
    fun `commonMain does not import java or javax`() {
        // Known existing violation: `SqlRepairRepository.kt` uses `java.nio.file` / `javax.xml.stream`
        // directly in commonMain. The DI doc §2 prescribes a `fun interface` adapter for this case;
        // the refactor is tracked as tech debt and out of scope for this dev-tooling PR. Allowlisting
        // by path so any *new* violation still fails the test.
        val knownViolationPathSuffixes = listOf(
            "/shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/SqlRepairRepository.kt",
        )

        Konsist.scopeFromProject()
            .files
            .filter {
                val p = it.path.replace('\\', '/')
                "/src/commonMain/" in p
            }
            .filter { file ->
                val p = file.path.replace('\\', '/')
                knownViolationPathSuffixes.none { suffix -> p.endsWith(suffix) }
            }
            .assertFalse { file ->
                file.imports.any { imp ->
                    val n = imp.name
                    // Loosened: `java.lang.*` types are auto-imported and never appear as
                    // explicit imports; we only check explicit `java.*` / `javax.*` imports.
                    // `kotlin.*` packages (kotlin.collections.*, kotlin.io.*) are fine.
                    n.startsWith("java.") || n.startsWith("javax.")
                }
            }
    }
}
