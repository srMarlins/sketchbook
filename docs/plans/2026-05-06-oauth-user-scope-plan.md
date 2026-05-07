# OAuth + User/Cloud DI Scope — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (or
> superpowers:subagent-driven-development) to implement this plan task-by-task.

**Goal:** Replace the "paste service-account JSON" cloud-auth path with
Google OAuth, model the signed-in user with a Metro `@GraphExtension`
`UserScope`, and wire everything end-to-end so multiple users can use the
same shared GCS bucket without sharing credentials.

**Architecture:** New `shared/auth` KMP module owns the OAuth desktop flow
(loopback PKCE) + refresh-token storage in the OS keychain. A new
`CloudCredentials` interface decouples `DirectGcsBackend` from the JWT
signer; an OAuth-backed impl reuses `AuthSession`. A new `UserScope` Metro
graph extension is built on `AuthState.SignedIn` and torn down on
`SignedOut`, exposing per-user services (`CloudBackend`, sync queue,
materializer) to AppScope via a `UserGraphHolder` `StateFlow`.

**Tech stack:** Kotlin Multiplatform (commonMain interfaces, jvmMain
desktop impls), Metro DI 0.7.x, Ktor CIO HTTP client, kotlinx.serialization,
javakeyring (`com.github.javakeyring:java-keyring:1.0.4`) for OS-native
refresh-token storage.

**Design doc:** `docs/plans/2026-05-06-oauth-user-scope-design.md`. Read it
before starting.

---

## Conventions for this plan

- Every code block in this plan is the *full* contents of the file or the
  exact diff. No placeholders. No `// ... rest unchanged`.
- Tests live alongside production code in the same module's `*Test`
  source set unless stated otherwise. Use `kotlin.test` (already on the
  classpath in `kmp-test` modules).
- Build/test command from repo root:
  ```
  ./gradlew :<module>:check
  ```
  Full app: `./gradlew :app-desktop:check`. Faster compile-check during
  iteration: `./gradlew :<module>:compileKotlinJvm :<module>:jvmTest`.
- Commit after each task. Use `feat`, `refactor`, `test`, or `chore`
  prefixes per commit conventions visible in `git log`.
- This is a single PR. Drive through all tasks before requesting review.

---

## Task 1 — Add `shared/auth` module skeleton

**Files:**
- Create: `shared/auth/build.gradle.kts`
- Modify: `settings.gradle.kts:31-53`
- Modify: `gradle/libs.versions.toml` (add `javakeyring` entry)

### Step 1.1 — Create build script

Create `shared/auth/build.gradle.kts`:

```kotlin
plugins {
    id("kmp-test")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.javakeyring)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}
```

### Step 1.2 — Register the module

In `settings.gradle.kts`, add `":shared:auth",` to the `include(...)` list,
positioned alphabetically — between `":shared:actions",` and
`":shared:catalog",` (or anywhere in that block; pick the alphabetical
slot).

### Step 1.3 — Add javakeyring to the version catalog

In `gradle/libs.versions.toml`, under `[versions]` add:
```
javakeyring = "1.0.4"
```
Under `[libraries]` add:
```
javakeyring = { module = "com.github.javakeyring:java-keyring", version.ref = "javakeyring" }
```

### Step 1.4 — Verify build wires up

Run: `./gradlew :shared:auth:compileKotlinJvm`
Expected: `BUILD SUCCESSFUL` (no sources yet so it's effectively a no-op
compile; the goal is to confirm the module is registered and the
javakeyring dep resolves).

### Step 1.5 — Commit

```
git add shared/auth/build.gradle.kts settings.gradle.kts gradle/libs.versions.toml
git commit -m "build: scaffold shared/auth module"
```

---

## Task 2 — Auth domain types (commonMain)

**Files:**
- Create: `shared/auth/src/commonMain/kotlin/com/sketchbook/auth/AuthState.kt`
- Create: `shared/auth/src/commonMain/kotlin/com/sketchbook/auth/AuthSession.kt`
- Create: `shared/auth/src/commonMain/kotlin/com/sketchbook/auth/TokenStore.kt`
- Create: `shared/auth/src/commonMain/kotlin/com/sketchbook/auth/AuthErrors.kt`

### Step 2.1 — Define `AuthState`

`shared/auth/src/commonMain/kotlin/com/sketchbook/auth/AuthState.kt`:

```kotlin
package com.sketchbook.auth

import com.sketchbook.core.UserId

sealed interface AuthState {
    data object SignedOut : AuthState

    data class SignedIn(
        val userId: UserId,
        val email: String,
    ) : AuthState
}
```

### Step 2.2 — Define `AuthSession`

`shared/auth/src/commonMain/kotlin/com/sketchbook/auth/AuthSession.kt`:

```kotlin
package com.sketchbook.auth

import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the signed-in user lifecycle. [state] is the single source of truth — UI binds to it,
 * `UserGraphHolder` builds/tears down the user graph from it.
 *
 * Implementations must:
 *  - Read any persisted refresh token at construction and emit `SignedIn` if a fresh access
 *    token can be minted, without opening a browser.
 *  - Provide [accessToken] for cloud backends. Caches and refreshes transparently. Surfaces
 *    refresh failure by flipping [state] to `SignedOut` AND throwing — the caller (e.g.
 *    `OAuthCloudCredentials`) decides how to surface the failure to the user.
 */
interface AuthSession {

    val state: StateFlow<AuthState>

    /**
     * Launch the OAuth flow. Implementations open the system browser, run a loopback server, and
     * suspend until the user completes consent (or denies / times out).
     */
    suspend fun signIn(): Result<AuthState.SignedIn>

    /**
     * Revoke + clear the persisted refresh token. After this returns, [state] is `SignedOut`.
     * Best-effort revoke against the IdP — never fails the call on revoke errors.
     */
    suspend fun signOut()

    /**
     * Mint or return a cached access token. Throws [AuthSessionExpired] if refresh fails; in
     * that case [state] has already flipped to `SignedOut`.
     */
    suspend fun accessToken(): String
}
```

### Step 2.3 — Define `TokenStore`

`shared/auth/src/commonMain/kotlin/com/sketchbook/auth/TokenStore.kt`:

```kotlin
package com.sketchbook.auth

/**
 * Persists the refresh token. Backed by the OS keychain on jvmMain
 * (Keychain / Credential Manager / libsecret via javakeyring).
 *
 * Reads return `null` if no token is present or the keystore is unavailable. Implementations
 * MUST NOT throw on read — keystore unavailability is an expected state (first launch, locked
 * keychain on Linux) and the caller treats it as `SignedOut`.
 *
 * Writes throw [TokenStoreException] on failure so a sign-in can't silently succeed without
 * persisting the refresh token.
 */
interface TokenStore {
    suspend fun read(): String?
    suspend fun write(refreshToken: String)
    suspend fun clear()
}

class TokenStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```

### Step 2.4 — Define error types

`shared/auth/src/commonMain/kotlin/com/sketchbook/auth/AuthErrors.kt`:

```kotlin
package com.sketchbook.auth

sealed class AuthException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class OAuthCancelled : AuthException("Sign-in cancelled by user")

class OAuthTimeout : AuthException("OAuth callback did not arrive in time")

class OAuthFailed(message: String, cause: Throwable? = null) : AuthException(message, cause)

class AuthSessionExpired(cause: Throwable? = null) : AuthException("Refresh token rejected — sign in again", cause)
```

### Step 2.5 — Verify compile

Run: `./gradlew :shared:auth:compileKotlinJvm`
Expected: `BUILD SUCCESSFUL`.

### Step 2.6 — Commit

```
git add shared/auth/src/commonMain
git commit -m "feat(auth): AuthSession, AuthState, TokenStore interfaces"
```

---

## Task 3 — TokenStore javakeyring impl (jvmMain) + tests

**Files:**
- Create: `shared/auth/src/jvmMain/kotlin/com/sketchbook/auth/KeyringTokenStore.kt`
- Create: `shared/auth/src/jvmTest/kotlin/com/sketchbook/auth/KeyringTokenStoreTest.kt`

### Step 3.1 — Write the failing test first

`shared/auth/src/jvmTest/kotlin/com/sketchbook/auth/KeyringTokenStoreTest.kt`:

```kotlin
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
```

### Step 3.2 — Run the test, expect compile failure

Run: `./gradlew :shared:auth:jvmTest --tests KeyringTokenStoreTest`
Expected: compile failure — `KeyringTokenStore` doesn't exist.

### Step 3.3 — Implement `KeyringTokenStore`

`shared/auth/src/jvmMain/kotlin/com/sketchbook/auth/KeyringTokenStore.kt`:

