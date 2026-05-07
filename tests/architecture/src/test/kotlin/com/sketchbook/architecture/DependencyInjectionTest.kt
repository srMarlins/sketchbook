package com.sketchbook.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.modifierprovider.withoutAbstractModifier
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test

/**
 * Encodes rules from `docs/architecture/dependency-injection.md` §2, §3, §3.1, §3.3, §7.
 *
 * Each test name names the rule it enforces. If a test fails, fix the code (the doc is the
 * contract) — don't loosen the test unless the rule is unenforceable in 0.17.3 Konsist.
 *
 * Notes on Konsist 0.17.3 API:
 * - This module is plain JVM and doesn't have Metro on its classpath, so annotation lookup is by
 *   simple class name (`hasAnnotation { it.name == "Inject" }`) rather than `hasAnnotationOf<>()`.
 *   Two annotations across the codebase share the simple name `Inject` (Metro and Kotlin
 *   serialization), but only Metro's lands on classes/constructors of services & VMs we filter.
 * - Konsist scans every `.kt` under the project (regardless of KMP source-set membership), so
 *   `commonMain`, `jvmMain`, etc. all show up via `Konsist.scopeFromProject()`.
 */
class DependencyInjectionTest {
    // ---------- §2 — Binding services ----------

    /** §2: state-holding singletons use `@ContributesBinding` (interface bind), not field/companion. */
    @Test
    fun `services with @ContributesBinding are also @Inject`() {
        Konsist
            .scopeFromProject()
            .classes()
            .filter { it.hasAnnotation { ann -> ann.name == "ContributesBinding" } }
            .assertTrue { klass ->
                klass.hasAnnotation { it.name == "Inject" }
            }
    }

    /** §2 + §7: no service-locator companion `instance` singletons. */
    @Test
    fun `no companion object 'instance' service locators`() {
        // We walk every companion object in the project and assert none expose a `val instance`.
        // Loosened: only flags the literal `instance` name. The doc rules out
        // `companion object { val instance = … }` style locators specifically; other companion
        // `val`s (DEFAULT, KEY constants, factory builders) are fine.
        Konsist
            .scopeFromProject()
            .objects(includeNested = true)
            .filter { it.hasCompanionModifier }
            .flatMap { it.properties() }
            .assertFalse { prop -> prop.name == "instance" }
    }

    // ---------- §3 / §3.1 — ViewModels ----------

    /** §3.1: every `*ViewModel` (concrete) is `@ContributesIntoMap` + `@ViewModelKey` + `@Inject`. */
    @Test
    fun `concrete ViewModel classes have Metro VM trio annotations`() {
        Konsist
            .scopeFromProject()
            .classes()
            .withoutAbstractModifier()
            .filter { it.name.endsWith("ViewModel") }
            // Skip the framework base class itself — `ViewModel` (no prefix) is the lifecycle base.
            .filter { it.name != "ViewModel" }
            .assertTrue { vm ->
                vm.hasAnnotation { it.name == "ContributesIntoMap" } &&
                    vm.hasAnnotation { it.name == "ViewModelKey" } &&
                    vm.hasAnnotation { it.name == "Inject" }
            }
    }

    /** §3.1: VMs use `viewModelScope`; never accept `CoroutineScope` as a constructor parameter. */
    @Test
    fun `ViewModel constructors do not take CoroutineScope`() {
        Konsist
            .scopeFromProject()
            .classes()
            .withoutAbstractModifier()
            .filter { it.name.endsWith("ViewModel") && it.name != "ViewModel" }
            .mapNotNull { it.primaryConstructor }
            .flatMap { it.parameters }
            .assertFalse { param ->
                // Match by simple type name; `kotlinx.coroutines.CoroutineScope` is the only
                // type with this name we'd plausibly see in a VM ctor.
                param.type.name == "CoroutineScope"
            }
    }

    /**
     * §3.1 anti-pattern: "No `SavedStateHandle` until the SavedStateRegistry writer is wired on
     * desktop. The current factory does not persist state across process death; pretending it
     * does is a footgun."
     *
     * Asserts that no concrete `*ViewModel` accepts a `SavedStateHandle` parameter in its primary
     * constructor. When the desktop `SavedStateRegistry` writer lands and the doc lifts this
     * restriction, drop this test in the same PR.
     */
    @Test
    fun `ViewModel constructors do not take SavedStateHandle`() {
        Konsist
            .scopeFromProject()
            .classes()
            .withoutAbstractModifier()
            .filter { it.name.endsWith("ViewModel") && it.name != "ViewModel" }
            .mapNotNull { it.primaryConstructor }
            .flatMap { it.parameters }
            .assertFalse { param ->
                // Match by simple type name. `androidx.lifecycle.SavedStateHandle` (and the
                // JetBrains lifecycle KMP fork's mirror) are the only types with this name.
                param.type.name == "SavedStateHandle"
            }
    }

    // ---------- §3.3 — assisted-injected VMs ----------

    /**
     * §3.3: "When a VM needs a runtime parameter (e.g. a project id), use `@AssistedInject` per
     * the metrox-viewmodel docs and acquire with `metroViewModel<VM> { factory -> factory.create(id) }`."
     *
     * **Loosened:** Konsist 0.17.3 can't introspect *which* parameters Metro can resolve from
     * the graph, so we can't directly assert "non-Metro-resolvable params must be `@Assisted`."
     * Instead we encode the inverse half: any ctor parameter annotated `@Assisted` implies the
     * enclosing class is annotated `@AssistedInject` (or, equivalently, the assisted-factory
     * variant `@AssistedFactory` on a sibling). This catches the most common mis-shape — putting
     * `@Assisted` on a param of an `@Inject` class, which Metro would reject at compile time but
     * is worth flagging earlier with a readable message.
     *
     * As of 2026-05-06 no production VM uses `@AssistedInject`; this test passes vacuously today
     * (zero `@Assisted` params), and starts paying rent the moment the first assisted VM lands.
     */
    @Test
    fun `@Assisted VM ctor params live on @AssistedInject classes`() {
        Konsist
            .scopeFromProject()
            .classes()
            .withoutAbstractModifier()
            .filter { it.name.endsWith("ViewModel") && it.name != "ViewModel" }
            .filter { vm ->
                val ctor = vm.primaryConstructor ?: return@filter false
                ctor.parameters.any { p -> p.hasAnnotation { it.name == "Assisted" } }
            }.assertTrue { vm ->
                vm.hasAnnotation { it.name == "AssistedInject" }
            }
    }

    // ---------- §7 — anti-patterns ----------

    /** §7: don't bind impl-as-impl — `@ContributesBinding` should bind plain classes, not data/value. */
    @Test
    fun `@ContributesBinding classes are not data classes or value classes`() {
        // Loosened from a "must extend an interface" structural check. The doc forbids binding
        // anything other than a normal class to an interface; data/value classes in particular
        // tend to indicate value-types-as-services or service locators.
        Konsist
            .scopeFromProject()
            .classes()
            .filter { it.hasAnnotation { ann -> ann.name == "ContributesBinding" } }
            .assertFalse { klass ->
                klass.hasDataModifier || klass.hasValueModifier
            }
    }
}
