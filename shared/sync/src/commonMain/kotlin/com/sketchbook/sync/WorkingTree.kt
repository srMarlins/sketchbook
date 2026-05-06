package com.sketchbook.sync

import com.sketchbook.core.BlobHash
import kotlinx.io.RawSource
import kotlin.time.Instant

/**
 * The pipeline's view of a project's on-disk tree. Abstracts JVM `Path` walks so [SnapshotPipeline]
 * can run identically against a real filesystem and against in-memory test doubles.
 */
interface WorkingTree {

    /** Project-relative paths of every snapshottable file in the tree. */
    fun list(): List<String>

    /** Last-modified time + byte size for [relativePath]. */
    fun stat(relativePath: String): FileStat

    /** Stream the file's bytes. The hasher closes the source. */
    fun read(relativePath: String): RawSource

    /** Stable BLAKE3 hash for [relativePath]. Pipeline calls this only when mtime/size diverge. */
    fun hash(relativePath: String): BlobHash
}

data class FileStat(val size: Long, val mtime: Instant)
