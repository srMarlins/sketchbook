package com.sketchbook.desktop.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.sketchbook.core.Action
import com.sketchbook.core.ActionKind
import com.sketchbook.core.BannerKey
import com.sketchbook.core.DefaultUserMessageBus
import com.sketchbook.core.UserMessage
import com.sketchbook.uishared.components.PaperPage
import com.sketchbook.uishared.components.Text
import com.sketchbook.uishared.theme.AppTheme
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test

/**
 * Roborazzi captures for the global error-surfacing chrome. Each test pre-seeds the bus with the
 * banner/snackbar state we want to verify visually, then renders the host wrapping a placeholder
 * "page" so the layered layout (banner-stack above, content below, snackbar bottom-overlay) is
 * obvious at a glance.
 *
 * Snackbar captures emit immediately before rendering; with the test harness's virtual clock the
 * 5s auto-dismiss timer can't fire mid-render, so the snackbar is reliably visible.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class UserMessageHostScreenshots {
    @Test
    fun empty_state() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                AppTheme {
                    val bus = DefaultUserMessageBus()
                    UserMessageHost(bus = bus, onSignIn = {}, onOpenSettings = {}) {
                        PlaceholderPage("Empty state")
                    }
                }
            }
            onRoot().captureRoboImage("build/roborazzi/user_message_host_empty.png")
        }

    @Test
    fun single_offline_banner() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                AppTheme {
                    val bus =
                        DefaultUserMessageBus().apply {
                            emit(
                                UserMessage.Banner(
                                    key = BannerKey.Offline,
                                    text = "You're offline. Sketchbook will sync when you reconnect.",
                                ),
                            )
                        }
                    UserMessageHost(bus = bus, onSignIn = {}, onOpenSettings = {}) {
                        PlaceholderPage("Offline")
                    }
                }
            }
            onRoot().captureRoboImage("build/roborazzi/user_message_host_offline_banner.png")
        }

    @Test
    fun auth_expired_banner_with_signin_cta() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                AppTheme {
                    val bus =
                        DefaultUserMessageBus().apply {
                            emit(
                                UserMessage.Banner(
                                    key = BannerKey.AuthExpired,
                                    text = "Sign-in expired. Sign in again to keep syncing.",
                                    action = Action("Sign in", ActionKind.SignIn),
                                ),
                            )
                        }
                    UserMessageHost(bus = bus, onSignIn = {}, onOpenSettings = {}) {
                        PlaceholderPage("Auth expired")
                    }
                }
            }
            onRoot().captureRoboImage("build/roborazzi/user_message_host_auth_banner.png")
        }

    @Test
    fun two_banners_stacked() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                AppTheme {
                    val bus =
                        DefaultUserMessageBus().apply {
                            emit(UserMessage.Banner(BannerKey.Offline, "You're offline. Sketchbook will sync when you reconnect."))
                            emit(
                                UserMessage.Banner(
                                    BannerKey.QuotaExceeded,
                                    "Cloud storage limit reached.",
                                    Action("Open settings", ActionKind.OpenSettings),
                                ),
                            )
                        }
                    UserMessageHost(bus = bus, onSignIn = {}, onOpenSettings = {}) {
                        PlaceholderPage("Two banners")
                    }
                }
            }
            onRoot().captureRoboImage("build/roborazzi/user_message_host_two_banners.png")
        }

    @Test
    fun snackbar_only() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                AppTheme {
                    val bus =
                        DefaultUserMessageBus().apply {
                            emit(UserMessage.Snackbar("Cloud is having trouble — retrying."))
                        }
                    UserMessageHost(bus = bus, onSignIn = {}, onOpenSettings = {}) {
                        PlaceholderPage("Snackbar only")
                    }
                }
            }
            onRoot().captureRoboImage("build/roborazzi/user_message_host_snackbar.png")
        }

    @Test
    fun snackbar_with_action_cta() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                AppTheme {
                    val bus =
                        DefaultUserMessageBus().apply {
                            emit(
                                UserMessage.Snackbar(
                                    text = "Couldn't push — cloud is having trouble.",
                                    action = Action("Open settings", ActionKind.OpenSettings),
                                ),
                            )
                        }
                    UserMessageHost(bus = bus, onSignIn = {}, onOpenSettings = {}) {
                        PlaceholderPage("Snackbar with action")
                    }
                }
            }
            onRoot().captureRoboImage("build/roborazzi/user_message_host_snackbar_with_action.png")
        }

    @Test
    fun banner_plus_snackbar() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                AppTheme {
                    val bus =
                        DefaultUserMessageBus().apply {
                            emit(UserMessage.Banner(BannerKey.Offline, "You're offline. Sketchbook will sync when you reconnect."))
                            emit(UserMessage.Snackbar("Couldn't save your settings."))
                        }
                    UserMessageHost(bus = bus, onSignIn = {}, onOpenSettings = {}) {
                        PlaceholderPage("Banner + snackbar")
                    }
                }
            }
            onRoot().captureRoboImage("build/roborazzi/user_message_host_banner_and_snackbar.png")
        }
}

@androidx.compose.runtime.Composable
private fun PlaceholderPage(label: String) {
    PaperPage {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = label, style = AppTheme.typography.title)
        }
    }
}
