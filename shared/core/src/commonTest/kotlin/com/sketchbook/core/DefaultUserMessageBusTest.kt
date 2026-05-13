package com.sketchbook.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultUserMessageBusTest {
    @Test
    fun emittedSnackbarReachesSubscriber() =
        runTest {
            val bus = DefaultUserMessageBus()
            val received = CompletableDeferred<UserMessage.Snackbar>()
            val job = launch { received.complete(bus.snackbars.first()) }
            // Ensure the collector subscribes before emit — tryEmit only reaches current subscribers
            // and the test dispatcher would otherwise sequence emit before launch's body.
            testScheduler.runCurrent()
            bus.emit(UserMessage.Snackbar("hello"))
            assertEquals(UserMessage.Snackbar("hello"), received.await())
            job.cancel()
        }

    @Test
    fun emittedBannerLandsInBannersMapKeyedByBannerKey() {
        val bus = DefaultUserMessageBus()
        bus.emit(UserMessage.Banner(BannerKey.Offline, "offline now"))
        assertEquals(
            UserMessage.Banner(BannerKey.Offline, "offline now"),
            bus.banners.value[BannerKey.Offline],
        )
    }

    @Test
    fun reEmittingSameBannerKeyCoalescesAndKeepsLatestWording() {
        val bus = DefaultUserMessageBus()
        bus.emit(UserMessage.Banner(BannerKey.Offline, "first"))
        bus.emit(UserMessage.Banner(BannerKey.Offline, "second"))
        val banners = bus.banners.value
        assertEquals(1, banners.size)
        assertEquals("second", banners[BannerKey.Offline]?.text)
    }

    @Test
    fun differentBannerKeysCoexist() {
        val bus = DefaultUserMessageBus()
        bus.emit(UserMessage.Banner(BannerKey.Offline, "offline"))
        bus.emit(UserMessage.Banner(BannerKey.AuthExpired, "auth"))
        val banners = bus.banners.value
        assertEquals(2, banners.size)
        assertTrue(BannerKey.Offline in banners)
        assertTrue(BannerKey.AuthExpired in banners)
    }

    @Test
    fun retractBannerRemovesEntry() {
        val bus = DefaultUserMessageBus()
        bus.emit(UserMessage.Banner(BannerKey.Offline, "x"))
        bus.retractBanner(BannerKey.Offline)
        assertNull(bus.banners.value[BannerKey.Offline])
    }

    @Test
    fun retractBannerForAbsentKeyIsNoop() {
        val bus = DefaultUserMessageBus()
        bus.retractBanner(BannerKey.QuotaExceeded)
        assertTrue(bus.banners.value.isEmpty())
    }

    @Test
    fun snackbarEmitDoesNotBlockOrThrowWithoutSubscribers() {
        val bus = DefaultUserMessageBus()
        repeat(100) { bus.emit(UserMessage.Snackbar("n=$it")) }
    }
}
