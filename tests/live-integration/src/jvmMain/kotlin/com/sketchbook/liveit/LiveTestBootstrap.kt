package com.sketchbook.liveit

import com.sketchbook.auth.AuthSession
import com.sketchbook.auth.AuthState
import com.sketchbook.auth.OAuthFlow
import com.sketchbook.auth.OAuthTokens
import com.sketchbook.auth.TokenStore
import com.sketchbook.auth.firebase.FirebaseAuthSession
import com.sketchbook.auth.firebase.FirebaseConfig
import com.sketchbook.auth.firebase.FirebaseSdkBootstrap
import com.sketchbook.auth.firebase.IdentityToolkitClient
import com.sketchbook.auth.firebase.JwksGoogleIdTokenVerifier
import com.sketchbook.cloud.CloudBackend
import com.sketchbook.cloud.CloudCredentials
import com.sketchbook.cloud.FirebaseBlobStore
import com.sketchbook.cloud.metadata.DocPath
import com.sketchbook.cloud.metadata.FirestoreMetadataStore
import com.sketchbook.cloud.metadata.MachineDoc
import com.sketchbook.cloud.metadata.MetadataStore
import com.sketchbook.core.UserId
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

/**
 * Wires the same Firebase graph the desktop app uses, but against the live-integration
 * [FileTokenStore] and (for the login flow) the prod [com.sketchbook.auth.OAuthClient].
 *
 * Two entry points:
 *  - [bootstrapForLogin] — used only by [LiveTestLoginKt]. Includes a real [OAuthFlow] so
 *    the interactive sign-in browser flow works. Does NOT call `tryRestore` or
 *    `ensureInitialized` — login is the one path that legitimately runs before any tokens
 *    or SDK init exist.
 *  - [bootstrapForCloudOps] — used by push/pull/sweep. Reads the cached refresh token from
 *    [FileTokenStore], rehydrates the [FirebaseAuthSession], boots the gitlive SDK, and
 *    hands back a [LiveCloudGraph] ready to make Firestore + Storage RPCs. Passes a poison
 *    [OAuthFlow] so any code path that accidentally calls `signIn()` fails loudly instead
 *    of opening a browser mid-test.
 *
 * Always pair construction with [LiveCloudGraph.close] — the HttpClient owns CIO worker
 * threads that block JVM exit, and `FirebaseSdkBootstrap.clearSession()` tears down the
 * gitlive SDK so a subsequent run doesn't pick up stale `Firebase.auth.currentUser`.
 */
object LiveTestBootstrap {
    suspend fun bootstrapForLogin(): LoginGraph {
        val http = buildHttpClient()
        val config = LiveTestEnv.firebaseConfig()
        val oauth =
            com.sketchbook.auth.OAuthClient(
                httpClient = http,
                clientId = LiveTestEnv.oauthClientId(),
                clientSecret = LiveTestEnv.oauthClientSecret(),
                // Mirrors DesktopAppGraph.provideOAuthClient exactly — Firebase migration
                // dropped the GCS scope; we only need openid + email.
                scopes = listOf("openid", "email"),
            )
        return LoginGraph(
            http = http,
            config = config,
            authSession =
                FirebaseAuthSession(
                    tokenStore = FileTokenStore(),
                    oauthClient = oauth,
                    identityToolkit = IdentityToolkitClient(httpClient = http, webApiKey = config.webApiKey),
                    googleIdTokenVerifier =
                        JwksGoogleIdTokenVerifier(expectedAudience = LiveTestEnv.oauthClientId()),
                ),
        )
    }