```kotlin
package com.sketchbook.auth

import com.github.javakeyring.BackendNotSupportedException
import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [TokenStore] backed by the OS keychain via javakeyring. Keychain access is blocking I/O on
 * desktop OSes (it can prompt the user on macOS), so every call hops to [Dispatchers.IO].
 *
 * `serviceName` namespaces the entry inside the keychain — production uses
 * `"com.sketchbook.refresh"`. Tests pass a unique name per test so they don't collide on a
 * shared dev workstation.
 */
class KeyringTokenStore(
    private val serviceName: String,
    private val accountName: String,
) : TokenStore {

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val keyring = Keyring.create()
            keyring.getPassword(serviceName, accountName)
        }.getOrNull()
    }

    override suspend fun write(refreshToken: String) = withContext(Dispatchers.IO) {
        try {
            Keyring.create().setPassword(serviceName, accountName, refreshToken)
        } catch (e: BackendNotSupportedException) {
            throw TokenStoreException("OS keychain not available", e)
        } catch (e: PasswordAccessException) {
            throw TokenStoreException("keychain write failed: ${e.message}", e)
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching {
            Keyring.create().deletePassword(serviceName, accountName)
        }
        Unit
    }
}
```

### Step 3.4 — Run the test, expect pass

Run: `./gradlew :shared:auth:jvmTest --tests KeyringTokenStoreTest`
Expected: 3 tests pass.

**If the test fails on a CI/headless machine** because no keychain backend
is installed: that's fine for a local dev box, but mark the test class
with `@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named =
"sketchbook.run.keychain.tests", matches = "true")` (after adding the
`junit-jupiter` test dep) — *only if CI fails*. Locally it just works.
Don't pre-disable.

### Step 3.5 — Commit

```
git add shared/auth/src/jvmMain shared/auth/src/jvmTest
git commit -m "feat(auth): KeyringTokenStore via javakeyring"
```

---

## Task 4 — OAuthClient (loopback PKCE flow) + tests

**Files:**
- Create: `shared/auth/src/jvmMain/kotlin/com/sketchbook/auth/OAuthClient.kt`
- Create: `shared/auth/src/jvmTest/kotlin/com/sketchbook/auth/OAuthClientTest.kt`

### Step 4.1 — Test plan

The OAuthClient test stubs Google's auth and token endpoints and drives the
client through one full sign-in. We don't actually open a browser in tests
— the test injects a `browserOpener` that calls back into the test's HTTP
client to hit the loopback callback URL with a fake code.

### Step 4.2 — Write the failing test

`shared/auth/src/jvmTest/kotlin/com/sketchbook/auth/OAuthClientTest.kt`:

```kotlin
package com.sketchbook.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OAuthClientTest {

    @Test
    fun `signIn round-trip exchanges code for tokens`() = runTest {
        // Mock Google's token endpoint: returns access+refresh+id token for any code.
        val tokenEngine = MockEngine { request ->
            val body = """
                {
                  "access_token": "at-FAKE",
                  "refresh_token": "rt-FAKE",
                  "id_token": "${makeFakeIdToken(sub = "11223344", email = "alice@example.com")}",
                  "expires_in": 3600,
                  "token_type": "Bearer"
                }
            """.trimIndent()
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val client = OAuthClient(
            httpClient = HttpClient(tokenEngine),
            clientId = "test-client",
            tokenUri = "https://oauth2.googleapis.com/token",
            authUri = "https://accounts.google.com/o/oauth2/v2/auth",
            scopes = listOf("openid", "email"),
            // Inject a fake browser: as soon as the loopback server is up, it hits the callback.
            browserOpener = { url ->
                val state = Regex("state=([^&]+)").find(url)!!.groupValues[1]
                val redirectUri = Regex("redirect_uri=([^&]+)").find(url)!!.groupValues[1]
                    .replace("%3A", ":").replace("%2F", "/")
                val callbackHttp = HttpClient()
                kotlinx.coroutines.GlobalScope.async {
                    callbackHttp.get("$redirectUri?code=fake-code&state=$state")
                    callbackHttp.close()
                }
            },
        )

        val result = client.signIn()
        val tokens = result.getOrThrow()
        assertEquals("at-FAKE", tokens.accessToken)
        assertEquals("rt-FAKE", tokens.refreshToken)
        assertEquals("11223344", tokens.userId.value)
        assertEquals("alice@example.com", tokens.email)
    }
}

/**
 * Build an unsigned, base64url JWT with the given payload — enough for OAuthClient to parse `sub`
 * + `email`, since signature verification of Google ID tokens is not in OAuthClient's job (the
 * tokens come from the IdP's own TLS-secured endpoint).
 */
private fun makeFakeIdToken(sub: String, email: String): String {
    val header = """{"alg":"none","typ":"JWT"}"""
    val payload = """{"sub":"$sub","email":"$email","aud":"test-client","iss":"https://accounts.google.com"}"""
    fun b64(s: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
    return "${b64(header)}.${b64(payload)}.fake-sig"
}
```

### Step 4.3 — Run the test, expect compile failure

Run: `./gradlew :shared:auth:jvmTest --tests OAuthClientTest`
Expected: compile failure — `OAuthClient` doesn't exist.

### Step 4.4 — Implement `OAuthClient`

`shared/auth/src/jvmMain/kotlin/com/sketchbook/auth/OAuthClient.kt`:

```kotlin
package com.sketchbook.auth

import com.sketchbook.core.UserId
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration.Companion.minutes

/**
 * Desktop OAuth via loopback redirect with PKCE. Spins up an ephemeral HTTP server on
 * 127.0.0.1:<random>, opens the system browser to the auth URL, captures the code, exchanges
 * it for tokens, and shuts the server down.
 *
 * Public clients (desktop apps) don't use a client secret per [RFC 8252]; PKCE is the proof.
 */
class OAuthClient(
    private val httpClient: HttpClient,
    private val clientId: String,
    private val authUri: String = "https://accounts.google.com/o/oauth2/v2/auth",
    private val tokenUri: String = "https://oauth2.googleapis.com/token",
    private val scopes: List<String>,
    private val callbackTimeout: kotlin.time.Duration = 5.minutes,
    /** Test seam — production uses [java.awt.Desktop.browse]. */
    private val browserOpener: (String) -> Unit = ::openInSystemBrowser,
) {

    suspend fun signIn(): Result<OAuthTokens> = runCatching {
        val verifier = randomVerifier()
        val challenge = sha256B64Url(verifier)
        val state = randomState()
        val deferredCode = CompletableDeferred<Result<String>>()
        val server = startLoopback(expectedState = state, deferredCode = deferredCode)
        try {
            val redirectUri = "http://127.0.0.1:${server.address.port}/callback"
            val authUrl = buildAuthUrl(
                redirectUri = redirectUri,
                state = state,
                challenge = challenge,
            )
            browserOpener(authUrl)
            val codeResult = withTimeoutOrNull(callbackTimeout) { deferredCode.await() }
                ?: throw OAuthTimeout()
            val code = codeResult.getOrElse { throw it }
            exchangeCodeForTokens(code = code, verifier = verifier, redirectUri = redirectUri)
        } finally {
            server.stop(0)
        }
    }

    suspend fun refresh(refreshToken: String): RefreshResult = withContext(Dispatchers.IO) {
        val response = httpClient.submitForm(
            url = tokenUri,
            formParameters = Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", clientId)
            },
        )
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            // Google returns 400 invalid_grant when a refresh token is revoked or expired.
            return@withContext RefreshResult.Invalid(body)
        }
        val parsed = JSON.decodeFromString(TokenResponse.serializer(), body)
        RefreshResult.Ok(
            accessToken = parsed.accessToken,
            expiresInSeconds = parsed.expiresIn,
        )
    }

    private suspend fun exchangeCodeForTokens(
        code: String,
        verifier: String,
        redirectUri: String,
    ): OAuthTokens = withContext(Dispatchers.IO) {
        val response = httpClient.submitForm(
            url = tokenUri,
            formParameters = Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("client_id", clientId)
                append("code_verifier", verifier)
                append("redirect_uri", redirectUri)
            },
        )
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw OAuthFailed("token exchange failed: ${response.status} $body")
        }
        val parsed = JSON.decodeFromString(TokenResponse.serializer(), body)
        val (sub, email) = parseSubAndEmail(parsed.idToken)
        OAuthTokens(
            accessToken = parsed.accessToken,
            refreshToken = parsed.refreshToken
                ?: throw OAuthFailed("no refresh_token in token response — did you set access_type=offline?"),
            expiresInSeconds = parsed.expiresIn,
            userId = UserId(sub),
            email = email,
        )
    }

    private fun startLoopback(
        expectedState: String,
        deferredCode: CompletableDeferred<Result<String>>,
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/callback") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            val params = parseQuery(query)
            val state = params["state"]
            val code = params["code"]
            val error = params["error"]
            val responseHtml = if (error != null) {
                "<html><body><h1>Sign-in failed</h1><p>${escapeHtml(error)}</p></body></html>"
            } else {
                "<html><body><h1>Signed in to Sketchbook</h1><p>You can close this window.</p></body></html>"
            }
            val bytes = responseHtml.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            when {
                error == "access_denied" -> deferredCode.complete(Result.failure(OAuthCancelled()))
                error != null -> deferredCode.complete(Result.failure(OAuthFailed("OAuth error: $error")))
                state != expectedState -> deferredCode.complete(Result.failure(OAuthFailed("state mismatch")))
                code == null -> deferredCode.complete(Result.failure(OAuthFailed("missing code")))
                else -> deferredCode.complete(Result.success(code))
            }
        }
        server.start()
        return server
    }

    private fun buildAuthUrl(redirectUri: String, state: String, challenge: String): String {
        val params = mapOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to scopes.joinToString(" "),
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "access_type" to "offline",
            "prompt" to "consent",
        )
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "$authUri?$query"
    }

    sealed interface RefreshResult {
        data class Ok(val accessToken: String, val expiresInSeconds: Long) : RefreshResult
        data class Invalid(val body: String) : RefreshResult
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("id_token") val idToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long = 3600,
        @SerialName("token_type") val tokenType: String = "Bearer",
    )

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val userId: UserId,
    val email: String,
)

private fun openInSystemBrowser(url: String) {
    val desktop = java.awt.Desktop.getDesktop()
    desktop.browse(java.net.URI(url))
}

private fun randomVerifier(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun sha256B64Url(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

private fun randomState(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun parseQuery(raw: String): Map<String, String> = raw
    .split("&")
    .filter { it.isNotEmpty() }
    .mapNotNull {
        val idx = it.indexOf('=')
        if (idx < 0) null else it.substring(0, idx) to java.net.URLDecoder.decode(it.substring(idx + 1), "UTF-8")
    }
    .toMap()

private fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

private fun parseSubAndEmail(idToken: String?): Pair<String, String> {
    if (idToken == null) throw OAuthFailed("no id_token in response")
    val parts = idToken.split(".")
    if (parts.size < 2) throw OAuthFailed("malformed id_token")
    val payloadJson = String(Base64.getUrlDecoder().decode(parts[1].padBase64()))
    val obj = Json.parseToJsonElement(payloadJson).jsonObject
    val sub = obj["sub"]?.jsonPrimitive?.content
        ?: throw OAuthFailed("id_token missing sub")
    val email = obj["email"]?.jsonPrimitive?.content
        ?: throw OAuthFailed("id_token missing email — request the email scope")
    return sub to email
}

private fun String.padBase64(): String {
    val rem = length % 4
    return if (rem == 0) this else this + "=".repeat(4 - rem)
}
```

