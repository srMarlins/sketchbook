package com.sketchbook.sync

import com.sketchbook.core.TrackedTreeKind

/**
 * Per-kind sync policy table. The pipeline reads from a policy rather than `if (kind == Project)`
 * branches so adding a kind is a one-row change. See
 * `docs/plans/2026-05-07-backend-generalization-design.md` §"KindPolicy".
 *
 * @param leaseRequired whether the snapshot pipeline must acquire a `LeaseLock` before walking the
 *   tree. `false` for kinds whose conflict mode handles concurrency without a lease.
 * @param conflictMode how the pipeline resolves CAS conflicts at HEAD-write time.
 * @param privateScopeAllowed informational; `BlobScope.Private` is still typed only against
 *   `ProjectUuid`, so kinds with this flag false can't request a private pool even if a future
 *   refactor opens up the scope type.
 * @param alsPatchingEnabled whether `AlsPatcher` runs over `.als` files post-materialize. False for
 *   `UserLibrary` because Live's browser handles intra-UL paths internally; flip to `true` if a
 *   future spike finds round-tripped templates with broken sample paths.
 * @param contributesToPluginManifest whether materialized `.als` / `.adg` files in this tree get
 *   walked for plugin references and contribute rows to the bootstrap-checklist union.
 */
data class KindPolicy(
    val leaseRequired: Boolean,
    val conflictMode: ConflictMode,
    val privateScopeAllowed: Boolean,
    val alsPatchingEnabled: Boolean,
    val contributesToPluginManifest: Boolean,
) {
    companion object {
        val Project: KindPolicy = KindPolicy(
            leaseRequired = true,
            conflictMode = ConflictMode.BranchFork,
            privateScopeAllowed = true,
            alsPatchingEnabled = true,
            contributesToPluginManifest = true,
        )

        val UserLibrary: KindPolicy = KindPolicy(
            leaseRequired = false,
            conflictMode = ConflictMode.Merge(DeletePolicy.Tombstones),
            privateScopeAllowed = false,
            alsPatchingEnabled = false,
            contributesToPluginManifest = true,
        )

        /**
         * Resolve a kind to its policy. [TrackedTreeKind.Unknown] (newer-binary entries) has no
         * known policy and throws — callers should filter unknown kinds out of pipeline work
         * upstream rather than rely on a default.
         */
        fun forKind(kind: TrackedTreeKind): KindPolicy = when (kind) {
            is TrackedTreeKind.Project -> Project
            is TrackedTreeKind.UserLibrary -> UserLibrary
            is TrackedTreeKind.Unknown -> error("No KindPolicy for unknown kind '${kind.wireName}'")
        }
    }
}
