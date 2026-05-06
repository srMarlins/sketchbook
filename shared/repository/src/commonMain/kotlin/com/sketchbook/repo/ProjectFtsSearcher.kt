package com.sketchbook.repo

/**
 * Project FTS5 search seam. The actual implementation lives in `shared/catalog/jvmMain`'s
 * `CatalogFts` (which talks to the JDBC driver directly). This interface keeps
 * [com.sketchbook.repo.impl.SqlProjectRepository] in `commonMain` while still being
 * constructor-injected by Metro — the desktop graph adapts `CatalogFts::search` to it.
 *
 * Returns the matched project rowids in relevance order. Empty query → empty list.
 */
fun interface ProjectFtsSearcher {
    fun search(query: String): List<Long>
}
