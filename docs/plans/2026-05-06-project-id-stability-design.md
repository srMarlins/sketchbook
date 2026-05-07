# Project-id stability + shared ProjectDisplay resolver

**Date:** 2026-05-06
**Branch:** `feat/project-id-stability`
**Status:** approved, in implementation

## Problem

History shows `"project #254"` instead of an actual project name. Same class of bug has surfaced on Proposals, Needs Attention, and Journal — every surface has invented its own fallback chain.

Two contributors:

1. **`projects.id` orphaning across rescans.** The JVM scanner upserts with `INSERT OR REPLACE INTO projects (path, ...)`. SQLite's `OR REPLACE` deletes-and-reinserts on conflict, so even though `path` is unique, every rescan mints a *new* autoincrement `id`. Every dependent table that keys on the int (`journal_entries`, `repair_acks`, `proposal_acks`, `project_samples`, `project_plugins`, `project_tags`) is left pointing at the dead id. Snapshots and sync_state are unaffected — they key on `project_uuid`.
2. **No shared name resolver.** Each surface invents a fallback chain (Proposals: `args.name → args.path basename → "project #ID"`; Journal: `denormName → catalog → action-payload → "project #ID"`). Drift is inevitable.

## Approach: A1 + B

### A1 — id-preserving upsert

Stop the bleed at the source. Rescan switches from `INSERT OR REPLACE` to read-then-update-or-insert:

```kotlin
catalog.transaction {
    val existing = selectProjectIdByPath(path).executeAsOneOrNull()
    if (existing != null) {
        updateProjectByPath(path = path, name = ..., parent_dir = ..., ...)
    } else {
        insertProject(...)
    }
}
```

Same path = same row id forever. Move/Rename mutators in `SqlProjectRepository` already use `UPDATE` — leave them. Orphan source is rescan only.

UUID-keyed lookup (instead of path) is *not* useful here because UUIDs are minted locally by `SyncStateStore.identityFor(id)`, not parsed from the `.als`. A deleted+recreated `.als` is genuinely a new project.

A2 (migrate every dependent table to `project_uuid`) is rejected as preemptive — A1 fixes the bug class and the data model's int↔uuid split is a documentation issue, not a correctness one once orphaning is prevented.

### B — `ProjectDisplay` resolver

New pure function in `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/ProjectDisplay.kt`:

```kotlin
data class ProjectDisplayHints(
    val denormName: String? = null,
    val pathHint: String? = null,
)

fun resolveProjectDisplay(
    id: ProjectId,
    hints: ProjectDisplayHints = ProjectDisplayHints(),
    nameById: Map<ProjectId, String> = emptyMap(),
): String
```

Fallback chain:
1. `hints.denormName` (captured at write time; survives rescans).
2. `nameById[id]` (live catalog lookup).
3. basename of `hints.pathHint`, with `.als` stripped.
4. `"project #${id.value}"` sentinel.

Always returns a non-null string.

### Migration `9.sqm`

Backfill legacy journal rows whose `project_name` is null but whose `project_id` still resolves:

```sql
UPDATE journal_entries
SET project_name = (SELECT p.name FROM projects p WHERE p.id = journal_entries.project_id)
WHERE project_name IS NULL
  AND EXISTS (SELECT 1 FROM projects p WHERE p.id = journal_entries.project_id);
```

Genuinely-orphaned rows (project gone from catalog) keep showing the sentinel — `pathHint` rescues path-bearing actions; the rest fall through. Acceptable; the data is gone.

## Affected files

- **A1**: `shared/catalog/src/jvmMain/kotlin/com/sketchbook/catalog/JvmScanner.kt` (and any other `insertOrReplaceProject` callsite in the catalog/sync modules).
- **A1 SQL**: `shared/catalog/src/commonMain/sqldelight/com/sketchbook/catalog/db/Catalog.sq` — add `updateProjectByPath`.
- **B resolver**: new `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/ProjectDisplay.kt`.
- **B callsites**: `shared/feature-journal/.../JournalViewModel.kt` (replace `resolveName`); `shared/feature-proposals/.../format/ProposalLabel.kt` (replace inline chain).
- **Migration**: new `shared/catalog/src/commonMain/sqldelight/com/sketchbook/catalog/db/9.sqm`; bump probe in `CatalogDb.kt`.

## Tests

- `JvmScannerIdStabilityTest` (new): rescan at same path twice, assert `project_id` unchanged + a journal entry written between rescans still resolves.
- `ProjectDisplayTest` (new): one assertion per fallback layer + orphan sentinel.
- Existing `JournalViewModelTest`, `ProposalLabelTest` should pass with the inlined call replaced.

## Rollout

Three commits in one PR:
1. `feat(catalog): preserve project_id across rescans` — A1 + scanner test.
2. `refactor(repo): shared ProjectDisplay resolver` — B + callsite swaps + resolver test.
3. `feat(catalog): backfill journal_entries.project_name (9.sqm)` — migration + probe bump.

User reviews + merges manually. Doc deleted post-merge per no-standing-plans rule.
