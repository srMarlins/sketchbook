package com.sketchbook.desktop.ui.setup

/**
 * Indirection over the JVM's `os.name` system property so screens, view-models, and tests
 * agree on a single canonical OS label (`darwin` / `windows` / `linux`). Wired in
 * `DesktopAppGraph` as a constant captured at graph-build time so the VM doesn't pay a
 * `System.getProperty` lookup on every reprobe / recomposition. Tests construct
 * `OsProvider { "darwin" }` (or similar) directly.
 *
 * Promoted out of [PluginChecklistViewModel]'s nested companion so the graph can `@Provides`
 * it without straddling a class-load — and renamed from the prior `OsProvider.System` to
 * [OsProvider.Default] so the name doesn't shadow `java.lang.System` inside the impl.
 */
fun interface OsProvider {
    fun os(): String

    companion object {
        /**
         * Canonical implementation backed by `os.name`. The label is computed once via [lazy]
         * and reused for every call — re-reading the property on every `os()` would do nothing
         * useful, since the JVM's OS doesn't change at runtime.
         */
        val Default: OsProvider =
            object : OsProvider {
                private val cached: String by lazy {
                    val raw =
                        java.lang.System
                            .getProperty("os.name")
                            ?.lowercase()
                            .orEmpty()
                    when {
                        raw.contains("mac") || raw.contains("darwin") -> "darwin"
                        raw.contains("win") -> "windows"
                        else -> "linux"
                    }
                }

                override fun os(): String = cached
            }
    }
}
