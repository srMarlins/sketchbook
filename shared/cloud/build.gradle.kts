plugins {
    id("kmp-test")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.utils.jvm)
            implementation(libs.ktor.io.jvm)
            // gitlive's Kotlin Multiplatform wrappers; on JVM these pull in firebase-java-sdk
            // (a community polyfill of the Firebase Android SDK against pure-JVM stubs).
            // App code MUST NOT import dev.gitlive.firebase.* outside this module — the
            // FirestoreMetadataStore adapter is the only consumer, by design (see
            // docs/plans/2026-05-08-firebase-migration-design.md "gitlive firebase-kotlin-sdk
            // is at v0.6.x" — wrapping the SDK is a stated risk-mitigation).
            implementation(libs.gitlive.firebase.app)
            implementation(libs.gitlive.firebase.firestore)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}