### Step 4.5 — Run test, expect pass

Run: `./gradlew :shared:auth:jvmTest --tests OAuthClientTest`
Expected: 1 test passes.

### Step 4.6 — Commit

```
git add shared/auth/src/jvmMain/kotlin/com/sketchbook/auth/OAuthClient.kt \
        shared/auth/src/jvmTest/kotlin/com/sketchbook/auth/OAuthClientTest.kt
git commit -m "feat(auth): OAuthClient with loopback PKCE flow"
```

---

## Task 5 — `GoogleAuthSession` impl + state-machine tests

**Files:**
- Create: `shared/auth/src/jvmMain/kotlin/com/sketchbook/auth/GoogleAuthSession.kt`
- Create: `shared/auth/src/jvmTest/kotlin/com/sketchbook/auth/GoogleAuthSessionTest.kt`
- Create: `shared/auth/src/jvmTest/kotlin/com/sketchbook/auth/FakeTokenStore.kt`

### Step 5.1 — Add a fake `TokenStore` for tests

`shared/auth/src/jvmTest/kotlin/com/sketchbook/auth/FakeTokenStore.kt`:

```kotlin
package com.sketchbook.auth

class FakeTokenStore(initial: String? = null) : TokenStore {
    @Volatile private var token: String? = initial
    val writes = mutableListOf<String>()
    val clears = java.util.concurrent.atomic.AtomicInteger(0)

    override suspend fun read(): String? = token

    override suspend fun write(refreshToken: String) {
        token = refreshToken
        writes += refreshToken
    }

    override suspend fun clear() {
        token = null
        clears.incrementAndGet()
    }
}
```

### Step 5.2 — Write failing tests

`shared/auth/src/jvmTest/kotlin/com/sketchbook/auth/GoogleAuthSessionTest.kt`:

```kotlin
package com.sketchbook.auth

import com.sketchbook.core.UserId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoogleAuthSessionTest {

    @Test
    fun `init with no token emits SignedOut`() = runTest {
        val session = GoogleAuthSession(
            tokenStore = FakeTokenStore(initial = null),
            oauthClient = stubClient(),
            email = "alice@example.com",
            userId = UserId("11223344"),
        )
        assertTrue(session.state.value is AuthState.SignedOut)
    }

    @Test
    fun `signIn flows to SignedIn and persists refresh token`() = runTest {
        val store = FakeTokenStore()
        val client = stubClient()
        val session = GoogleAuthSession(
            tokenStore = store,
            oauthClient = client,
            email = "alice@example.com",
            userId = UserId("11223344"),
        )

        val result = session.signIn().getOrThrow()
        assertEquals("11223344", result.userId.value)
        assertEquals("alice@example.com", result.email)
        assertEquals(listOf("rt-FRESH"), store.writes)
        assertTrue(session.state.value is AuthState.SignedIn)
    }

    @Test
    fun `signOut clears state and token`() = runTest {
        val store = FakeTokenStore()
        val session = GoogleAuthSession(
            tokenStore = store,
            oauthClient = stubClient(),
            email = "alice@example.com",
            userId = UserId("11223344"),
        )
        session.signIn().getOrThrow()
        session.signOut()
        assertTrue(session.state.value is AuthState.SignedOut)
        assertEquals(1, store.clears.get())
    }

    @Test
    fun `refresh failure flips state to SignedOut and throws AuthSessionExpired`() = runTest {
        val store = FakeTokenStore(initial = "rt-EXPIRED")
        val session = GoogleAuthSession(
            tokenStore = store,
            oauthClient = stubClient(refreshOutcome = OAuthClient.RefreshResult.Invalid("invalid_grant")),
            email = "alice@example.com",
            userId = UserId("11223344"),
        )
        // Even with an initial refresh token, init does NOT auto-refresh — that happens on first
        // accessToken() call. So this is a SignedOut → first-accessToken-fail scenario after a
        // forced restore: simulate it by signing in then poisoning the next refresh.
        session.signIn().getOrThrow()
        // Now set the next refresh to fail.
        (session.oauthClient as StubOAuthClient).refreshOutcome = OAuthClient.RefreshResult.Invalid("invalid_grant")
        // Force a refresh by manipulating cached expiry.
        session.expireForTest()
        assertFailsWith<AuthSessionExpired> { session.accessToken() }
        assertTrue(session.state.value is AuthState.SignedOut)
    }
}

/** Minimal stub for OAuthClient that doesn't talk to a network. */
private class StubOAuthClient(
    var signInOutcome: OAuthTokens = OAuthTokens(
        accessToken = "at-FAKE",
        refreshToken = "rt-FRESH",
        expiresInSeconds = 3600,
        userId = UserId("11223344"),
        email = "alice@example.com",
    ),
    var refreshOutcome: OAuthClient.RefreshResult = OAuthClient.RefreshResult.Ok("at-REFRESHED", 3600),
) {
    fun signIn(): Result<OAuthTokens> = Result.success(signInOutcome)
    fun refresh(@Suppress("UNUSED_PARAMETER") rt: String): OAuthClient.RefreshResult = refreshOutcome
}

private fun stubClient(
    signInOutcome: OAuthTokens = OAuthTokens(
        accessToken = "at-FAKE",
        refreshToken = "rt-FRESH",
        expiresInSeconds = 3600,
        userId = UserId("11223344"),
        email = "alice@example.com",
    ),
    refreshOutcome: OAuthClient.RefreshResult = OAuthClient.RefreshResult.Ok("at-REFRESHED", 3600),
): StubOAuthClient = StubOAuthClient(signInOutcome, refreshOutcome)
```

> **Note for executor:** `GoogleAuthSession` should accept a small interface
> that both `OAuthClient` and `StubOAuthClient` can satisfy. Define
> `interface OAuthFlow { suspend fun signIn(): Result<OAuthTokens>; suspend fun refresh(rt: String): OAuthClient.RefreshResult }`
> in `OAuthClient.kt` and have `OAuthClient` implement it. Then change
> `StubOAuthClient` in the test to also implement it. Adjust the test to
> reference the field as `OAuthFlow` rather than `StubOAuthClient` for the
> refresh-poison case — the test currently casts to make this clear; use
> whatever type makes the cast vanish.

### Step 5.3 — Run tests, expect compile failure

Run: `./gradlew :shared:auth:jvmTest --tests GoogleAuthSessionTest`
Expected: compile failure.

### Step 5.4 — Implement `GoogleAuthSession`

`shared/auth/src/jvmMain/kotlin/com/sketchbook/auth/GoogleAuthSession.kt`:

