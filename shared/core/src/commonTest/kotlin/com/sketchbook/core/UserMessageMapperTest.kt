package com.sketchbook.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserMessageMapperTest {
    @Test
    fun nullStatusRemoteFailureBecomesOfflineBanner() {
        val msg = remote(status = null).toUserMessage(ErrorContext.Sync)
        val banner = assertNotNull(msg) as UserMessage.Banner
        assertEquals(BannerKey.Offline, banner.key)
        assertTrue("reconnect" in banner.text, "Offline copy mentions reconnect: ${banner.text}")
        assertNull(banner.action)
    }

    @Test
    fun authStatusBecomesAuthExpiredBannerWithSignInCta() {
        for (status in listOf(401, 403)) {
            val msg = remote(status = status).toUserMessage(ErrorContext.Sync)
            val banner = assertNotNull(msg) as UserMessage.Banner
            assertEquals(BannerKey.AuthExpired, banner.key)
            assertEquals(Action("Sign in", ActionKind.SignIn), banner.action)
        }
    }

    @Test
    fun quotaStatusBecomesQuotaBannerWithSettingsCta() {
        for (status in listOf(413, 429, 507)) {
            val msg = remote(status = status).toUserMessage(ErrorContext.Sync)
            val banner = assertNotNull(msg) as UserMessage.Banner
            assertEquals(BannerKey.QuotaExceeded, banner.key)
            assertEquals(Action("Open settings", ActionKind.OpenSettings), banner.action)
        }
    }

    @Test
    fun other5xxBecomesTransientSnackbar() {
        for (status in listOf(500, 502, 503, 504)) {
            val msg = remote(status = status).toUserMessage(ErrorContext.Sync)
            val snackbar = assertNotNull(msg) as UserMessage.Snackbar
            assertTrue("retrying" in snackbar.text.lowercase(), "5xx copy mentions retrying: ${snackbar.text}")
            assertNull(snackbar.action)
        }
    }

    @Test
    fun unmappedHttpStatusFallsBackToSnackbarWithCode() {
        val msg = remote(status = 418).toUserMessage(ErrorContext.Sync)
        val snackbar = assertNotNull(msg) as UserMessage.Snackbar
        assertTrue("418" in snackbar.text, "Fallback wording surfaces the HTTP code: ${snackbar.text}")
    }

    @Test
    fun ioFailureWordingDependsOnContext() {
        val cases =
            mapOf(
                ErrorContext.Settings to "Couldn't save your settings.",
                ErrorContext.FileSystem to "Sketchbook can't read that folder — check permissions.",
                ErrorContext.Scan to "Sketchbook can't read that folder — check permissions.",
                ErrorContext.Snapshot to "Couldn't read project files for a snapshot.",
                ErrorContext.Lock to "Couldn't update the project lock.",
                ErrorContext.Sync to "Disk error during sync.",
            )
        for ((context, expected) in cases) {
            val msg = SketchbookError.IoFailure("disk full").toUserMessage(context)
            val snackbar = assertNotNull(msg) as UserMessage.Snackbar
            assertEquals(expected, snackbar.text, "context=$context")
        }
    }

    @Test
    fun conflictAndIntegrityAndNotFoundEachGetTheirOwnSnackbar() {
        val conflict = SketchbookError.Conflict("rev clash").toUserMessage(ErrorContext.Sync)
        val integrity = SketchbookError.IntegrityError("bad blob").toUserMessage(ErrorContext.Sync)
        val notFound = SketchbookError.NotFound("missing").toUserMessage(ErrorContext.Sync)
        assertTrue(conflict is UserMessage.Snackbar)
        assertTrue(integrity is UserMessage.Snackbar)
        assertTrue(notFound is UserMessage.Snackbar)
        assertTrue(conflict.text != integrity.text, "Conflict and Integrity should read differently")
    }

    @Test
    fun unknownThrowableFallsBackToGenericSnackbar() {
        val msg = IllegalStateException("unexpected").toUserMessage(ErrorContext.Sync)
        val snackbar = assertNotNull(msg) as UserMessage.Snackbar
        assertTrue("diagnostics" in snackbar.text.lowercase(), "Generic copy mentions diagnostics: ${snackbar.text}")
    }

    @Test
    fun cancellationExceptionReturnsNullSoCancelledOpsDoNotShowAGenericError() {
        val msg =
            kotlin.coroutines.cancellation
                .CancellationException("user navigated away")
                .toUserMessage(ErrorContext.Sync)
        kotlin.test.assertNull(msg)
    }

    private fun remote(status: Int?): SketchbookError.RemoteFailure =
        SketchbookError.RemoteFailure(
            status = status,
            body = null,
            message = "cloud failure status=$status",
        )
}
