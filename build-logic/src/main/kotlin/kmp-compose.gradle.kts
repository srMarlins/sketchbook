plugins {
    id("kmp-library")
    // Compose Multiplatform + Compose compiler plugins are applied per-module via the
    // version-catalog plugin aliases. This convention plugin is reserved for shared
    // Compose-specific config once a module actually consumes Compose APIs.
}