```kotlin
package com.sketchbook.auth

import com.sketchbook.core.UserId
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.http.Parameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Production [AuthSession] backed by [OAuthFlow] (real impl: [OAuthClient]) + [TokenStore].
 *
 * On construction, schedules a background restore: if the keystore holds a refresh token, mint
 * an access token and emit [AuthState.SignedIn]. Failures during restore are silent (treated
 * as `SignedOut`) — the user can re-sign-in.
 */
class GoogleAuthSession(
    private val tokenStore: TokenStore,
    val oauthClient: OAuthFlow,
    private val httpClient: HttpClient = HttpClient(),
    private val clock: Clock = Clock.System,
    private val scope: CoroutineScope? = null,
    /** Email used for display when restoring from a refresh token (we don't re-fetch userinfo). */
    email: String? = null,
    userId: UserId? = null,
) : AuthSession {

    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var cachedAccessToken: String? = null
    private var cachedAccessTokenExpiry: Instant = Instant.DISTANT_PAST
    private var cachedRefreshToken: String? = null

    init {
        // Test seam: when both fields are pre-supplied, hold them so signIn() is a no-network op
        // (the stub OAuthFlow returns these). Production passes neither and lets signIn() set them.
        if (email != null && userId != null) {
            // Mark as SignedOut anyway — signIn() must actually run to populate refresh token.
        }
        // Background restore from keystore if scope is provided. In tests we usually skip this
        // by passing scope = null and exercising signIn() explicitly.
        scope?.launch { tryRestore() }
    }

    override suspend fun signIn(): Result<AuthState.SignedIn> = mutex.withLock {
        val outcome = oauthClient.signIn()
        outcome.map { tokens ->
            tokenStore.write(tokens.refreshToken)
            cachedRefreshToken = tokens.refreshToken
            cachedAccessToken = tokens.accessToken
            cachedAccessTokenExpiry = clock.now().plus(tokens.expiresInSeconds.seconds).minus(60.seconds)
            val signedIn = AuthState.SignedIn(userId = tokens.userId, email = tokens.email)
            _state.value = signedIn
            signedIn
        }
    }

    override suspend fun signOut() = mutex.withLock {
        val rt = cachedRefreshToken
        if (rt != null) {
            // Best-effort revoke. Never fail the call on revoke errors.
            runCatching {
                withContext(Dispatchers.IO) {
                    httpClient.submitForm(
                        url = "https://oauth2.googleapis.com/revoke",
                        formParameters = Parameters.build { append("token", rt) },
                    )
                }
            }
        }
        tokenStore.clear()
        cachedAccessToken = null
        cachedRefreshToken = null
        cachedAccessTokenExpiry = Instant.DISTANT_PAST
        _state.value = AuthState.SignedOut
    }

    override suspend fun accessToken(): String = mutex.withLock {
        val now = clock.now()
        val cached = cachedAccessToken
        if (cached != null && now < cachedAccessTokenExpiry) {
            return@withLock cached
        }
        val rt = cachedRefreshToken ?: tokenStore.read()
            ?: run {
                _state.value = AuthState.SignedOut
                throw AuthSessionExpired()
            }
        when (val r = oauthClient.refresh(rt)) {
            is OAuthClient.RefreshResult.Ok -> {
                cachedAccessToken = r.accessToken
                cachedAccessTokenExpiry = clock.now().plus(r.expiresInSeconds.seconds).minus(60.seconds)
                cachedRefreshToken = rt
                r.accessToken
            }
            is OAuthClient.RefreshResult.Invalid -> {
                tokenStore.clear()
                cachedRefreshToken = null
                cachedAccessToken = null
                _state.value = AuthState.SignedOut
                throw AuthSessionExpired()
            }
        }
    }

    /** Test helper — drops the cached access token so the next [accessToken] call refreshes. */
    internal fun expireForTest() {
        cachedAccessTokenExpiry = Instant.DISTANT_PAST
        cachedAccessToken = null
    }

    private suspend fun tryRestore() {
        val rt = tokenStore.read() ?: return
        when (val r = oauthClient.refresh(rt)) {
            is OAuthClient.RefreshResult.Ok -> {
                cachedRefreshToken = rt
                cachedAccessToken = r.accessToken
                cachedAccessTokenExpiry = clock.now().plus(r.expiresInSeconds.seconds).minus(60.seconds)
                // We don't re-fetch userinfo — the caller (DesktopAppGraph) provides the cached
                // identity. If we don't have one, leave SignedOut and let the user re-sign-in.
                // (See `email`/`userId` constructor params.)
            }
            is OAuthClient.RefreshResult.Invalid -> {
                tokenStore.clear()
            }
        }
    }
}

/** Decoupling seam so tests don't need to spin up a loopback HTTP server. */
interface OAuthFlow {
    suspend fun signIn(): Result<OAuthTokens>
    suspend fun refresh(refreshToken: String): OAuthClient.RefreshResult
}
```

Now make `OAuthClient` implement `OAuthFlow`:

In `OAuthClient.kt` change `class OAuthClient(...)` to
`class OAuthClient(...) : OAuthFlow`. The methods already match.

And update `StubOAuthClient` in the test to `: OAuthFlow`. Drop the cast in
the refresh-poison test — declare `oauthClient` as `OAuthFlow` in the
session and access via `(session.oauthClient as StubOAuthClient).refreshOutcome = ...`.
This compiles because `oauthClient` is `val oauthClient: OAuthFlow` —
checked with `as`.

> **Important:** the restore-flow gap (no userinfo re-fetch) means a real
> first-launch from a stored refresh token won't currently produce a
> `SignedIn` state. We accept this v1 limitation: persist `email` + `sub`
> in `TokenStore` alongside the refresh token (or in a sibling
> `prefs.userNodeForPackage`) — see Task 7. For now, only the
> just-after-signIn path emits SignedIn.

### Step 5.5 — Run tests, expect pass

Run: `./gradlew :shared:auth:jvmTest`
Expected: all green.

### Step 5.6 — Commit

```
git add shared/auth/src
git commit -m "feat(auth): GoogleAuthSession state machine"
```

---

## Task 6 — `CloudCredentials` abstraction in `shared/cloud`

**Files:**
- Create: `shared/cloud/src/commonMain/kotlin/com/sketchbook/cloud/CloudCredentials.kt`
- Modify: `shared/cloud/src/jvmMain/kotlin/com/sketchbook/cloud/GcsAuth.kt:34-122`
- Modify: `shared/cloud/src/jvmMain/kotlin/com/sketchbook/cloud/DirectGcsBackend.kt:62-68`
- Modify: `shared/cloud/src/jvmTest/kotlin/com/sketchbook/cloud/DirectGcsBackendTest.kt` (rename `auth` field to `credentials` of `CloudCredentials` type)

### Step 6.1 — Add interface

`shared/cloud/src/commonMain/kotlin/com/sketchbook/cloud/CloudCredentials.kt`:

```kotlin
package com.sketchbook.cloud

/**
 * Provider-agnostic bearer-token source for [DirectGcsBackend]. v1 has two impls:
 *  - `GcsAuth` (jvmMain) — service-account JWT signer. Used in tests.
 *  - `OAuthCloudCredentials` (app-desktop) — wraps an `AuthSession` and threads its access
 *    token through. Used in production.
 */
fun interface CloudCredentials {
    suspend fun token(): String
}
```

### Step 6.2 — Make `GcsAuth` implement `CloudCredentials`

In `shared/cloud/src/jvmMain/kotlin/com/sketchbook/cloud/GcsAuth.kt`, change:
```kotlin
class GcsAuth(
    private val key: ServiceAccountKey,
    ...
) {
```
to:
```kotlin
class GcsAuth(
    private val key: ServiceAccountKey,
    ...
) : CloudCredentials {
```
The existing `suspend fun token(): String` already matches the interface —
no other changes needed.

### Step 6.3 — Update `DirectGcsBackend` constructor

In `shared/cloud/src/jvmMain/kotlin/com/sketchbook/cloud/DirectGcsBackend.kt`,
change:
```kotlin
class DirectGcsBackend(
    private val http: HttpClient,
    private val auth: GcsAuth,
    private val bucket: String,
    private val userId: UserId = UserId.DEFAULT,
    private val json: Json = ManifestJson,
) : CloudBackend {
```
to:
```kotlin
class DirectGcsBackend(
    private val http: HttpClient,
    private val credentials: CloudCredentials,
    private val bucket: String,
    private val userId: UserId,
    private val json: Json = ManifestJson,
) : CloudBackend {
```
Also drop the `UserId.DEFAULT` default — caller MUST provide a real UserId.

Find every `auth.token()` reference in this file and rename to
`credentials.token()`. (The `authHeader()` private extension function
should be renamed similarly — search for `auth.token()` first; it's the
only meaningful site.)

### Step 6.4 — Update DirectGcsBackend prefix

