package com.sketchbook.migration

import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
import com.sketchbook.repo.TreeRegistryEntry
import kotlinx.coroutines.flow.Flow

/**
 * One-shot relocation tool. v=1 builds wrote project manifests under
 * `<tenant>/manifests/<project_uuid>/...`; v=2 expects them under
 * `<tenant>/trees/project/<tree_id>/manifests/...`. The migrator moves them, builds the
 * `<tenant>/registry.json` `CloudDoc` from local catalog rows, and registers a UserLibrary
 * tree with `scope_key = "default"`.
 *
 * **Idempotent.** Re-running on a partially-migrated bucket completes the rest — copies are
 * gated by an `if-generation-match: 0` precondition, so a destination object that already
 * exists is treated as "already migrated" and skipped. Source objects are intentionally
 * **not** deleted by the migrator: the rolling-upgrade window has sibling hosts still
 * reading legacy paths, and a follow-up cleanup PR drops them once `machines.json` shows
 * everyone is on v=2.
 *
 * **Mandatory on first launch.** The desktop coordinator gates app startup on completion;
 * see `docs/plans/2026-05-07-backend-generalization-design.md` § "Migration UX".
 */
interface CloudMigrator {
    /**
     * Probe the bucket without making changes. Returns [MigrationStatus.UpToDate] when no
     * legacy paths are detected (or `Settings.cloudMigrationComplete` is already set);
     * otherwise [MigrationStatus.Pending] with the dry-run report.
     */
    suspend fun status(): MigrationStatus

    /**
     * Run the migration. Emits [MigrationProgress] events so the dialog can show progress
     * for users with thousands of legacy manifests. The terminal event is either
     * [MigrationProgress.Done] (success) or [MigrationProgress.Failed].
     *
     * On success, [com.sketchbook.repo.SettingsRepository.markCloudMigrationComplete] is
     * called so subsequent launches skip the dialog.
     */
    fun migrate(): Flow<MigrationProgress>
}

/** Result of [CloudMigrator.status]. */
sealed interface MigrationStatus {
    /** No work to do — either the bucket is empty or the migration already ran. */
    data object UpToDate : MigrationStatus

    /** Work pending. [report] gives the user counts to display in the confirmation dialog. */
    data class Pending(
        val report: MigrationReport,
    ) : MigrationStatus
}

/**
 * Counts surfaced in the confirmation dialog. Mirrors the design doc mock:
 *
 *     • 23 project trees
 *     • 412 manifest files to relocate
 *     • 0 blob files to move (content stays put)
 */
data class MigrationReport(
    val projectTreesPending: Int,
    val manifestsPending: Int,
    val userLibraryPending: Boolean,
)

/** Streaming progress for the migrate dialog. */
sealed interface MigrationProgress {
    data object Probing : MigrationProgress

    /** [done] / [total] manifest files have been relocated. */
    data class Relocating(
        val done: Int,
        val total: Int,
    ) : MigrationProgress

    data object BuildingRegistry : MigrationProgress

    data class RegisteredTree(
        val entry: TreeRegistryEntry,
    ) : MigrationProgress

    /** Migration finished and `cloudMigrationComplete` was persisted. */
    data class Done(
        val report: MigrationReport,
        val registry: List<TreeRegistryEntry>,
    ) : MigrationProgress

    /** Migration failed; the local settings flag is **not** flipped. */
    data class Failed(
        val reason: String,
    ) : MigrationProgress
}

/**
 * Stable string identifiers used in cloud paths. Centralized here so the migrator and
 * [com.sketchbook.cloud.CloudBackend] callers agree on the legacy/v=2 layout.
 */
object MigrationLayout {
    /** Legacy manifest prefix: `<tenant>/manifests/<project_uuid>/...` (project-only in v=1). */
    const val LEGACY_MANIFESTS_PREFIX: String = "manifests/"

    /** v=2 manifest prefix: `<tenant>/trees/<kind>/<tree_id>/manifests/...`. */
    fun v2ManifestPrefix(
        treeId: TrackedTreeId,
        kind: TrackedTreeKind,
    ): String = "trees/${kind.wireName}/${treeId.value}/manifests/"
}
