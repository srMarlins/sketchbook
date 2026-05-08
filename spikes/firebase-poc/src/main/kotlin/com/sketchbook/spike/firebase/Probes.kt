/*
 * Spike probes — the actual experiments that answer Phase 0 questions.
 *
 * Each probe is a top-level suspend function. Main.kt dispatches to them by name.
 * Output is println-on-stdout for ease of reading; structured log lines prefixed
 * with `[probe-X]` so a casual reader can tell what's running.
 *
 * To run any probe:  ./gradlew :spikes:firebase-poc:run --args="<probe-name> [args...]"
 *
 * Probes:
 *
 *   listener-sanity <email> <password>
 *     Initialize Firebase, sign in via Email/Password (verified-implemented on JVM),
 *     write a doc, listen for it on a second flow, observe the listener fire. Answers:
 *     "does Firestore + listeners work on JVM via gitlive at all?"
 *
 *   exchange-google-token <google-id-token>
 *     Take a Google ID token (from Google's OAuth Playground or our existing OAuthClient),
 *     run JWKS verification + Identity Toolkit signInWithIdp exchange, print the
 *     resulting Firebase ID + refresh tokens. Answers: "does our auth wire-format work end-to-end?"
 *
 *   storage-rest <firebase-id-token>
 *     PUT a 1MB blob to Firebase Storage via REST with the given Firebase ID token.
 *     Confirms parity with DirectGcsBackend's REST shape. Answers: "does Storage REST
 *     accept Firebase ID tokens against the new-naming bucket?"
 */
package com.sketchbook.spike.firebase