The current code prefixes keys with `<userId.value>/`. Per the design's
IAM expression, they should now be `users/<userId.value>/`. Locate the
`blobPath`, `objectUrl`, and any other path-building helpers in
`DirectGcsBackend.kt` and prefix them with `users/` once. Add a private
constant:
```kotlin
private val tenantPrefix = "users/${userId.value}"
```
and use `"$tenantPrefix/blobs/<hash>"` etc. Confirm by grep that there is
no remaining bare `userId.value` concatenation outside this constant.

### Step 6.5 — Update tests

Open `shared/cloud/src/jvmTest/kotlin/com/sketchbook/cloud/DirectGcsBackendTest.kt`
and:
- Rename the `GcsAuth` parameter to `CloudCredentials` (its type is now
  abstract; `GcsAuth` still works as the test impl).
- Update any path assertions to expect `users/<sub>/...` instead of
  `<sub>/...`.
- Update any place that constructs `DirectGcsBackend(...)` without
  specifying `userId` — pass an explicit `UserId("test")` everywhere.

Also fix `app-desktop/src/jvmTest/kotlin/com/sketchbook/desktop/repo/GcsSyncQueueDrainTest.kt:93`
where `ownerUserId = UserId.DEFAULT` is used — leave as-is for now; the
SnapshotPipeline default still resolves to `UserId.DEFAULT` and the test
just round-trips that value. We'll revisit when SwappableSyncQueue is
overhauled.

### Step 6.6 — Verify

Run: `./gradlew :shared:cloud:check :app-desktop:compileKotlinJvm`
Expected: green.

### Step 6.7 — Commit

```
git add shared/cloud
git commit -m "refactor(cloud): CloudCredentials interface + users/ tenant prefix"
```

---

## Task 7 — `OAuthCloudCredentials` (app-desktop) + persisted identity

**Files:**
- Create: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/auth/OAuthCloudCredentials.kt`
- Create: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/auth/PrefsIdentityStore.kt`
- Modify: `app-desktop/build.gradle.kts` — add `implementation(project(":shared:auth"))`

### Step 7.1 — Add module dep

In `app-desktop/build.gradle.kts`, find the JVM dependencies block and add:
```kotlin
implementation(project(":shared:auth"))
```

### Step 7.2 — Implement `OAuthCloudCredentials`

```kotlin
package com.sketchbook.desktop.auth

import com.sketchbook.auth.AuthSession
import com.sketchbook.cloud.CloudCredentials

class OAuthCloudCredentials(
    private val authSession: AuthSession,
) : CloudCredentials {
    override suspend fun token(): String = authSession.accessToken()
}
```

### Step 7.3 — Implement `PrefsIdentityStore`

The `GoogleAuthSession.tryRestore` flow needs `email` + `sub` to emit
SignedIn after a silent restore from the keychain. Persist them in
`java.util.prefs` alongside the refresh token (the refresh token itself
stays in the keychain).

```kotlin
package com.sketchbook.desktop.auth

import com.sketchbook.auth.AuthState
import com.sketchbook.core.UserId
import java.util.prefs.Preferences

class PrefsIdentityStore(private val node: Preferences) {

    fun load(): AuthState.SignedIn? {
        val sub = node.get(KEY_SUB, null) ?: return null
        val email = node.get(KEY_EMAIL, null) ?: return null
        return AuthState.SignedIn(userId = UserId(sub), email = email)
    }

    fun save(signedIn: AuthState.SignedIn) {
        node.put(KEY_SUB, signedIn.userId.value)
        node.put(KEY_EMAIL, signedIn.email)
        node.flush()
    }

    fun clear() {
        node.remove(KEY_SUB)
        node.remove(KEY_EMAIL)
        node.flush()
    }

    private companion object {
        const val KEY_SUB = "auth_sub_v1"
        const val KEY_EMAIL = "auth_email_v1"
    }
}
```

> **Note:** `GoogleAuthSession` doesn't directly call `PrefsIdentityStore`
> — it stays cleanly in `shared/auth`. Instead, the desktop layer (Task 8)
> wraps `GoogleAuthSession` in an outer adapter that persists/loads
> identity. See `DesktopAuthSession` in Task 8.

### Step 7.4 — Verify compile

Run: `./gradlew :app-desktop:compileKotlinJvm`
Expected: green.

### Step 7.5 — Commit

```
git add app-desktop/build.gradle.kts app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/auth
git commit -m "feat(desktop): OAuthCloudCredentials + identity prefs store"
```

---

## Task 8 — `DesktopAuthSession` (identity-aware wrapper) + provider in `DesktopAppGraph`

**Files:**
- Create: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/auth/DesktopAuthSession.kt`
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt`

### Step 8.1 — `DesktopAuthSession`

Wraps `GoogleAuthSession` and weaves `PrefsIdentityStore` in/out so silent
restore actually emits SignedIn:

```kotlin
package com.sketchbook.desktop.auth

import com.sketchbook.auth.AuthSession
import com.sketchbook.auth.AuthState
import com.sketchbook.auth.GoogleAuthSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DesktopAuthSession(
    private val inner: GoogleAuthSession,
    private val identityStore: PrefsIdentityStore,
    scope: CoroutineScope,
) : AuthSession {

    private val _state = MutableStateFlow<AuthState>(loadInitial())
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        scope.launch {
            inner.state.collectLatest { innerState ->
                when (innerState) {
                    is AuthState.SignedIn -> {
                        identityStore.save(innerState)
                        _state.value = innerState
                    }
                    AuthState.SignedOut -> {
                        identityStore.clear()
                        _state.value = AuthState.SignedOut
                    }
                }
            }
        }
        // If we have a persisted identity AND the inner session restores a refresh token, the
        // inner session will emit SignedIn shortly via its own scope-launched tryRestore. No
        // action needed here — we just expose the persisted identity as an optimistic initial
        // value so the UI doesn't flash "SignedOut" between launch and restore.
    }

    private fun loadInitial(): AuthState = identityStore.load() ?: AuthState.SignedOut

    override suspend fun signIn(): Result<AuthState.SignedIn> = inner.signIn().also { r ->
        r.getOrNull()?.let { identityStore.save(it) }
    }

    override suspend fun signOut() {
        inner.signOut()
        identityStore.clear()
    }

    override suspend fun accessToken(): String = inner.accessToken()
}
```

### Step 8.2 — Wire into `DesktopAppGraph`

Add new `@Provides` blocks in `DesktopAppGraph.kt`. The OAuth client ID
needs to live somewhere — for v1 a local constant in `DesktopAppGraph` is
fine (later move to a config file or env override).

Add at the bottom of the graph interface body (before the closing `}`):

```kotlin
@Provides
@SingleIn(AppScope::class)
fun provideTokenStore(): TokenStore = KeyringTokenStore(
    serviceName = "com.sketchbook.refresh",
    accountName = "default",
)

@Provides
@SingleIn(AppScope::class)
fun provideIdentityStore(): PrefsIdentityStore = PrefsIdentityStore(
    node = Preferences.userNodeForPackage(PrefsIdentityStore::class.java),
)

@Provides
@SingleIn(AppScope::class)
fun provideOAuthClient(): OAuthClient = OAuthClient(
    httpClient = HttpClient(CIO),
    clientId = OAUTH_CLIENT_ID,
    scopes = listOf(
        "openid",
        "email",
        "https://www.googleapis.com/auth/devstorage.read_write",
    ),
)

@Provides
@SingleIn(AppScope::class)
fun provideAuthSession(
    tokenStore: TokenStore,
    identityStore: PrefsIdentityStore,
    oauthClient: OAuthClient,
    appScope: CoroutineScope,
): AuthSession {
    val inner = GoogleAuthSession(
        tokenStore = tokenStore,
        oauthClient = oauthClient,
        scope = appScope,
    )
    return DesktopAuthSession(
        inner = inner,
        identityStore = identityStore,
        scope = appScope,
    )
}
```

Add the imports at the top:
```kotlin
import com.sketchbook.auth.AuthSession
import com.sketchbook.auth.GoogleAuthSession
import com.sketchbook.auth.KeyringTokenStore
import com.sketchbook.auth.OAuthClient
import com.sketchbook.auth.TokenStore
import com.sketchbook.desktop.auth.DesktopAuthSession
import com.sketchbook.desktop.auth.PrefsIdentityStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
```

Add the OAuth client ID constant just below the graph interface:
```kotlin
/**
 * OAuth 2.0 desktop client ID for Sketchbook. Public clients have no secret — PKCE proves
 * the client. Created in the Google Cloud console under "OAuth 2.0 Client IDs" → Application
 * type "Desktop app". If you fork this repo, replace this with your own client ID.
 */
private const val OAUTH_CLIENT_ID = "REPLACE_ME.apps.googleusercontent.com"
```

Also expose the auth session as a graph accessor on the interface so
Main.kt can read it:
```kotlin
val authSession: AuthSession
```
(add this near the other accessors at the top of the interface body).

### Step 8.3 — Verify

