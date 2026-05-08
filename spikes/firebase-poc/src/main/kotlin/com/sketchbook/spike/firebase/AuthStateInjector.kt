/*
 * Pattern A1 (storage hijack) implementation.
 *
 * Verified by reading firebase-java-sdk 0.6.3 source (FirebaseAuth.kt:248–254):
 *
 *   private var user: FirebaseUserImpl? =
 *       FirebasePlatform.firebasePlatform
 *           .runCatching {
 *               retrieve(app.key)?.let {
 *                   FirebaseUserImpl(app, data = jsonParser.parseToJsonElement(it).jsonObject)
 *               }
 *           }
 *           .onFailure { it.printStackTrace() }
 *           .getOrNull()
 *
 *   val FirebaseApp.key get() =
 *       "com.google.firebase.auth.FIREBASE_USER${"[$name]".takeUnless { isDefaultApp }.orEmpty()}"
 *
 * On boot, FirebaseAuth reads the persisted user from FirebasePlatform under this key. The
 * `FirebaseUserImpl(app, data: JsonObject)` constructor accepts EITHER camelCase
 * (`idToken`, `refreshToken`, `expiresIn`) OR snake_case (`id_token`, `refresh_token`,
 * `expires_in`), and maps `localId / user_id → uid`. So the Identity Toolkit
 * `signInWithIdp` response JSON works essentially as-is.
 *
 * Token refresh: when the cached idToken nears expiry (per `expiresIn` and `createdAt`),
 * `FirebaseAuth.getAccessToken()` POSTs the stored refreshToken to
 * `securetoken.googleapis.com/v1/token`. Same endpoint our IdentityToolkitClient.refresh
 * targets, same refresh-token format. Refresh "just works" — no manual handling needed
 * on our side after injection.
 */
package com.sketchbook.spike.firebase

import com.google.firebase.FirebasePlatform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Build the FirebaseUserImpl-shaped JSON the SDK expects in storage. The firebase-java-sdk
 * deserializer accepts a wider field set than the @Serializable one writes — we mirror
 * what the deserializer reads so this is the most permissive shape.
 *
 * Derived from FirebaseUserImpl(app, data: JsonObject) in firebase-java-sdk:
 *   uid           ← "uid" | "user_id" | "localId"
 *   idToken       ← "idToken" | "id_token"
 *   refreshToken  ← "refreshToken" | "refresh_token"
 *   expiresIn     ← "expiresIn" | "expires_in"
 *   createdAt     ← "createdAt" | now()
 *   email         ← "email"
 *   photoUrl      ← "photoUrl" | "photo_url"
 *   displayName   ← "displayName" | "display_name"
 *   isAnonymous   ← "isAnonymous" | false
 */
fun firebaseUserImplJson(
    tokens: FirebaseTokens,
    isAnonymous: Boolean = false,
): String {
    val expiresInSeconds =
        ((tokens.expiresAt.toEpochMilliseconds() - System.currentTimeMillis()) / 1000)
            .coerceAtLeast(60)
            .toInt()
    val obj: JsonObject =
        buildJsonObject {
            put("uid", JsonPrimitive(tokens.uid))
            put("idToken", JsonPrimitive(tokens.idToken))
            put("refreshToken", JsonPrimitive(tokens.refreshToken))
            put("expiresIn", JsonPrimitive(expiresInSeconds))
            put("createdAt", JsonPrimitive(System.currentTimeMillis()))
            tokens.email?.let { put("email", JsonPrimitive(it)) }
            put("isAnonymous", JsonPrimitive(isAnonymous))
        }
    return Json.encodeToString(JsonObject.serializer(), obj)
}

/**
 * Storage key the SDK looks under for the default app's persisted auth state. Stable
 * constant — has been the same on Android Firebase Auth since 2019; firebase-java-sdk
 * follows the Android source verbatim.
 */
const val FIREBASE_USER_STORAGE_KEY: String = "com.google.firebase.auth.FIREBASE_USER"

/**
 * Pre-seed [JvmFirebasePlatform] storage with our externally-obtained Firebase tokens
 * BEFORE `Firebase.initialize(...)` is called. The SDK reads the seed on FirebaseAuth
 * construction and boots in a signed-in state — `currentUser` populated, Firestore RPCs
 * carry our token, listeners work.
 *
 * Returns the pre-seeded platform; pass it to [initializeFirebase] (or
 * `FirebasePlatform.initializeFirebasePlatform` directly).
 */
fun preSeededPlatform(tokens: FirebaseTokens): JvmFirebasePlatform =
    JvmFirebasePlatform(
        seed = mapOf(FIREBASE_USER_STORAGE_KEY to firebaseUserImplJson(tokens)),
    )
