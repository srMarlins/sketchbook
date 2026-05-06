plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.konsist)
}

// Konsist 0.17.3 examples in the upstream docs use JUnit 4 (`org.junit.Test`).
// `kotlin("test")` resolves to kotlin-test-junit by default on JVM, which is
// JUnit 4 — so no `useJUnitPlatform()` here.
