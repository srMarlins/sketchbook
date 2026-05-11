package com.sketchbook.cloud.metadata

import kotlin.jvm.JvmInline

/**
 * Slash-delimited Firestore document path: `/users/<uid>/trees/<treeId>` etc. Stored without
 * a leading slash so adapters can prepend one or not depending on the SDK in use.
 *
 * Validates at construction so a malformed path can't slip past via string concatenation in a
 * call site. Firestore requires an even number of path segments for documents (collection /
 * doc-id pairs); enforce that here.
 */
@JvmInline
value class DocPath(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "DocPath must not be empty" }
        require(!value.startsWith("/")) { "DocPath must not start with /: '$value'" }
        require(!value.endsWith("/")) { "DocPath must not end with /: '$value'" }
        val segments = value.split('/')
        require(segments.size % 2 == 0) {
            "DocPath must have an even number of segments (collection/doc pairs): '$value' has ${segments.size}"
        }
        require(segments.all { it.isNotBlank() }) { "DocPath has empty segments: '$value'" }
    }

    val collection: CollectionPath
        get() = CollectionPath(value.substringBeforeLast('/'))

    val id: String
        get() = value.substringAfterLast('/')

    override fun toString(): String = value

    companion object {
        /** Build `users/<uid>/trees/<treeId>`. */
        fun tree(
            uid: String,
            treeId: String,
        ): DocPath = DocPath("users/$uid/trees/$treeId")

        /** Build `users/<uid>/machines/<hostId>`. */
        fun machine(
            uid: String,
            hostId: String,
        ): DocPath = DocPath("users/$uid/machines/$hostId")

        /** Build `users/<uid>/plugins/<hostId>`. */
        fun plugins(
            uid: String,
            hostId: String,
        ): DocPath = DocPath("users/$uid/plugins/$hostId")

        /** Build `users/<uid>/locks/<treeId>`. */
        fun lock(
            uid: String,
            treeId: String,
        ): DocPath = DocPath("users/$uid/locks/$treeId")
    }
}

/**
 * Slash-delimited Firestore collection path. Like [DocPath] but with an ODD number of
 * segments (collection / doc / collection / doc / .../ collection).
 */
@JvmInline
value class CollectionPath(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "CollectionPath must not be empty" }
        require(!value.startsWith("/")) { "CollectionPath must not start with /: '$value'" }
        require(!value.endsWith("/")) { "CollectionPath must not end with /: '$value'" }
        val segments = value.split('/')
        require(segments.size % 2 == 1) {
            "CollectionPath must have an odd number of segments: '$value' has ${segments.size}"
        }
        require(segments.all { it.isNotBlank() }) { "CollectionPath has empty segments: '$value'" }
    }

    /** Append a doc id: `users/<uid>/trees`.doc(`projectUuid`) = `users/<uid>/trees/<projectUuid>`. */
    fun doc(id: String): DocPath = DocPath("$value/$id")

    override fun toString(): String = value

    companion object {
        /** Build `users/<uid>/trees`. */
        fun trees(uid: String): CollectionPath = CollectionPath("users/$uid/trees")

        /** Build `users/<uid>/machines`. */
        fun machines(uid: String): CollectionPath = CollectionPath("users/$uid/machines")
    }
}
