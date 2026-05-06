// Root build script.
// Most cross-cutting config lives in convention plugins under build-logic/. Spotless is the
// exception — it scans **/*.kt across the whole tree, so it has to live at the root.

plugins {
    alias(libs.plugins.spotless)
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
        ktlint(ktlintVersion).editorConfigOverride(
            mapOf(
                "ktlint_standard_filename" to "disabled",
                "ktlint_standard_no-wildcard-imports" to "disabled",
                "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
            ),
        )
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

// `clean` is provided by the `base` plugin (transitively applied by spotless).
