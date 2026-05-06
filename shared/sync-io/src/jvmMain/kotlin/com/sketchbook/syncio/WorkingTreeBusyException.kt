package com.sketchbook.syncio

/**
 * Thrown by [ManifestMaterializer] when one or more destination files are currently held open
 * by another process (typically Ableton Live with the project open). The pull/rewind caller
 * should surface this as "Close project in Ableton to apply" rather than retrying immediately.
 */
class WorkingTreeBusyException(val busyPaths: List<String>) : Exception("Working tree is busy: ${busyPaths.joinToString(", ")}")
