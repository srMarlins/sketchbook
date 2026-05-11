package com.sketchbook.cloud.metadata

/**
 * A single doc inside an observed collection: its id (the last path segment) paired with
 * the decoded value. Returned by [MetadataStore.observeCollection] so listener-driven
 * consumers (SyncCoordinator) can route per-doc deltas without baking the id into the
 * `@Serializable` wire shape.
 */
data class CollectionEntry<T>(
    val id: String,
    val value: T,
)
