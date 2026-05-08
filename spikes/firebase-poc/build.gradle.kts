// Phase 0 spike for the Firebase migration design
// (docs/plans/2026-05-08-firebase-migration-design.md).
//
// Validates Pattern A (token injection): does gitlive's `FirebasePlatform` token hook on
// JVM gate Firestore listener-RPC auth, so we can hold Firebase tokens in our own
// AuthSession and inject them on demand without using the SDK's Auth state? If yes,
// Pattern A ships. If no, fall back to Pattern B (custom token via Cloud Function).
//
// Throwaway module — not depended on by anything else. Plain Kotlin/JVM (no KMP) for
// minimum machinery. Delete the whole `spikes/firebase-poc/` directory once Phase 0 is
// complete and findings are appended to the design doc.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.sketchbook.spike.firebase.MainKt")
}

dependencies {
    implementation(libs.gitlive.firebase.app)
    implementation(libs.gitlive.firebase.auth)
    implementation(libs.gitlive.firebase.firestore)

    implementation(libs.kotlinx.coroutines.core)
    // gitlive's auth post-sign-in path uses Dispatchers.Main to notify listeners
    // (FirebaseAuth.kt:330–333). On Android the Play Services SDK registers it; on JVM
    // we have to bring our own. Swing dispatcher works for both Compose Desktop apps
    // (which run on Swing/AWT) and pure CLI use cases.
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    implementation(libs.kermit)

    // JWKS / JWT signature verification (security-commitment #1).
    implementation(libs.nimbus.jose.jwt)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
