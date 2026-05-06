plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("detekt-config")
    // Dependency-analysis is applied per-project so the root `buildHealth` task can
    // aggregate per-project health reports across all KMP modules.
    id("com.autonomousapps.dependency-analysis")
}

private val libsCatalog = the<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")
private val jvmToolchainVersion =
    libsCatalog
        .findVersion("jvm-toolchain")
        .get()
        .requiredVersion
        .toInt()

kotlin {
    jvm()
    jvmToolchain(jvmToolchainVersion)

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                // Per-module dependencies live in the module's own build.gradle.kts.
            }
        }
    }
}
