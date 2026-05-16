package com.sketchbook.core

/**
 * Where a [Throwable] arose. The same underlying failure may need different wording depending on
 * what the user was doing — an [SketchbookError.IoFailure] during a scan reads as a permission
 * problem; the same during a settings write reads as "couldn't save". Pass the closest context.
 */
enum class ErrorContext {
    Sync,
    Scan,
    Settings,
    Lock,
    Snapshot,
    FileSystem,
}
