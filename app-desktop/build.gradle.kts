import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.metro)
    alias(libs.plugins.conveyor)
    alias(libs.plugins.dependency.analysis)
}

// Conveyor (and Compose Desktop's nativeDistributions) require a real
// project version. CI passes -Pversion=<tag without leading v> from the
// release workflow; local builds fall through to a dev placeholder.
version = (project.findProperty("version")?.toString()?.takeIf { it != "unspecified" })
    ?: "0.0.0-dev"

kotlin {
    jvm()
    jvmToolchain(21)

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":shared:core"))
            implementation(project(":shared:actions"))
            implementation(project(":shared:catalog"))
            implementation(project(":shared:cloud"))
            implementation(project(":shared:repository"))
            implementation(project(":shared:sync"))
            implementation(project(":shared:sync-io"))
            implementation(project(":shared:ui-shared"))
            implementation(project(":shared:feature-projects"))
            implementation(project(":shared:feature-project-detail"))
            implementation(project(":shared:feature-timeline"))
            implementation(project(":shared:feature-proposals"))
            implementation(project(":shared:feature-needs-attention"))
            implementation(project(":shared:feature-settings"))
            implementation(project(":shared:feature-journal"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.metro.runtime)
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.metro.viewmodel)
            implementation(libs.metro.viewmodel.compose)
            implementation(libs.lifecycle.viewmodel.navigation3)
            implementation(libs.lifecycle.runtime.compose)
            // Required by `Dispatchers.Main` on Compose Desktop (Swing event-loop binding).
            // Pure runtime; no compile-time references.
            runtimeOnly(libs.kotlinx.coroutines.swing)
            // nav3-ui transitively pulls navigation3-runtime; explicit runtime dep is omitted
            // because the JetBrains fork only publishes navigation3-runtime via the ui artifact.
            implementation(libs.nav3.ui)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.desktop.currentOs)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
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

// Conveyor packages all OS targets from a single (Linux) runner, so we
// can't rely on `compose.desktop.currentOs` for cross-platform skiko
// natives — that resolves at build time to the runner's OS only, and
// the bundled installers crash on every other OS with
// ExceptionInInitializerError in WindowSkiaLayerComponent.
//
// The Conveyor Gradle plugin pre-registers per-target configurations
// (linuxAmd64/macAmd64/macAarch64/windowsAmd64). Adding the matching
// compose.desktop.<os>_<arch> artifact to each ensures every installer
// ships its own skiko binaries.
dependencies {
    "linuxAmd64"(compose.desktop.linux_x64)
    "macAmd64"(compose.desktop.macos_x64)
    "macAarch64"(compose.desktop.macos_arm64)
    "windowsAmd64"(compose.desktop.windows_x64)
}

// Workaround for a long-standing Compose Multiplatform issue where
// configuration resolution sometimes picks an HTML/Web variant for
// JVM-only consumers. Forcing the "awt" UI variant fixes it.
// See: https://github.com/JetBrains/compose-jb/issues/1404
//
// Gradle 9 forbids mutating attributes on bucket (dependency-only)
// configurations, so apply only to resolvable/consumable ones.
configurations.matching { it.isCanBeResolved || it.isCanBeConsumed }.configureEach {
    attributes {
        attribute(Attribute.of("ui", String::class.java), "awt")
    }
}
