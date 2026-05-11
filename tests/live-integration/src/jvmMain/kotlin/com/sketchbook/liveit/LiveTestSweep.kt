package com.sketchbook.liveit

import com.sketchbook.cloud.metadata.CollectionPath
import com.sketchbook.cloud.metadata.DocPath
import com.sketchbook.cloud.metadata.TreeDoc
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * Clean up live-integration test artifacts from Firestore.
 *
 * Scope: only docs under `users/<my-uid>/` whose tree id starts with the [LiveTestEnv.TEST_UUID_PREFIX]
 * `liveit-` prefix. The Security Rules block reaches outside `users/<my-uid>/` regardless, but
 * the prefix filter is the additional belt-and-suspenders so a sweep can't accidentally nuke
 * a manually-created tree that doesn't follow the test naming.
 *
 * What gets deleted:
 *  - `users/<uid>/trees/<treeId>` — the tree doc (head pointer)
 *  - `users/<uid>/locks/<treeId>` — any orphaned lease
 *
 * **NOT deleted (known limitation):** storage blobs and manifest objects in GCS. [CloudBackend]
 * exposes no delete method today; doing so cleanly would mean either (a) adding `deleteBlob` to
 * the port + adapter, or (b) calling the GCS REST API directly from this script. Today's manifest
 * + blob bytes are content-addressed and tenant-scoped, so leftover bytes don't leak to other
 * users — they just sit unreferenced under your user's quota. If they pile up, run
 * `gcloud storage rm -r gs://<bucket>/users/<your-uid>/manifests/liveit-*` to clear.
 *
 * Dry-run by default. Pass `-Papply=true` to actually delete.
 * Optional `-PolderThanHours=<N>` skips trees whose `head_updated_at` is newer than N hours
 * — useful when a teammate's test is in flight on another machine.
 */
fun main() =
    runBlocking {
        val apply = LiveTestEnv.sweepApply()
        val olderThanHours = LiveTestEnv.sweepOlderThanHours()

        println("[liveTestSweep] mode=${if (apply) "APPLY" else "dry-run"} olderThanHours=$olderThanHours")
        LiveTestBootstrap.bootstrapForCloudOps().let { graph ->
            try {
                println("[liveTestSweep] signed in as ${graph.signedInEmail} (uid=${graph.userId.value})")
                val trees =
                    graph.metadataStore
                        .observeCollection(
                            CollectionPath("users/${graph.userId.value}/trees"),
                            TreeDoc.serializer(),
                        ).first()
                val testTrees = trees.filter { it.id.startsWith(LiveTestEnv.TEST_UUID_PREFIX) }
                val now = Clock.System.now()
                val cutoff = if (olderThanHours > 0L) now - olderThanHours.hours else null

                val candidates =
                    testTrees.filter { entry ->
                        cutoff == null || entry.value.head_updated_at < cutoff
                    }
                val skipped = testTrees.size - candidates.size
                println(
                    "[liveTestSweep] discovered trees=${trees.size} test=${testTrees.size} " +
                        "candidates=${candidates.size} skipped-by-age=$skipped",
                )
                for (entry in candidates) {
                    println(
                        "  ${if (apply) "delete" else "would-delete"} ${entry.id} " +
                            "head_rev=${entry.value.head_rev} updated=${entry.value.head_updated_at}",
                    )
                    if (apply) {
                        // Delete lock first so a half-finished sweep doesn't leave a stranded
                        // lease pointing at a deleted tree.
                        runCatching {
                            graph.metadataStore.deleteDoc(DocPath.lock(graph.userId.value, entry.id))
                        }.onFailure { println("    (warn) lock delete failed: ${it.message}") }
                        graph.metadataStore.deleteDoc(DocPath.tree(graph.userId.value, entry.id))
                    }
                }
                if (!apply && candidates.isNotEmpty()) {
                    println()
                    println("Dry-run only. Re-run with -Papply=true to delete the trees above.")
                }
            } finally {
                graph.shutdown()
            }
        }
    }
