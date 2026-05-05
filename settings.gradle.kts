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
    ":shared:sync",
    ":shared:mcp-server",
    ":shared:ui-shared",
    ":shared:feature-projects",
    ":shared:feature-project-detail",
    ":app-desktop",
    ":app-mcp",
)

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