Run: `./gradlew :app-desktop:compileKotlinJvm`
Expected: green. (Tests come in later tasks once all the pieces are in.)

### Step 8.4 — Commit

```
git add app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/auth/DesktopAuthSession.kt \
        app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt
git commit -m "feat(desktop): wire AuthSession into DesktopAppGraph"
```

---

## Task 9 — Pivot `SwappableSyncQueue` from `cloudReady` to `AuthState`

**Files:**
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/repo/SwappableSyncQueue.kt`
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt` (constructor wiring)

### Step 9.1 — Change `SwappableSyncQueue` constructor

Replace `private val settings: SettingsRepository` with two new params:
```kotlin
class SwappableSyncQueue(
    private val authSession: AuthSession,
    private val settings: SettingsRepository,   // still needed for bucket
    private val projects: ProjectRepository,
    ...
)
```

### Step 9.2 — Replace the `init { settings.observe()... }` block

```kotlin
init {
    scope.launch {
        // Re-build the queue when EITHER auth state OR bucket changes.
        kotlinx.coroutines.flow.combine(
            authSession.state,
            settings.observe().map { it.cloudBucket }.distinctUntilChanged(),
        ) { auth, bucket -> auth to bucket }
            .distinctUntilChanged()
            .collect { (auth, bucket) ->
                val previous = delegate.value
                val next = if (auth is AuthState.SignedIn && !bucket.isNullOrBlank()) {
                    buildGcsQueue(authSession = authSession, userId = auth.userId, bucket = bucket)
                } else {
                    currentMaterializer = null
                    _currentCloud.value = null
                    fallback
                }
                if (previous !== next) {
                    (previous as? GcsSyncQueue)?.stop()
                }
                delegate.value = next
                (next as? GcsSyncQueue)?.start()
            }
    }
}
```

### Step 9.3 — Replace `buildGcsQueue` body

Replace the JSON-decoding block with OAuth-credentials wiring:

```kotlin
private fun buildGcsQueue(
    authSession: AuthSession,
    userId: UserId,
    bucket: String,
): SyncQueue = runCatching {
    val credentials = OAuthCloudCredentials(authSession)
    val backend = DirectGcsBackend(
        http = httpClient,
        credentials = credentials,
        bucket = bucket,
        userId = userId,
    )
    val pipeline = SnapshotPipeline(
        cloud = backend,
        ownerUserId = userId,
        hostId = hostId,
        hostName = hostName,
    )
    val cacheSettings: () -> BlobCacheSettings = {
        kotlinx.coroutines.runBlocking { settings.observe().first() }.cacheSettings
    }
    val blobCache = JvmBlobCache(
        catalog = catalog,
        cacheRoot = blobCacheRoot,
        cloud = backend,
        cacheSettings = cacheSettings,
    )
    _currentCloud.value = backend
    currentMaterializer = ManifestMaterializer(
        cloud = backend,
        blobCache = blobCache,
        projectRoot = { uuid ->
            val pid = syncStateStore.projectIdFor(uuid)
                ?: throw IllegalStateException("no local project for uuid $uuid")
            val row = kotlinx.coroutines.runBlocking {
                projects.observeProject(pid).first()
            } ?: throw IllegalStateException("project row $pid not found")
            val parent = Paths.get(row.path.value).parent
                ?: throw IllegalStateException("project path has no parent: ${row.path.value}")
            parent
        },
    )
    GcsSyncQueue(
        cloud = backend,
        pipeline = pipeline,
        syncState = syncStateStore,
        projects = projects,
        scope = scope,
        journal = journal,
    )
}.getOrElse {
    currentMaterializer = null
    _currentCloud.value = null
    fallback
}
```

Drop the `import com.sketchbook.cloud.GcsAuth`, `ServiceAccountKey`
imports. Drop the `json` parameter from the constructor (no longer used).
Add imports:
```kotlin
import com.sketchbook.auth.AuthSession
import com.sketchbook.auth.AuthState
import com.sketchbook.desktop.auth.OAuthCloudCredentials
import kotlinx.coroutines.flow.combine
```

### Step 9.4 — Update `DesktopAppGraph.provideSyncQueue`

```kotlin
@Provides
@SingleIn(AppScope::class)
fun provideSyncQueue(
    authSession: AuthSession,
    settings: SettingsRepository,
    projects: ProjectRepository,
    store: SyncStateStore,
    catalog: Catalog,
    journal: JournalRepository,
    scope: CoroutineScope,
): SyncQueue = SwappableSyncQueue(
    authSession = authSession,
    settings = settings,
    projects = projects,
    syncStateStore = store,
    catalog = catalog,
    blobCacheRoot = catalogDbPath().parent.resolve("blob-cache"),
    scope = scope,
    hostId = hostIdentity().id,
    hostName = hostIdentity().name,
    journal = journal,
)
```

### Step 9.5 — Verify

Run: `./gradlew :app-desktop:compileKotlinJvm`
Expected: green. Tests in `app-desktop:jvmTest` will likely fail because
`SwappableSyncQueue` constructor changed — fix call sites in test files
(`GcsSyncQueueDrainTest`, `LeasedLockRepositoryTest` if any) to pass a
fake `AuthSession`. Add a small fake:

```kotlin
// In a test util file:
class FakeAuthSession(
    initial: AuthState = AuthState.SignedIn(UserId("test"), "test@example.com"),
) : AuthSession {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(initial)
    override val state get() = _state
    override suspend fun signIn() = error("not used")
    override suspend fun signOut() { _state.value = AuthState.SignedOut }
    override suspend fun accessToken(): String = "fake-access-token"
}
```

### Step 9.6 — Commit

```
git add app-desktop/src
git commit -m "refactor(sync): pivot SwappableSyncQueue on AuthState"
```

---

## Task 10 — Strip the JSON path from `SettingsRepository`

**Files:**
- Modify: `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/SettingsRepository.kt`
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/repo/PreferencesSettingsRepository.kt`
- Modify: `shared/feature-settings/src/commonMain/kotlin/com/sketchbook/featuresettings/SettingsViewModel.kt`
- Modify: `shared/feature-settings/src/commonMain/kotlin/com/sketchbook/featuresettings/SettingsScreen.kt`
- Modify: `shared/feature-settings/src/commonTest/kotlin/com/sketchbook/featuresettings/SettingsViewModelTest.kt`

### Step 10.1 — `SettingsRepository.kt`

Drop `setCloudCredential`. Drop `cloudCredentialJson` and `cloudConfigured`
from `Settings` data class. Update `cloudReady`:

```kotlin
interface SettingsRepository {
    fun observe(): Flow<Settings>
    suspend fun upsertRoot(root: LibraryRoot): Result<Unit>
    suspend fun removeRoot(root: LibraryRoot): Result<Unit>
    suspend fun setCloudBucket(bucket: String?): Result<Unit>
    suspend fun setSelfContained(uuid: ProjectUuid, value: Boolean): Result<Unit>
    suspend fun setCacheSettings(settings: BlobCacheSettings): Result<Unit>
}

data class Settings(
    val libraryRoots: List<LibraryRoot>,
    val selfContainedProjects: Set<ProjectUuid>,
    val cacheSettings: BlobCacheSettings = BlobCacheSettings.Default,
    val cloudBucket: String? = null,
)
```

### Step 10.2 — `PreferencesSettingsRepository.kt`

- Delete `setCloudCredential` and `KEY_CLOUD_CREDENTIAL`.
- In `read()`, drop the `cred` line and don't set `cloudCredentialJson` /
  `cloudConfigured`.
- On startup, opportunistically remove the legacy
  `KEY_CLOUD_CREDENTIAL` from the prefs node so old service-account JSON
  doesn't sit at rest indefinitely:
  ```kotlin
  init {
      runCatching { node.remove("cloud_credential_json") ; node.flush() }
  }
  ```

### Step 10.3 — `SettingsViewModel.kt`

- Drop `Intent.SetCloudCredential`.
- Drop `cloudConfigured` and `cloudReady` from the State.
  `cloudBucket` stays.
- Drop the `is Intent.SetCloudCredential -> ...` branch in `dispatch`.

### Step 10.4 — `SettingsScreen.kt`

The full Advanced/cloud disclosure goes — it's been replaced by the
"Cloud" section coming in Task 12. For this task, just delete the
disclosure (`if (showCloud) { ... }` block plus `var showCloud` plus the
"Show cloud sync" toggle button) and the `onUploadCredentialClicked`
parameter.

Update the call site in `RootContent.kt` (or wherever `SettingsScreen` is
instantiated) to drop the `onUploadCredentialClicked` argument.

### Step 10.5 — `SettingsViewModelTest.kt`

Remove any test cases that exercise `SetCloudCredential`. Update assertions
referring to `cloudConfigured` / `cloudReady` / `cloudCredentialJson`.

### Step 10.6 — Verify

Run: `./gradlew :app-desktop:compileKotlinJvm :shared:feature-settings:check`
Expected: green.

### Step 10.7 — Commit

```
git add shared/repository shared/feature-settings app-desktop
git commit -m "refactor(settings): remove service-account JSON path"
```

---

## Task 11 — `UserScope` as `@GraphExtension`

**Files:**
- Create: `shared/core/src/commonMain/kotlin/com/sketchbook/core/UserScope.kt`
- Create: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/UserGraph.kt`
- Create: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/UserGraphHolder.kt`
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt`
- Modify: `docs/architecture/dependency-injection.md`

