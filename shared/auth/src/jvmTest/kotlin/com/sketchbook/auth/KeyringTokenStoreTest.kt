package com.sketchbook.auth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyringTokenStoreTest {

    @Test
    fun `read returns null when keyring missing`() = runTest {
        val store = KeyringTokenStore(serviceName = "sketchbook-test-${System.nanoTime()}", accountName = "default")
        store.clear()
        assertNull(store.read())
    }

    @Test
    fun `write then read round-trips`() = runTest {
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
    fun `clear removes the entry`() = runTest {
        val service = "sketchbook-test-${System.nanoTime()}"
        val store = KeyringTokenStore(serviceName = service, accountName = "default")
        store.write("rt-clear-me")
        store.clear()
        assertNull(store.read())
    }
}
