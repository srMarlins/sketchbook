package com.sketchbook.desktop.ui.setup

import com.sketchbook.core.Os

/**
 * Indirection over the JVM's `os.name` system property so screens, view-models, and tests
 * agree on a single canonical [Os] value. Wired in `DesktopAppGraph` as a constant captured
 * at graph-build time so the VM doesn't pay a `System.getProperty` lookup on every reprobe /
 * recomposition. Tests construct `OsProvider { Os.Mac }` (or similar) directly.
 *
 * Promoted out of [PluginChecklistViewModel]'s nested companion so the graph can `@Provides`
 * it without straddling a class-load — and renamed from the prior `OsProvider.System` to
 * [OsProvider.Default] so the name doesn't shadow `java.lang.System` inside the impl.
 *
 * Returns the typed [Os] enum (#129); the wire string is available via [Os.wireName].
 */
fun interface OsProvider {
    fun os(): Os

    companion object {
        /**
         * Canonical implementation backed by `os.name`. The label is computed once via [lazy]
         * and reused for every call — re-reading the property on every `os()` would do nothing
         * useful, since the JVM's OS doesn't change at runtime.
         */
        val Default: OsProvider =
            object : OsProvider {
                private val cached: Os by lazy {
                    Os.fromOsName(java.lang.System.getProperty("os.name"))
                }

                override fun os(): Os = cached
            }
    }
}