### Step 11.1 — `UserScope` marker

`shared/core/src/commonMain/kotlin/com/sketchbook/core/UserScope.kt`:

```kotlin
package com.sketchbook.core

/**
 * Per-signed-in-user scope. Built on `AuthState.SignedIn`, torn down on `SignedOut`. Anything
 * that requires an authenticated user (CloudBackend, sync queue, lock repository) lives here.
 *
 * See `docs/architecture/dependency-injection.md` §1 for the wider scope hierarchy.
 */
abstract class UserScope private constructor()
```

### Step 11.2 — `UserGraph` extension

`app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/UserGraph.kt`:

```kotlin
package com.sketchbook.desktop

import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.DirectGcsBackend
import com.sketchbook.cloud.CloudCredentials
import com.sketchbook.core.AppScope
import com.sketchbook.core.UserId
import com.sketchbook.core.UserScope
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient

/**
 * Per-user subgraph. Lifetime: `AuthState.SignedIn` ↔ `SignedOut`. Built by
 * [UserGraphHolder.rebuildOnAuthChange]. `UserScope` lifetime services contributed via
 * `@SingleIn(UserScope::class)` belong here. Today only the cloud backend lives in this
 * graph; the SwappableSyncQueue stays in AppScope as the public-facing accessor and reads
 * from this graph indirectly via the auth state pivot.
 */
@GraphExtension(scope = UserScope::class, parent = AppScope::class)
interface UserGraph {

    val cloudBackend: CloudBackend

    @Provides
    @SingleIn(UserScope::class)
    fun provideCloudBackend(
        http: HttpClient,
        credentials: CloudCredentials,
        bucket: String,
        userId: UserId,
    ): CloudBackend = DirectGcsBackend(
        http = http,
        credentials = credentials,
        bucket = bucket,
        userId = userId,
    )

    interface Factory {
        fun create(
            @Provides @SingleIn(UserScope::class) userId: UserId,
            @Provides @SingleIn(UserScope::class) bucket: String,
            @Provides @SingleIn(UserScope::class) credentials: CloudCredentials,
            @Provides @SingleIn(UserScope::class) http: HttpClient,
        ): UserGraph
    }
}
```

> **Note on Metro `@GraphExtension`:** the `Factory` interface with
> `@Provides`-annotated parameters is how Metro models a runtime-parameterized
> subgraph (per the Metro docs). If the version of Metro in this repo
> (`0.7.x`) uses a slightly different syntax, follow Metro's docs at
> <https://github.com/ZacSweers/metro> §GraphExtension. Functionally we
> need: a subgraph that takes `userId`, `bucket`, `credentials`, and an
> `HttpClient` at construction and exposes a `CloudBackend`. Adjust the
> shape if needed but keep that contract.

### Step 11.3 — `UserGraphHolder`

`app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/UserGraphHolder.kt`:

```kotlin
package com.sketchbook.desktop

import com.sketchbook.auth.AuthSession
import com.sketchbook.auth.AuthState
import com.sketchbook.cloud.CloudCredentials
import com.sketchbook.desktop.auth.OAuthCloudCredentials
import com.sketchbook.repo.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@SingleIn(com.sketchbook.core.AppScope::class)
@Inject
class UserGraphHolder(
    private val authSession: AuthSession,
    private val settings: SettingsRepository,
    private val factory: UserGraph.Factory,
    private val scope: CoroutineScope,
) {

    private val _userGraph = MutableStateFlow<UserGraph?>(null)
    val userGraph: StateFlow<UserGraph?> = _userGraph

    private val httpClient: HttpClient = HttpClient(CIO)

    init {
        scope.launch {
            combine(
                authSession.state,
                settings.observe().map { it.cloudBucket }.distinctUntilChanged(),
            ) { auth, bucket -> auth to bucket }
                .collect { (auth, bucket) ->
                    val next = if (auth is AuthState.SignedIn && !bucket.isNullOrBlank()) {
                        factory.create(
                            userId = auth.userId,
                            bucket = bucket,
                            credentials = OAuthCloudCredentials(authSession),
                            http = httpClient,
                        )
                    } else {
                        null
                    }
                    _userGraph.value = next
                }
        }
    }
}
```

### Step 11.4 — Wire `UserGraph.Factory` into `DesktopAppGraph`

Make `DesktopAppGraph` extend the factory so Metro generates an impl:

```kotlin
@DependencyGraph(scope = AppScope::class)
interface DesktopAppGraph : ViewModelGraph, UserGraph.Factory {
    ...
    val userGraphHolder: UserGraphHolder
    ...
}
```

(If Metro's syntax for a graph-extension-factory accessor differs, follow
Metro docs; this is the standard pattern.)

### Step 11.5 — Update DI doc

In `docs/architecture/dependency-injection.md` §1 "Scopes", change the
prose from "There is exactly one application scope" to:
```
There are two scopes:
- `com.sketchbook.core.AppScope` — application lifetime, present on every shell.
- `com.sketchbook.core.UserScope` — signed-in-user lifetime. Built by `UserGraphHolder`
  on `AuthState.SignedIn` and torn down on `SignedOut`. Modelled as a Metro
  `@GraphExtension(parent = AppScope::class)`. See §1.1.
```
Add §1.1 with a one-paragraph description of UserScope.

### Step 11.6 — Verify

Run: `./gradlew :app-desktop:compileKotlinJvm`
Expected: green. Metro's annotation processor will tell you if the
`@GraphExtension` shape isn't quite right; iterate per its error messages.

> **If Metro 0.7.x doesn't yet support `@GraphExtension` factories the way
> Step 11.2 sketches** (this is a real possibility — check before
> implementing): fall back to a manual factory. Implement `UserGraph` as a
> plain class (not a `@GraphExtension`), have `UserGraphHolder` instantiate
> it directly, and document in the DI doc that the "subgraph" is hand-rolled
> for now. The point of this task is the *scoping* of cloud-backend
> ownership, not the specific Metro feature; if the feature isn't there
> yet, deliver the same isolation with hand-wiring and move on.

### Step 11.7 — Commit

```
git add shared/core app-desktop docs/architecture/dependency-injection.md
git commit -m "feat(di): UserScope graph extension + UserGraphHolder"
```

---

## Task 12 — Settings UI: Cloud section + Sign in / Sign out