    suspend fun bootstrapForCloudOps(): LiveCloudGraph {
        val http = buildHttpClient()
        val config = LiveTestEnv.firebaseConfig()
        val tokenStore: TokenStore = FileTokenStore()
        val authSession =
            FirebaseAuthSession(
                tokenStore = tokenStore,
                oauthClient = PoisonOAuthFlow,
                identityToolkit = IdentityToolkitClient(httpClient = http, webApiKey = config.webApiKey),
                googleIdTokenVerifier =
                    JwksGoogleIdTokenVerifier(expectedAudience = LiveTestEnv.oauthClientId()),
            )
        val restored = authSession.tryRestore()
        check(restored) {
            "No cached refresh token at ${LiveTestEnv.tokenCachePath} (or it expired). " +
                "Run `./gradlew :tests:live-integration:liveTestLogin` first."
        }
        // tryRestore flipped the inner state; the suspended `first { ... }` resolves immediately
        // off the StateFlow buffer.
        val signedIn = authSession.state.first { it is AuthState.SignedIn } as AuthState.SignedIn
        val userId = signedIn.userId

        // Diagnostic: confirm the Firebase ID token is a real JWT before seeding the SDK.
        val tokensForDiag = authSession.currentTokens()
        val jwtPayloadSub =
            runCatching {
                val parts = tokensForDiag.idToken.split(".")
                if (parts.size >= 2) {
                    val padding = (4 - parts[1].length % 4) % 4
                    val decoded =
                        String(
                            java.util.Base64
                                .getUrlDecoder()
                                .decode(parts[1] + "=".repeat(padding)),
                        )
                    // Extract sub, aud, iss from the JSON payload using simple regex
                    val sub = Regex(""""sub"\s*:\s*"([^"]+)"""").find(decoded)?.groupValues?.get(1) ?: "?"
                    val aud = Regex(""""aud"\s*:\s*"([^"]+)"""").find(decoded)?.groupValues?.get(1) ?: "?"
                    "sub=$sub aud=${aud.takeLast(25)}"
                } else {
                    "jwt-malformed"
                }
            }.getOrDefault("decode-failed")
        System.err.println(
            "[LiveTestBootstrap] pre-seed uid=${tokensForDiag.uid} " +
                "jwtClaims=[$jwtPayloadSub] " +
                "expiresAt=${tokensForDiag.expiresAt}",
        )

        val bootstrap = FirebaseSdkBootstrap(authSession = authSession, config = config)
        bootstrap.ensureInitialized()

        // Diagnostic: verify auth (Pattern A1) by writing a machine doc — machine rules use
        // only `isOwner(uid) && withinSizeLimit()`, no hasOnly check. If this fails → auth is
        // broken (storage seed or token issue). If this passes but lock writes fail → hasOnly
        // field shape mismatch in the lock rules.
        val metadataStore = FirestoreMetadataStore(ensureInitialized = bootstrap::ensureInitialized)
        val diagPath = DocPath.machine(userId.value, "live-diag-${Clock.System.now().epochSeconds}")
        try {
            metadataStore.setDoc(
                diagPath,
                MachineDoc(
                    hostName = "live-integration-diag",
                    os = System.getProperty("os.name") ?: "JVM",
                    lastSeenAt = Clock.System.now(),
                ),
                MachineDoc.serializer(),
            )
            println("[LiveTestBootstrap] auth diagnostic OK — machine doc write succeeded, auth is working")
            runCatching { metadataStore.deleteDoc(diagPath) }
        } catch (e: Exception) {
            error(
                "[LiveTestBootstrap] auth diagnostic FAILED (${e::class.simpleName}: ${e.message}). " +
                    "Pattern A1 (platform storage seeding) or token warm-up is broken — all Firestore " +
                    "operations will fail with PERMISSION_DENIED. Re-run liveTestLogin if the token is stale.",
            )
        }
        val cloud: CloudBackend =
            FirebaseBlobStore(
                http = http,
                credentials = LiveCloudCredentials(authSession),
                bucket = config.storageBucket,
                userId = userId,
            )
        return LiveCloudGraph(
            http = http,
            config = config,
            authSession = authSession,
            bootstrap = bootstrap,
            metadataStore = metadataStore,
            cloud = cloud,
            userId = userId,
            signedInEmail = signedIn.email,
        )
    }

    private fun buildHttpClient(): HttpClient =
        HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
            expectSuccess = false
        }

    // Mirrors DesktopAppGraph.provideHttpClient — keeps live-integration HTTP behaviour the
    // same as the desktop app's, so any timeout-shaped bug shows up in both.
    private const val CONNECT_TIMEOUT_MS = 5_000L
    private const val REQUEST_TIMEOUT_MS = 60_000L
    private const val SOCKET_TIMEOUT_MS = 30_000L
}

class LoginGraph(
    private val http: HttpClient,
    val config: FirebaseConfig,
    val authSession: FirebaseAuthSession,
) : AutoCloseable {
    override fun close() {
        http.close()
    }
}

class LiveCloudGraph(
    private val http: HttpClient,
    val config: FirebaseConfig,
    val authSession: FirebaseAuthSession,
    private val bootstrap: FirebaseSdkBootstrap,
    val metadataStore: MetadataStore,
    val cloud: CloudBackend,
    val userId: UserId,
    val signedInEmail: String,
) : AutoCloseable {
    suspend fun shutdown() {
        // Tear down the gitlive SDK so subsequent runs (or sweeps) start clean.
        runCatching { bootstrap.clearSession() }
        http.close()
    }

    override fun close() {
        // Synchronous close — usable from `use { }`. CIO HttpClient.close() is non-blocking
        // for callbacks but does cancel in-flight requests, which is the right behaviour at
        // the end of a script.
        http.close()
    }
}

/**
 * [CloudCredentials] adapter that reads a fresh Firebase ID token off the [AuthSession] on
 * every call. Mirrors `app-desktop`'s `FirebaseCloudCredentials` — inlined here so the
 * test module doesn't depend on `:app-desktop` (which would invert the dependency direction
 * and pull in Compose/etc.).
 */
private class LiveCloudCredentials(
    private val authSession: AuthSession,
) : CloudCredentials {
    override suspend fun token(): String = authSession.idToken()
}

/**
 * [OAuthFlow] that refuses to run. Wired into push/pull/sweep so a regression that calls
 * `signIn()` from cloud-ops code (which should never happen — we have a cached refresh
 * token by construction) fails immediately with a useful message instead of opening a
 * browser window during a CI-style run.
 */
private object PoisonOAuthFlow : OAuthFlow {
    override suspend fun signIn(): Result<OAuthTokens> =
        Result.failure(
            IllegalStateException(
                "Live-integration cloud-ops path tried to run an interactive OAuth flow. " +
                    "This means the cached refresh token was rejected and FirebaseAuthSession " +
                    "fell through to signIn(). Re-run `liveTestLogin`.",
            ),
        )
}
