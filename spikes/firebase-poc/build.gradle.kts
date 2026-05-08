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
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    implementation(libs.kermit)
}
