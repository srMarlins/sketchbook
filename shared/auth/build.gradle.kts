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
            // JWKS-based RS256 verification of Google ID tokens (security-commitment #1
            // in docs/plans/2026-05-08-firebase-migration-design.md).
            implementation(libs.nimbus.jose.jwt)
            // firebase-app: needed for the Pattern A1 token-injection plumbing
            // (FirebasePlatform implementation + Firebase.initialize call) that lives in
            // shared/auth/jvmMain/.../firebase/. The Firestore wrapper is NOT pulled in
            // here — that one stays scoped to :shared:cloud where the MetadataStore adapter
            // is the only consumer.
            implementation(libs.gitlive.firebase.app)
            implementation(libs.gitlive.firebase.auth)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}
