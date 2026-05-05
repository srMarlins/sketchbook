import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.conveyor)
}

// Conveyor (and Compose Desktop's nativeDistributions) require a real
// project version. CI passes -Pversion=<tag without leading v> from the
// release workflow; local builds fall through to a dev placeholder.
version = (project.findProperty("version")?.toString()?.takeIf { it != "unspecified" })
    ?: "0.0.0-dev"

kotlin {
    jvm()
    jvmToolchain(17)

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":shared:core"))
            implementation(project(":shared:repository"))
            implementation(project(":shared:ui-shared"))
            implementation(project(":shared:feature-projects"))
            implementation(project(":shared:feature-project-detail"))
            implementation(project(":shared:feature-timeline"))
            implementation(project(":shared:feature-proposals"))
            implementation(project(":shared:feature-needs-attention"))
            implementation(project(":shared:feature-settings"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.sketchbook.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "Sketchbook"
            // Mac/Windows installer embed-version. Must be a SemVer-like
            // string with no pre-release suffix on Mac. Fall back to a
            // numeric placeholder for dev builds.
            packageVersion = (project.version.toString().takeIf { it.matches(Regex("\\d+\\.\\d+\\.\\d+")) }) ?: "0.0.0"
        }
    }
}
