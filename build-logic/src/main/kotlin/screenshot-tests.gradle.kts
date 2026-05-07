@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

plugins {
    id("kmp-compose")
    // Apply the Compose Multiplatform plugin so `compose.uiTest` / `compose.desktop.currentOs`
    // accessors resolve in this precompiled script. Consumer modules also apply it via the
    // catalog alias — Gradle treats double-application as a no-op.
    id("org.jetbrains.compose")
    id("io.github.takahirom.roborazzi")
}

// Pull catalog so this script can resolve `libs.*` aliases.
val libs = the<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        // Roborazzi compose-desktop tests run on the JVM target only — Skia surface
        // initialisation and the desktop UI test harness are JVM-bound. Hosting them
        // in `jvmTest` (not `commonTest`) keeps the dep + plugin off other targets if
        // the project ever adds them.
        val jvmTest by getting {
            dependencies {
                // kotlin("test") is intentionally NOT added here — feature modules already
                // declare it in commonTest, which jvmTest inherits via the KMP source-set
                // hierarchy. Re-declaring it here would trip dependency-analysis's
                // redundant-declaration check.
                implementation(libs.findLibrary("roborazzi-compose-desktop").get())
                // Compose's official UI test harness — provides runDesktopComposeUiTest.
                implementation(compose.uiTest)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

// Roborazzi's `verify` task is intentionally NOT wired into `check`. Captures are
// for Claude's eyes, not regression gating. Tests that capture run on demand via
// `recordRoborazziJvm` only.
tasks.matching { it.name == "check" }.configureEach {
    setDependsOn(
        dependsOn.filterNot {
            (it as? String)?.startsWith("verifyRoborazzi") == true ||
                (it as? org.gradle.api.Task)?.name?.startsWith("verifyRoborazzi") == true
        },
    )
}
