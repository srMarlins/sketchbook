package com.sketchbook.desktop

import androidx.navigation3.runtime.NavKey
import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectUuid
import com.sketchbook.desktop.inbox.InboxTab
import kotlinx.serialization.Serializable

/**
 * Top-level navigation destinations. Each variant is a Compose Navigation 3 [NavKey]; typed
 * arguments are constructor params on the data class — no string templates, no `NavType`.
 *
 * `kotlinx.serialization` handles save/restore through Nav3's `SavedStateConfiguration`. The
 * `SerializersModule` in `NavConfig` registers each subclass under [NavKey] for polymorphic
 * decode (Android uses reflection; JVM Desktop does not).
 */
@Serializable
sealed interface Screen : NavKey {
    @Serializable data object Projects : Screen

    @Serializable data class ProjectDetail(val id: ProjectId) : Screen

    @Serializable data class Timeline(val uuid: ProjectUuid) : Screen

    @Serializable data class Inbox(val tab: InboxTab = InboxTab.Proposals) : Screen

    @Serializable data object Settings : Screen
}
