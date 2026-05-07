package com.sketchbook.repo

/**
 * Repair-side abstraction over the on-disk `.als` rewriter. Lives in `:shared:repository` so
 * `SqlRepairRepository` can call into the patch pipe without pulling in `:shared:sync-io`
 * (which already depends on `:shared:repository` — a direct dep would create a module cycle).
 *
 * The concrete implementation in `:shared:sync-io` (`AlsPatcher`) does the StAX rewrite + atomic
 * temp+rename. Repository tests can substitute a fake to assert wiring without spinning up the
 * real file-system pipe.
 */
interface AlsPatchService {
    /** Mirrors `AlsPatcher.Outcome` 1:1. Repository code only needs the discriminator. */
    enum class Outcome { Patched, NoChange, SkippedBusy, Failed }

    /**
     * Rewrite [alsPath] in place by applying [mapping] to every `<SampleRef>` Path/RelativePath
     * value match. Implementations are responsible for atomicity and busy detection.
     *
     * @param alsPath absolute path to the `.als` file. Stringly-typed because `commonMain` can't
     *   reference `java.nio.file.Path`.
     */
    suspend fun patch(
        alsPath: String,
        mapping: Map<String, String>,
    ): Outcome

    /**
     * Rich-edit overload. Each [com.sketchbook.core.SampleRefEdit] is matched on the SampleRef's
     * primary `<Path>` (or `<RelativePath>`); on match, **both** the primary FileRef and its
     * `SourceContext/SourceContext/OriginalFileRef/FileRef` sibling are updated atomically — Live
     * re-derives paths from the sibling under some operations, so patching only the primary
     * causes silent reverts. Beyond paths, the edit can rewrite `OriginalFileSize`, `OriginalCrc`,
     * `RelativePathType`, and `LastModDate`; null fields are left untouched.
     *
     * Implementations honor atomicity and busy detection identically to [patch] above. An empty
     * [edits] list is a no-op that returns [Outcome.NoChange].
     *
     * @param alsPath absolute path to the `.als` file. Stringly-typed because `commonMain` can't
     *   reference `java.nio.file.Path`.
     */
    suspend fun patch(
        alsPath: String,
        edits: List<com.sketchbook.core.SampleRefEdit>,
    ): Outcome

    /**
     * Atomically replace the contents of [alsPath] with the supplied [bytes]. Used by PR-W W4's
     * Undo path: the repository captures the pre-patch bytes via a sidecar before calling
     * [patch]; on Undo it reads the sidecar back and feeds the bytes to this method.
     *
     * Implementations must honor busy detection (Live holding the file open) and use the same
     * atomic temp+rename dance [patch] does so concurrent readers never see a half-written file.
     */
    suspend fun restore(
        alsPath: String,
        bytes: ByteArray,
    ): Outcome
}
