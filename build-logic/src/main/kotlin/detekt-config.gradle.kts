import dev.detekt.gradle.Detekt

plugins {
    id("dev.detekt")
}

// Resolve detekt rule packs from the version catalog.
val libsCatalog = the<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

fun lib(name: String) = libsCatalog.findLibrary(name).get()

// detekt 2.x: every property on DetektExtension is a Gradle `Property<T>` /
// `ConfigurableFileCollection` / `RegularFileProperty`, so configuration uses
// `.set(...)` / `.setFrom(...)` rather than direct assignment.
//
// Baselines: detekt 2.x splits a KMP project into per-source-set tasks
// (`detekt<SourceSet>SourceSet`), each with its own baseline file at
// `<module>/detekt-baseline-<source-set>SourceSet.xml`. The baseline-generation
// counterparts (`detektBaseline<SourceSet>SourceSet`) write to that path and
// the analysis tasks read from it automatically — no extension wiring needed.
detekt {
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    buildUponDefaultConfig.set(true)
    parallel.set(true)
    autoCorrect.set(false)
}

dependencies {
    "detektPlugins"(lib("detekt-formatting"))
    "detektPlugins"(lib("compose-lints-detekt"))
}

// detekt 2.x: task type moved to `dev.detekt.gradle.Detekt`. Reports use
// `checkstyle` (replaces 1.x `xml`) and `markdown` (replaces 1.x `md`); the
// 1.x `txt` report no longer exists. `jvmTarget` is a `Property<String>`.
tasks.withType<Detekt>().configureEach {
    jvmTarget.set(libsCatalog.findVersion("jvm-toolchain").get().requiredVersion)
    reports {
        html.required.set(true)
        checkstyle.required.set(true)
        markdown.required.set(false)
        sarif.required.set(false)
    }
}

// Wire detekt into `check` so the local build is the gate.
//
// detekt 2.x's KMP integration registers two families of tasks:
//   1. detekt<SourceSet>SourceSet — KMP-aware, consume per-source-set baselines (detekt-baseline-*.xml)
//   2. detekt<Compilation>        — per-compilation, do NOT consume the per-source-set baselines
// We gate `check` on (1) only — the (2) family double-reports without baselines.
//
// Plain-JVM modules (no KMP plugin) don't get the SourceSet variants — they
// register `detektMain` / `detektTest` instead. Wire those into `check` too
// so this convention works for both module shapes.
tasks.named("check") {
    val detektTasks =
        tasks.withType<Detekt>().matching { task ->
            task.name.endsWith("SourceSet") || task.name in setOf("detektMain", "detektTest")
        }
    dependsOn(detektTasks)
}

// Per-compilation detekt tasks (detektMainJvm, detektTestJvm) double-report without baselines
// in KMP modules. Disable them — the SourceSet tasks already cover the same code with baselines.
// (Plain-JVM modules don't register *Jvm-suffixed tasks, so this is a no-op there.)
tasks.matching { it.name.matches(Regex("^detekt(Main|Test)Jvm$")) }.configureEach {
    enabled = false
}
