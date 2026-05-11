package com.sketchbook.cloud

import kotlinx.serialization.Serializable

/**
 * Pointer to a manifest object in cloud storage.
 *
 * NOTE (Phase 3, 2026-05-10): this file used to host the `LeaseLock`,
 * `LeaseAcquireResult`, and `LeaseRefreshResult` types that wrapped CloudBackend's lock
 * primitives. Locks moved to Firestore-backed [com.sketchbook.cloud.metadata.MetadataStore]
 * docs (`/users/{uid}/locks/{treeId}` of shape [com.sketchbook.cloud.metadata.LockDoc]),
 * and CloudBackend no longer exposes lock methods. The Lock.kt file is kept as the home of
 * [ManifestRef] because manifest listing still lives on CloudBackend; one less rename
 * surface across the rest of the codebase.
 */
@Serializable
data class ManifestRef(
    val rev: Long,
    val path: String,
    val generation: Generation,
)