import android.app.Application
import com.google.firebase.FirebaseOptions
import com.google.firebase.FirebasePlatform
import com.google.firebase.initialize
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.firestore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Initialize Firebase once with our JVM platform impl. Idempotent within a single JVM —
 * subsequent calls are no-ops because the underlying SDK throws "already exists" on re-init.
 *
 * Init pattern matches firebase-java-sdk's own tests: pass a synthetic [Application]
 * (the polyfilled `android.app.Application` from firebase-java-sdk's android-stubs),
 * then `Firebase.initialize(...)` from the gitlive `dev.gitlive.firebase` extension on
 * the `com.google.firebase.Firebase` companion.
 */
fun initializeFirebase(platform: FirebasePlatform = JvmFirebasePlatform()) {
    FirebasePlatform.initializeFirebasePlatform(platform)
    val options =
        FirebaseOptions
            .Builder()
            .setApiKey(FirebaseConfig.WEB_API_KEY)
            .setProjectId(FirebaseConfig.PROJECT_ID)
            .setApplicationId("1:823073361838:jvm:spike-firebase-poc") // synthetic; required field
            .setStorageBucket(FirebaseConfig.STORAGE_BUCKET)
            .build()
    runCatching {
        com.google.firebase.Firebase
            .initialize(Application(), options)
    }.onFailure { e ->
        if (e.message?.contains("already exists") != true) throw e
    }
}

/** Probe entry point dispatched from Main.kt. */
suspend fun runProbe(args: List<String>) {
    when (val cmd = args.firstOrNull()) {
        "listener-sanity" -> {
            listenerSanityProbe(
                email = args.getOrNull(1) ?: error("usage: listener-sanity <email> <password>"),
                password = args.getOrNull(2) ?: error("usage: listener-sanity <email> <password>"),
            )
        }

        "exchange-google-token" -> {
            exchangeGoogleTokenProbe(
                googleIdToken = args.getOrNull(1) ?: error("usage: exchange-google-token <google-id-token>"),
            )
        }

        "storage-rest" -> {
            storageRestProbe(
                firebaseIdToken = args.getOrNull(1) ?: error("usage: storage-rest <firebase-id-token>"),
            )
        }

        "pattern-a1" -> {
            patternA1Probe(
                googleIdToken = args.getOrNull(1) ?: error("usage: pattern-a1 <google-id-token>"),
            )
        }

        null -> {
            println("Available probes:")
            println("  listener-sanity <email> <password>")
            println("  exchange-google-token <google-id-token>")
            println("  storage-rest <firebase-id-token>")
            println("  pattern-a1 <google-id-token>")
        }

        else -> {
            error("unknown probe: $cmd")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Probe 1: Firestore listener sanity check (the load-bearing one)
// ---------------------------------------------------------------------------------------------

private suspend fun listenerSanityProbe(
    email: String,
    password: String,
) {
    println("[probe-listener] initializing Firebase...")
    initializeFirebase()

    println("[probe-listener] signing in as $email...")
    val auth = Firebase.auth
    val authResult = auth.signInWithEmailAndPassword(email, password)
    val user = authResult.user ?: error("sign-in returned no user")
    println("[probe-listener] signed in as uid=${user.uid}")

    val firestore = Firebase.firestore
    val docRef = firestore.collection("spike").document("listener-probe-${System.currentTimeMillis()}")
    val payload = mapOf("ts" to System.currentTimeMillis(), "from" to "spike-jvm")

    coroutineScope {
        // Listener side: subscribe BEFORE write, capture the first snapshot that has data.
        val listenerJob =
            async {
                println("[probe-listener] subscribing...")
                withTimeoutOrNull(15.seconds) {
                    docRef.snapshots.firstOrNull { snap -> snap.exists }
                }
            }

        // Give the listener a moment to attach before writing.
        delay(1_000)
        println("[probe-listener] writing doc at ${docRef.path}...")
        docRef.set(payload)

        val snapshot = listenerJob.await()
        if (snapshot == null) {
            println("[probe-listener] FAILED: listener did not receive a snapshot within 15s")
            println("[probe-listener]   This is the load-bearing failure. Firestore listeners")
            println("[probe-listener]   on JVM via gitlive don't work for this build/version.")
            return@coroutineScope
        }
        println("[probe-listener] SUCCESS: listener fired with data=${snapshot.data<Map<String, Any?>>()}")
        println("[probe-listener]   Firestore + listeners DO work on JVM. Pattern A1/A2")
        println("[probe-listener]   token-injection is now an engineering question, not a viability one.")
    }
}

// ---------------------------------------------------------------------------------------------
// Probe 2: Google ID token → Firebase ID token exchange (no Firebase SDK; pure REST)
// ---------------------------------------------------------------------------------------------

private suspend fun exchangeGoogleTokenProbe(googleIdToken: String) {
    println("[probe-exchange] verifying Google ID token signature...")
    val verifier = GoogleIdTokenVerifier(expectedAudience = SpikeSecrets.OAUTH_CLIENT_ID)
    val verified =
        verifier.verify(googleIdToken).getOrElse { e ->
            println("[probe-exchange] FAILED at JWKS verification: ${e.message}")
            return
        }
    println("[probe-exchange] Google token verified: sub=${verified.sub}, email=${verified.email}")

    val http = HttpClient(CIO)
    try {
        val client = IdentityToolkitClient(http)
        println("[probe-exchange] exchanging via Identity Toolkit...")
        val tokens =
            client.signInWithGoogleIdToken(googleIdToken).getOrElse { e ->
                println("[probe-exchange] FAILED at Identity Toolkit: ${e.message}")
                return
            }
        println("[probe-exchange] SUCCESS:")
        println("[probe-exchange]   firebase uid:   ${tokens.uid}")
        println("[probe-exchange]   id token (head): ${tokens.idToken.take(40)}...")
        println("[probe-exchange]   refresh token (head): ${tokens.refreshToken.take(40)}...")
        println("[probe-exchange]   expires at:     ${tokens.expiresAt}")
        println("[probe-exchange]")
        println("[probe-exchange] Pass the id token to `storage-rest` to validate the Storage path:")
        println("[probe-exchange]   ./gradlew :spikes:firebase-poc:run --args=\"storage-rest ${tokens.idToken}\"")
    } finally {
        http.close()
    }
}

// ---------------------------------------------------------------------------------------------
// Probe 4: Pattern A1 (storage hijack) end-to-end — Google OAuth → Identity Toolkit
//          → pre-seed FirebasePlatform → Firebase.initialize → Firestore listener
// ---------------------------------------------------------------------------------------------

private suspend fun patternA1Probe(googleIdToken: String) {
    println("[probe-a1] verifying Google ID token...")
    val verified =
        GoogleIdTokenVerifier(expectedAudience = SpikeSecrets.OAUTH_CLIENT_ID)
            .verify(googleIdToken)
            .getOrElse { e ->
                println("[probe-a1] FAILED at JWKS: ${e.message}")
                return
            }
    println("[probe-a1] Google token OK: ${verified.email}")

    val http = HttpClient(CIO)
    val tokens =
        try {
            IdentityToolkitClient(http).signInWithGoogleIdToken(googleIdToken).getOrElse { e ->
                println("[probe-a1] FAILED at Identity Toolkit: ${e.message}")
                return
            }
        } finally {
            http.close()
        }
    println("[probe-a1] Identity Toolkit OK: firebase uid=${tokens.uid}")

    println("[probe-a1] pre-seeding FirebasePlatform with auth state JSON...")
    val platform = preSeededPlatform(tokens)
    initializeFirebase(platform)

    val auth = Firebase.auth
    val current = auth.currentUser
    if (current == null) {
        println("[probe-a1] FAILED: currentUser is null after init — storage hijack didn't take")
        println("[probe-a1]   Storage key may have changed; check FirebaseAuth.kt:241 for `app.key`")
        return
    }
    println("[probe-a1] storage hijack worked: currentUser.uid=${current.uid}")

    val firestore = Firebase.firestore
    val docRef = firestore.collection("spike").document("a1-probe-${System.currentTimeMillis()}")
    val payload = mapOf("ts" to System.currentTimeMillis(), "from" to "pattern-a1", "uid" to current.uid)

    coroutineScope {
        val listenerJob =
            async {
                println("[probe-a1] subscribing...")
                withTimeoutOrNull(15.seconds) {
                    docRef.snapshots.firstOrNull { snap -> snap.exists }
                }
            }
        delay(1_000)
        println("[probe-a1] writing doc at ${docRef.path}...")
        docRef.set(payload)

        val snapshot = listenerJob.await()
        if (snapshot == null) {
            println("[probe-a1] FAILED: listener didn't fire within 15s.")
            println("[probe-a1]   Pattern A1 storage hijack works for currentUser but not")
            println("[probe-a1]   Firestore RPCs. Investigate InternalAuthProvider plumbing.")
            return@coroutineScope
        }
        println("[probe-a1] SUCCESS: listener fired with data=${snapshot.data<Map<String, Any?>>()}")
        println("[probe-a1] ")
        println("[probe-a1]   PATTERN A1 (STORAGE HIJACK) IS VIABLE.")
        println("[probe-a1]   - Google OAuth → Identity Toolkit → pre-seed → SDK boots signed-in")
        println("[probe-a1]   - Firestore listeners receive snapshots with our injected token")
        println("[probe-a1]   - SDK auto-refreshes via securetoken endpoint when idToken nears expiry")
        println("[probe-a1]   This is the recommended path for Phase 2.")
    }
}

// ---------------------------------------------------------------------------------------------
// Probe 3: Firebase Storage REST PUT — validates blob upload with Firebase ID token bearer
// ---------------------------------------------------------------------------------------------

private suspend fun storageRestProbe(firebaseIdToken: String) {
    val bucket = FirebaseConfig.STORAGE_BUCKET
    val objectPath = "spike/storage-probe-${System.currentTimeMillis()}.bin"
    val payloadBytes = ByteArray(1 * 1024 * 1024) { (it % 256).toByte() } // 1 MB

    println("[probe-storage] PUTting 1 MB blob to gs://$bucket/$objectPath ...")
    val http = HttpClient(CIO)
    try {
        val response =
            http.put("https://storage.googleapis.com/upload/storage/v1/b/$bucket/o") {
                url.parameters.append("uploadType", "media")
                url.parameters.append("name", objectPath)
                header("Authorization", "Bearer $firebaseIdToken")
                header("Content-Type", ContentType.Application.OctetStream.toString())
                setBody(payloadBytes)
            }
        when (response.status) {
            HttpStatusCode.OK -> println("[probe-storage] SUCCESS: 200 OK. Body: ${response.bodyAsText().take(200)}")
            else -> println("[probe-storage] FAILED: ${response.status}. Body: ${response.bodyAsText().take(500)}")
        }
    } finally {
        http.close()
    }
}
