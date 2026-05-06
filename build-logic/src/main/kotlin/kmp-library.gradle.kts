import dev.detekt.gradle.Detekt

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("dev.detekt")
    // Dependency-analysis is applied per-project so the root `buildHealth` task can
    // aggregate per-project health reports across all KMP modules.
    id("com.autonomousapps.dependency-analysis")
}

kotlin {
    jvm()
    jvmToolchain(21)

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                // Per-module dependencies live in the module's own build.gradle.kts.
            }
        }
    }
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
    jvmTarget.set("21")
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
tasks.named("check") {
    dependsOn(tasks.withType<Detekt>().matching { it.name.endsWith("SourceSet") })
}

// Per-compilation detekt tasks (detektMainJvm, detektTestJvm) double-report without baselines.
// Disable them — the SourceSet tasks already cover the same code with baselines.
tasks.matching { it.name.matches(Regex("^detekt(Main|Test)Jvm$")) }.configureEach {
    enabled = false
}
