@file:Suppress("UnstableApiUsage")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

plugins {
    // Auto-provisions the JDK that matches `jvmToolchain(...)` if it isn't installed locally
    // (e.g. CI machines without JDK 21 pre-installed). Gradle downloads from the Foojay API,
    // verifies, and pins it in `~/.gradle/jdks/`.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "sketchbook"

include(
    ":shared:core",
    ":shared:parser-als",
    ":shared:catalog",
    ":shared:cloud",
    ":shared:sync-io",
    ":shared:repository",
    ":shared:actions",
    ":shared:auth",
    ":shared:sync",
    ":shared:mcp-server",
    ":shared:ui-shared",
    ":shared:feature-projects",
    ":shared:feature-project-detail",
    ":shared:feature-timeline",
    ":shared:feature-proposals",
    ":shared:feature-needs-attention",
    ":shared:feature-onboarding",
    ":shared:feature-settings",
    ":shared:feature-journal",
    ":app-desktop",
    ":app-mcp",
    ":tests:architecture",
    ":tests:integration",
    // Phase 0 spike for the Firebase migration. Throwaway module — delete with the
    // whole `spikes/firebase-poc/` directory once findings are appended to
    // docs/plans/2026-05-08-firebase-migration-design.md.
    ":spikes:firebase-poc",
)

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
