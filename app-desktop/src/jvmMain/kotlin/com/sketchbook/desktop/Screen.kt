package com.sketchbook.desktop

import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectUuid

/**
 * NavStack screen variants. Plain Kotlin sealed interface — no navigation framework.
 * The shell renders one screen at a time; back/forward is `pop` / `push` on the stack.
 */
sealed interface Screen {
    data object Projects : Screen
    data class ProjectDetail(val id: ProjectId) : Screen
    data class Timeline(val uuid: ProjectUuid) : Screen
    data object Proposals : Screen
    data object NeedsAttention : Screen
    data object Settings : Screen
}
