package com.sketchbook.auth

import com.github.javakeyring.Keyring
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyringTokenStoreTest {
    /**
     * Probe whether a usable OS keychain backend is on the JVM. Returns false on headless Linux
     * runners (no `libsecret` / `gnome-keyring`); macOS Keychain and Windows Credential Manager
     * succeed unconditionally on dev workstations + GitHub-hosted macOS / Windows runners.
     */
    private fun keyringAvailable(): Boolean = runCatching { Keyring.create() }.isSuccess

    @Test
    fun `read returns null when keyring missing`() =
        runTest {
            // This test exercises the read-side error swallowing — it works whether or not a real
            // keychain backend is installed.
            val store = KeyringTokenStore(serviceName = "sketchbook-test-${System.nanoTime()}", accountName = "default")
            store.clear()
            assertNull(store.read())
        }

    @Test
    fun `write then read round-trips`() =
        runTest {
            assumeTrue("OS keychain backend not available — skipping (expected on headless Linux CI)", keyringAvailable())
            val service = "sketchbook-test-${System.nanoTime()}"
            val store = KeyringTokenStore(serviceName = service, accountName = "default")
            try {
                store.write("rt-abc-123")
                assertEquals("rt-abc-123", store.read())
            } finally {
                store.clear()
            }
        }

    @Test
    fun `clear removes the entry`() =
        runTest {
            assumeTrue("OS keychain backend not available — skipping (expected on headless Linux CI)", keyringAvailable())
            val service = "sketchbook-test-${System.nanoTime()}"
            val store = KeyringTokenStore(serviceName = service, accountName = "default")
            store.write("rt-clear-me")
            store.clear()
            assertNull(store.read())
        }
}
