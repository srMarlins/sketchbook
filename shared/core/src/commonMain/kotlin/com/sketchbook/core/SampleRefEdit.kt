package com.sketchbook.core

/**
 * One pending SampleRef edit. Identifies the SampleRef by its current primary `<Path Value="..."/>`
 * (the one whose grandparent is `<SampleRef>`). All `new*` fields are optional; null means leave
 * the corresponding attribute as-is.
 *
 * **Both the primary FileRef and its `SourceContext/SourceContext/OriginalFileRef/FileRef`
 * sibling within the same SampleRef are updated.** Live re-derives paths from the sibling under
 * some operations; patching only the primary causes silent reverts.
 *
 * **CRC handling:** the Ableton CRC algorithm is unsolved publicly. The recommended pattern is
 * to pass `newOriginalCrc = 0L` whenever the path changes, which forces Live to recompute on its
 * next save. Preserve the existing CRC only when you are confident the bytes are identical.
 */
data class SampleRefEdit(
    val oldPath: String,
    val newPath: String,
    val newRelativePath: String? = null,
    val newRelativePathType: Int? = null,
    val newOriginalFileSize: Long? = null,
    val newOriginalCrc: Long? = null,
    val newLastModDate: Long? = null,
)
