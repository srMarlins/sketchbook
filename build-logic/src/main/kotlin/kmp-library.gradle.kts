plugins {
    id("org.jetbrains.kotlin.multiplatform")
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
