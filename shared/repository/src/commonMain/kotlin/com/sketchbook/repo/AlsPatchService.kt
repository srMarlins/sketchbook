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
    suspend fun patch(alsPath: String, mapping: Map<String, String>): Outcome
}
