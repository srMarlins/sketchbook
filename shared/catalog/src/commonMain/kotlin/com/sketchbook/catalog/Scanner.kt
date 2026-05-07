package com.sketchbook.catalog

/**
 * One emission from a library scan. Producers (`Scanner` impls per platform) emit a single
 * `Started` first, one `ProjectIndexed` per project, optional `ProjectFailed`s for parse errors,
 * and one `Finished` at the end. Consumers can render a progress bar from `total`/`done`.
 */
sealed interface ScanProgress {
    val total: Int
    val done: Int

    data class Started(
        override val total: Int,
    ) : ScanProgress {
        override val done: Int = 0
    }

    data class ProjectIndexed(
        override val total: Int,
        override val done: Int,
        val projectId: Long,
        val path: String,
        val name: String,
        val missingSampleCount: Int = 0,
        val effortScore: Int? = null,
    ) : ScanProgress

    data class ProjectFailed(
        override val total: Int,
        override val done: Int,
        val path: String,
        val reason: String,
    ) : ScanProgress

    data class Finished(
        override val total: Int,
        override val done: Int,
        val durationMillis: Long,
    ) : ScanProgress
}
