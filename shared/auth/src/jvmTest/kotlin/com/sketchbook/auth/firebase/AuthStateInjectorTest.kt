package com.sketchbook.auth.firebase

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Locks down the wire-shape of the `FirebaseUserImpl` JSON we feed Pattern A1's storage hijack.
 * If firebase-java-sdk's deserializer ever drops one of the field aliases we rely on, this test
 * fails before a release ships.
 */
class AuthStateInjectorTest {
    @Test
    fun `JSON shape contains every field the SDK reads`() {
        val tokens =
            FirebaseTokens(
                idToken = "ID_TOKEN_VALUE",
                refreshToken = "REFRESH_TOKEN_VALUE",
                uid = "UID_VALUE",
                expiresAt = Instant.fromEpochMilliseconds(1_700_003_600_000),
                email = "alice@example.com",
            )
        val nowMillis = 1_700_000_000_000L

        val json = AuthStateInjector.firebaseUserImplJson(tokens, nowMillis = nowMillis)
        val obj = Json.parseToJsonElement(json).jsonObject

        assertEquals("UID_VALUE", obj["uid"]?.jsonPrimitive?.content)
        assertEquals("ID_TOKEN_VALUE", obj["idToken"]?.jsonPrimitive?.content)
        assertEquals("REFRESH_TOKEN_VALUE", obj["refreshToken"]?.jsonPrimitive?.content)
        assertEquals(3600, obj["expiresIn"]?.jsonPrimitive?.int)
        assertEquals(1_700_000_000_000L, obj["createdAt"]?.jsonPrimitive?.long)
        assertEquals("alice@example.com", obj["email"]?.jsonPrimitive?.content)
        assertEquals(false, obj["isAnonymous"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `expiresIn floors at 60 seconds even for an already-expired token`() {
        // Token expired one minute ago; raw math yields a negative number, which the SDK would
        // treat as "refresh immediately" — fine, but expressing it as 60s gives the SDK one
        // good operation before kicking off its own refresh cycle (and avoids weird signed
        // arithmetic in the SDK's expiry check).
        val tokens =
            FirebaseTokens(
                idToken = "T",
                refreshToken = "R",
                uid = "U",
                expiresAt = Instant.fromEpochMilliseconds(1_700_000_000_000 - 60_000),
                email = null,
            )
        val json = AuthStateInjector.firebaseUserImplJson(tokens, nowMillis = 1_700_000_000_000)
        val obj = Json.parseToJsonElement(json).jsonObject

        assertEquals(60, obj["expiresIn"]?.jsonPrimitive?.int)
    }

    @Test
    fun `email is omitted when null instead of written as JSON null`() {
        // The SDK's deserializer differentiates "no email field" (fine) from "email field set to
        // null" (would NPE on some code paths). Omit when null.
        val tokens =
            FirebaseTokens(
                idToken = "T",
                refreshToken = "R",
                uid = "U",
                expiresAt = Instant.fromEpochMilliseconds(1_700_003_600_000),
                email = null,
            )
        val json = AuthStateInjector.firebaseUserImplJson(tokens, nowMillis = 1_700_000_000_000)
        val obj = Json.parseToJsonElement(json).jsonObject

        assertNull(obj["email"])
    }

    @Test
    fun `FIREBASE_USER_STORAGE_KEY matches the Android SDK constant verbatim`() {
        // If this ever drifts, Pattern A1 silently breaks — the SDK looks under the original
        // key, finds nothing, and boots signed-out. Regression test against typos.
        assertEquals("com.google.firebase.auth.FIREBASE_USER", FIREBASE_USER_STORAGE_KEY)
    }

    @Test
    fun `JvmFirebasePlatform round-trips seed values plus subsequent writes`() {
        val platform =
            JvmFirebasePlatform(
                seed = mapOf(FIREBASE_USER_STORAGE_KEY to """{"uid":"seeded"}"""),
            )
        assertEquals("""{"uid":"seeded"}""", platform.retrieve(FIREBASE_USER_STORAGE_KEY))

        platform.store("other", "value")
        assertEquals("value", platform.retrieve("other"))

        platform.clear(FIREBASE_USER_STORAGE_KEY)
        assertNull(platform.retrieve(FIREBASE_USER_STORAGE_KEY))
        assertEquals("value", platform.retrieve("other"))
    }

    @Test
    fun `JvmFirebasePlatform getDatabasePath returns a writable temp path`() {
        val platform = JvmFirebasePlatform()
        val path = platform.getDatabasePath("sample")
        assertTrue(path.absolutePath.contains("sketchbook-firebase-sample"))
    }
}
