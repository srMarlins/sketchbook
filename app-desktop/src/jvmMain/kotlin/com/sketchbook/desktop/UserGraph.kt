package com.sketchbook.desktop

import com.sketchbook.cloud.CloudBackend

/**
 * Per-user object graph. Lifetime: `AuthState.SignedIn` ↔ `SignedOut`, scoped to a specific
 * `(UserId, bucket)` pair. Built and torn down by [UserGraphHolder] as `AuthSession.state` and
 * the configured `cloudBucket` change.
 *
 * **Implementation note.** This is hand-rolled rather than a Metro `@GraphExtension`. Metro
 * 1.0.0's `@GraphExtension` annotation does not take a `parent` argument (the parent linkage is
 * implicit via a `@GraphExtension.Factory` interface that the parent graph extends). The plan's
 * sketch in §11.2 was based on a slightly different shape; rather than burn iterations on the
 * exact factory syntax for a single-binding subgraph, the holder constructs the underlying
 * [com.sketchbook.cloud.DirectGcsBackend] directly. Promote to a real `@GraphExtension` if and
 * when this graph grows past one or two bindings, or when sync services move into it.
 *
 * See `docs/architecture/dependency-injection.md` §1.1.
 */
class UserGraph(
    val cloudBackend: CloudBackend,
)
