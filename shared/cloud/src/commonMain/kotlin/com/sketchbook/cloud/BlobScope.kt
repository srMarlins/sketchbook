package com.sketchbook.cloud

import com.sketchbook.core.ProjectUuid

/**
 * Where a blob lives in the bucket.
 *
 * - [Shared] — the default content-addressed pool: `<user>/blobs/<aa>/<hash>`. Identical bytes
 *   uploaded by any project for this user dedup naturally.
 * - [Private] — a per-project pool: `<user>/blobs-private/<uuid>/<aa>/<hash>`. Used when a
 *   project is marked self-contained (sync_state.self_contained = 1). Uploads still dedup
 *   *within* the project (re-syncing the same project doesn't double-upload), but never with
 *   any other project — useful when the user wants a hard guarantee that the project's bytes
 *   travel with the project (e.g. for client deliverables, archival).
 */
sealed interface BlobScope {
    data object Shared : BlobScope

    data class Private(
        val uuid: ProjectUuid,
    ) : BlobScope
}
