// Root build script.
// Most cross-cutting config lives in convention plugins under build-logic/. Spotless is the
// exception — it scans **/*.kt across the whole tree, so it has to live at the root.

plugins {
    alias(libs.plugins.spotless)
    // The dependency-analysis plugin requires KGP to be loaded in the same classloader (or a
    // parent), so we declare the Kotlin plugins here with `apply false`. They're actually
    // applied by subprojects via convention plugins (build-logic).
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.dependency.analysis)
}

spotless {
    val ktlintVersion = libs.versions.ktlint.get()
    kotlin {
        target("**/*.kt")
        targetExclude(
            "**/build/**",
            "**/.gradle/**",
            "**/generated/**",
            ".claude/worktrees/**",
            ".worktrees/**",
        )
        // ktlint settings live in `.editorconfig` so spotless and detekt-formatting (both
        // ktlint-backed) read the same `max_line_length` and rule disables. Without
        // .editorconfig, spotless's ktlint runs unbounded and joins multi-line signatures into
        // single overlong lines that detekt-formatting (capped at 140 via detekt.yml) rejects.
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude(
            "**/build/**",
            ".claude/worktrees/**",
            ".worktrees/**",
        )
        ktlint(ktlintVersion)
    }
}

dependencyAnalysis {
    issues {
        all {
            // NOTE: deliberately no `onAny { severity(...) }`. `onAny` is a catch-all that
            // overrides the per-category severities below — once it's set to "fail", every
            // category fails, even ones we explicitly downgraded. Per the DA wiki, configure
            // the categories you care about and leave the rest at default ("warn").
            onUnusedDependencies {
                severity("fail")
                // Compiler-plugin-sourced deps that DA can't see usage of through bytecode
                // (they're referenced from generated code). Excluded globally so every
                // feature/leaf module doesn't have to repeat the suppression.
                exclude(
                    // Compose: the compose compiler plugin generates references to the
                    // Multiplatform Compose runtime/foundation/ui artifacts; in JVM target
                    // they resolve to the *-desktop variants, so DA sees the declared KMP
                    // artifact as "unused" and the desktop variant as "used transitively".
                    // List all supported desktop platforms so the build doesn't fail on a
                    // host other than the one this exclusion was first written on.
                    "org.jetbrains.compose.runtime:runtime",
                    "org.jetbrains.compose.foundation:foundation",
                    "org.jetbrains.compose.ui:ui",
                    "org.jetbrains.compose.desktop:desktop-jvm-windows-x64",
                    "org.jetbrains.compose.desktop:desktop-jvm-macos-x64",
                    "org.jetbrains.compose.desktop:desktop-jvm-macos-arm64",
                    "org.jetbrains.compose.desktop:desktop-jvm-linux-x64",
                    "org.jetbrains.compose.desktop:desktop-jvm-linux-arm64",
                    "org.jetbrains.compose.hot-reload:hot-reload-runtime-api",
                    // JetBrains KMP fork of androidx.lifecycle: same declared-vs-resolved
                    // KMP variant mismatch — `androidx.lifecycle.ViewModel` /
                    // `viewModelScope` / `collectAsStateWithLifecycle` are imported in
                    // sources but resolve from the lifecycle-*-desktop artifacts.
                    "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel",
                    "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose",
                    "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-savedstate",
                    "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3",
                    "org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose",
                    "org.jetbrains.androidx.navigation3:navigation3-ui",
                    // Metro DI compiler plugin generates @Inject / @ContributesBinding /
                    // metroViewModel wiring code that references these runtime artifacts;
                    // DA only sees the bytecode after compilation, by which point the
                    // generated references look transitive.
                    "dev.zacsweers.metro:runtime",
                    "dev.zacsweers.metro:metrox-viewmodel",
                    "dev.zacsweers.metro:metrox-viewmodel-compose",
                )
            }
            // KMP / Compose Multiplatform pulls a lot of transitive runtime; tightening this
            // to `fail` would force every leaf module to redeclare half of compose. Warn-only
            // is the deliberate baseline — revisit if signal-to-noise improves.
            onUsedTransitiveDependencies {
                severity("warn")
            }
            // KMP advice for `implementation` -> `api` is dominated by the same KMP-variant
            // artifact noise as transitive deps (DA suggests api'ing every project dep that
            // exposes Metro/Compose types). Keep at warn until the signal-to-noise improves.
            onIncorrectConfiguration {
                severity("warn")
            }
            onRedundantPlugins {
                severity("fail")
            }
        }
    }
}

tasks.named("check") {
    dependsOn("buildHealth")
}

// `clean` is provided by the `base` plugin (transitively applied by spotless).
