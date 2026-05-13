package com.sketchbook.desktop.chrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sketchbook.core.Action
import com.sketchbook.core.ActionKind
import com.sketchbook.core.BannerKey
import com.sketchbook.core.UserMessage
import com.sketchbook.core.UserMessageBus
import com.sketchbook.uishared.components.BulkUndoSnackbar
import com.sketchbook.uishared.components.BulkUndoSnackbarState
import com.sketchbook.uishared.components.Button
import com.sketchbook.uishared.components.ButtonVariant
import com.sketchbook.uishared.components.ProvideContentColor
import com.sketchbook.uishared.components.Surface
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme

/**
 * App-global chrome for user-facing errors and notices. Wraps the entire UI tree (onboarding +
 * main app both) so background failures surface no matter which screen is active and survive
 * navigation. There is exactly one of these in the app, installed by `Main.kt`.
 *
 * Two surfaces:
 *  - **Banner stack** at the top (full window-width), one row per active [BannerKey]. Persistent
 *    until the underlying condition retracts the banner (offline → online, signed-out → signed-in).
 *  - **Snackbar** at the bottom-center for transient messages. Reuses [BulkUndoSnackbarState] —
 *    info-only mode, no Undo button. 5s auto-dismiss; most-recent-wins under burst.
 *
 * CTAs: banners may carry an [Action]. The host interprets [ActionKind.SignIn] and
 * [ActionKind.OpenSettings] by calling the caller-supplied lambdas (which know about
 * `AuthSession` / the nav back stack); [ActionKind.Dismiss] retracts the banner.
 */
@Composable
fun UserMessageHost(
    bus: UserMessageBus,
    onSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val banners by bus.banners.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember(scope) { BulkUndoSnackbarState(scope) }

    LaunchedEffect(bus) {
        bus.snackbars.collect { msg -> snackbar.show(message = msg.text) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (banners.isNotEmpty()) {
                BannerStack(
                    banners = banners.values.toList(),
                    onAction = { banner ->
                        banner.action?.let { action ->
                            dispatchAction(action, banner.key, bus, onSignIn, onOpenSettings)
                        }
                    },
                    onDismiss = { key -> bus.retractBanner(key) },
                )
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) { content() }
        }
        // Snackbar lives on top of content so a mid-screen state doesn't jump when one arrives.
        // Capped width keeps the surface readable on wide windows.
        BulkUndoSnackbar(
            state = snackbar,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(AppTheme.spacing.md)
                    .widthIn(max = 520.dp),
        )
    }
}

private fun dispatchAction(
    action: Action,
    key: BannerKey,
    bus: UserMessageBus,
    onSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    when (action.kind) {
        is ActionKind.SignIn -> onSignIn()
        is ActionKind.OpenSettings -> onOpenSettings()
        is ActionKind.Dismiss -> bus.retractBanner(key)
    }
}

@Composable
private fun BannerStack(
    banners: List<UserMessage.Banner>,
    onAction: (UserMessage.Banner) -> Unit,
    onDismiss: (BannerKey) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
    ) {
        for (banner in banners) {
            BannerRow(
                banner = banner,
                onActionClick = { onAction(banner) },
                onDismissClick = { onDismiss(banner.key) },
            )
        }
    }
}

@Composable
private fun BannerRow(
    banner: UserMessage.Banner,
    onActionClick: () -> Unit,
    onDismissClick: () -> Unit,
) {
    Surface(
        color = AppTheme.colors.tintRose,
        padding = PaddingValues(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ProvideContentColor(AppTheme.colors.inkPrimary) {
                Text(
                    text = banner.text,
                    style = AppTheme.typography.body,
                    modifier = Modifier.weight(1f),
                )
            }
            banner.action?.let { action ->
                Button(onClick = onActionClick, variant = ButtonVariant.Primary) {
                    Text(action.label)
                }
            }
            Button(onClick = onDismissClick, variant = ButtonVariant.Ghost) {
                Text("Dismiss")
            }
        }
    }
}