**Files:**
- Modify: `shared/feature-settings/src/commonMain/kotlin/com/sketchbook/featuresettings/SettingsScreen.kt`
- Modify: `shared/feature-settings/src/commonMain/kotlin/com/sketchbook/featuresettings/SettingsViewModel.kt`
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt` (call site)

### Step 12.1 — Inject `AuthSession` into `SettingsViewModel`

```kotlin
@ContributesIntoMap(AppScope::class)
@ViewModelKey
@Inject
class SettingsViewModel(
    private val repository: SettingsRepository,
    private val authSession: AuthSession,
) : ViewModel() {
    ...
}
```

Add to `State`:
```kotlin
val auth: AuthState = AuthState.SignedOut,
```

In the `state: StateFlow<State>` builder, combine `repository.observe()`
with `authSession.state`:
```kotlin
val state: StateFlow<State> = kotlinx.coroutines.flow.combine(
    repository.observe(),
    authSession.state,
) { settings, auth ->
    State(
        libraryRoots = settings.libraryRoots,
        cloudBucket = settings.cloudBucket,
        auth = auth,
        selfContainedProjects = settings.selfContainedProjects,
        cacheSettings = settings.cacheSettings,
        loading = false,
    )
}.stateIn(viewModelScope, SharingStarted.Eagerly, State(loading = true))
```

Add intents:
```kotlin
data object SignIn : Intent
data object SignOut : Intent
```

In `dispatch`:
```kotlin
is Intent.SignIn -> viewModelScope.launch {
    val r = authSession.signIn()
    if (r.isFailure) {
        _effects.tryEmit(Effect.Failed("auth", r.exceptionOrNull()?.message ?: "sign-in failed"))
    } else {
        _effects.tryEmit(Effect.Saved("auth"))
    }
}
is Intent.SignOut -> viewModelScope.launch {
    authSession.signOut()
    _effects.tryEmit(Effect.Saved("auth"))
}
```

### Step 12.2 — Add a Cloud section in `SettingsScreen.kt`

Insert above "Local blob cache" section, layered onto existing components
(per `feedback_layer_dont_redesign`). Reuse `Section`, `Surface`, `Badge`,
`Button`, `Text`, `AppTheme.colors.tint*`. Do not introduce new colors:

```kotlin
Section(
    title = "Cloud",
    hint = "Sign in with Google so Sketchbook can sync your projects.",
) {
    Surface(
        color = AppTheme.colors.tintBlue,
        elevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
            when (val auth = state.auth) {
                is AuthState.SignedIn -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                    ) {
                        Badge(color = AppTheme.colors.accentPositive) {
                            Text("signed in", style = AppTheme.typography.caption)
                        }
                        Text(auth.email, style = AppTheme.typography.body)
                    }
                    Button(
                        onClick = { vm.dispatch(SettingsViewModel.Intent.SignOut) },
                        variant = ButtonVariant.Ghost,
                    ) { Text("Sign out") }
                }
                AuthState.SignedOut -> {
                    Text(
                        "Not signed in. Cloud sync is disabled until you sign in.",
                        style = AppTheme.typography.body,
                    )
                    Button(
                        onClick = { vm.dispatch(SettingsViewModel.Intent.SignIn) },
                        variant = ButtonVariant.Primary,
                    ) { Text("Sign in with Google") }
                }
            }
            // Bucket field — same as before but moved here. Disabled when SignedOut so the user
            // sets things in a sensible order.
            var bucketDraft by remember(state.cloudBucket) { mutableStateOf(state.cloudBucket.orEmpty()) }
            com.sketchbook.uishared.components.TextField(
                value = bucketDraft,
                onChange = {
                    bucketDraft = it
                    vm.dispatch(SettingsViewModel.Intent.SetCloudBucket(it.takeIf { v -> v.isNotBlank() }))
                },
                placeholder = "Bucket name (e.g. sketchbook-prod)",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
```

In the existing "Cloud sync" status section (if present), prepend a hint
when SignedOut:
```kotlin
if (state.auth is AuthState.SignedOut) {
    Text(
        "Sign in to enable sync.",
        style = AppTheme.typography.caption,
    )
}
```

### Step 12.3 — Update call site

In `RootContent.kt`, the `SettingsScreen(...)` invocation no longer takes
`onUploadCredentialClicked`. Remove that param + any state in
`RootContent` related to a file picker for service-account JSON.

### Step 12.4 — Manual UI verification

Run: `./gradlew :app-desktop:run`
Expected: app launches. Open Settings → Cloud section shows "Sign in with
Google" button. Clicking it opens the system browser. After consent (you
need a real OAuth client ID — see Task 8), the section flips to
"Signed in as <your-email>".

> **If you do NOT have a real OAuth client ID yet:** stop after Task 12.3
> and proceed with Task 13. The end-to-end smoke test waits until the
> client ID is provisioned in Task 14.

### Step 12.5 — Commit

```
git add shared/feature-settings app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt
git commit -m "feat(settings): Cloud section with Sign in/out"
```

---

## Task 13 — `tools/grant-user.ps1`

**Files:**
- Create: `tools/grant-user.ps1`

### Step 13.1 — Script

```powershell
<#
.SYNOPSIS
  Grant a user IAM permission on the Sketchbook GCS bucket, scoped to their tenant prefix.

.DESCRIPTION
  Sketchbook uses a single shared bucket with per-user object-name prefixes (`users/<sub>/...`).
  This script adds an IAM Condition that lets the given Google identity read/write only their
  own prefix. Run this once per new signup, after the user has signed in at least once and you
  have their Google `sub` (numeric subject identifier from the ID token — surface it via the
  app's logs or read from the bucket's `users/<sub>/` prefix that appears on first sync).

.PARAMETER Email
  The user's Google account email address.

.PARAMETER Sub
  The user's Google `sub` claim — a numeric string.

.PARAMETER Bucket
  The GCS bucket name. Defaults to "sketchbook-prod".

.EXAMPLE
  ./tools/grant-user.ps1 -Email alice@example.com -Sub 117449212344556677889
#>
param(
  [Parameter(Mandatory = $true)] [string] $Email,
  [Parameter(Mandatory = $true)] [string] $Sub,
  [string] $Bucket = "sketchbook-prod"
)

$ErrorActionPreference = "Stop"

$expression = @"
resource.name.startsWith("projects/_/buckets/$Bucket/objects/users/$Sub/")
"@

# strip newlines for the IAM condition body
$expression = $expression -replace "`r?`n", " "

$conditionTitle = "tenant_$Sub"

gcloud storage buckets add-iam-policy-binding "gs://$Bucket" `
  --member="user:$Email" `
  --role="roles/storage.objectAdmin" `
  --condition="expression=$expression,title=$conditionTitle"

Write-Host "Granted $Email objectAdmin on gs://$Bucket scoped to users/$Sub/"
```

### Step 13.2 — Smoke

(Don't actually run unless you have a real second test account; otherwise
just `./tools/grant-user.ps1 -?` to confirm help renders.)

### Step 13.3 — Commit

```
git add tools/grant-user.ps1
git commit -m "tools: grant-user.ps1 for per-user IAM grant"
```

---

## Task 14 — End-to-end smoke + final cleanup

### Step 14.1 — Provision a real OAuth client ID

(One-time, manual.) In the Google Cloud console for the project that owns
`sketchbook-prod`:
1. APIs & Services → OAuth consent screen → configure if not already (External, scopes:
   `openid email https://www.googleapis.com/auth/devstorage.read_write`).
2. Credentials → Create Credentials → OAuth client ID → Application type:
   Desktop app → name "Sketchbook Desktop".
3. Copy the client ID (looks like `1234-abcd.apps.googleusercontent.com`).
4. Replace `OAUTH_CLIENT_ID = "REPLACE_ME..."` in `DesktopAppGraph.kt`.
5. Make sure your own Google account has been IAM-granted on
   `sketchbook-prod` (run `tools/grant-user.ps1` for yourself with your
   `sub` — find it via JWT decoder on the ID token after a sign-in
   attempt, or use `gcloud auth print-identity-token` decoded).

### Step 14.2 — Smoke

Run: `./gradlew :app-desktop:run`. In Settings → Cloud:
1. Click "Sign in with Google", complete consent in browser.
2. Confirm "Signed in as <you>" appears.
3. Set a bucket name (`sketchbook-prod`).
4. Force a snapshot push from a project (per existing app flow).
5. In `gcloud storage ls gs://sketchbook-prod/users/<your-sub>/`, confirm
   blobs/manifests appear.
6. Click "Sign out". Confirm Cloud section returns to "Not signed in" and
   the sync status hint says "Sign in to enable sync".
7. Re-launch the app. Confirm the Cloud section silently restores
   "Signed in as <you>" without browser prompt (refresh-token persistence
   working).

### Step 14.3 — Final build + detekt baselines

Run: `./gradlew check`
Expected: green. If detekt baselines need updating because of new files
(`detekt-baseline-*.xml`), regenerate per the project's existing process —
typically `./gradlew detektBaseline*`.

### Step 14.4 — Update top-level docs

- Delete or update `docs/runbooks/release.md` references to the
  service-account JSON workflow if any. Update `docs/runbooks/recover-local-catalog.md`
  similarly.
- Confirm `docs/plans/2026-05-06-oauth-user-scope-design.md` and this plan
  are committed.

### Step 14.5 — Commit any leftover

```
git add -A
git commit -m "chore: doc + baseline cleanup for oauth-user-scope"
```

---

## Task 15 — Open the PR

This is the final task. Push the branch and open one PR titled:

> feat: OAuth user auth + UserScope DI extension

PR body:

```
## Summary
- Replace "paste service-account JSON" with Google OAuth (loopback PKCE).
- New `shared/auth` KMP module: `AuthSession`, `OAuthClient`, `KeyringTokenStore`.
- New `UserScope` Metro `@GraphExtension` — `CloudBackend` + per-user services live here.
- `DirectGcsBackend` now accepts `CloudCredentials` (interface) and uses `users/<sub>/` prefix.
- Settings: Cloud section with Sign in / Sign out.
- `tools/grant-user.ps1` for manual per-user IAM grants.

Design: docs/plans/2026-05-06-oauth-user-scope-design.md
Plan:   docs/plans/2026-05-06-oauth-user-scope-plan.md

## Test plan
- [ ] `./gradlew check` passes
- [ ] `KeyringTokenStoreTest`, `OAuthClientTest`, `GoogleAuthSessionTest` pass
- [ ] Manual: sign in with Google → "Signed in as ..." appears
- [ ] Manual: push a snapshot → blob lands at `gs://sketchbook-prod/users/<sub>/blobs/...`
- [ ] Manual: sign out → "Sign in to enable sync"
- [ ] Manual: relaunch → silent restore to SignedIn
```

---

## Out of scope (do NOT implement here)

- Token-vending Cloud Function. Manual `gcloud` IAM grants are the v1
  story.
- Account switcher / multi-account UI.
- Cross-user shared blob pool.
- Quota / cost-cap enforcement.
- Refresh token rotation handling beyond what Google sends back.
- `SavedStateHandle` integration for Settings (existing limitation, see DI doc).
