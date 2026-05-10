package com.sketchbook.cloud.metadata

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Wire shape for `/users/{uid}/trees/{treeId}` per the data model in
 * docs/plans/2026-05-08-firebase-migration-design.md §"Data model".
 *
 * **treeId == ProjectUuid.value.** Phase 3 maps one tree per project; the design's notion of
 * `TrackedTreeKind` (Project / UserLibrary / …) collapses to `"Project"` for everything we
 * ship today. Field stays on the doc so v1.2 (UserLibrary trees) can populate it without a
 * schema change.
 *
 * **owner_user_id is load-bearing for Security Rules.** firestore.rules enforces
 * `owner_user_id == uid` on every create/update of `/users/{uid}/trees/{treeId}`. Without
 * it the write is rejected server-side. Always set it to the signed-in UID.
 *
 * **collaborators** is the v1.2 scaffold. Empty array today; populated by a future
 * sharing flow. The Security Rules collaborator branch is also scaffolded but disabled
 * (`if false`) until then.
 *
 * **head_rev / head_gen / head_updated_at / head_updated_by_host** are the listener-driven
 * fields that drive cross-machine sync. SnapshotPipeline writes these after a successful
 * blob CAS; SyncCoordinator listens for advances and fires PullPoller.pollOnce on the
 * receiving end.
 */
@Serializable
data class TreeDoc(
    val owner_user_id: String,
    val kind: String = "Project",
    val scope_key: String = "",
    val display_name: String = "",
    val created_at: Instant,
    val created_by_host: String = "",
    val collaborators: List<Collaborator> = emptyList(),
    val head_rev: Long = 0,
    val head_gen: String = "",
    val head_updated_at: Instant,
    val head_updated_by_host: String = "",
) {
    @Serializable
    data class Collaborator(
        val uid: String,
        val role: String,
    )
}
