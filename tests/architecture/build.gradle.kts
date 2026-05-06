plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dependency.analysis)
    id("detekt-config")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Konsist 0.17.3 docs use JUnit 4 directly, but our tests use the
    // `kotlin.test.Test` / `kotlin.test.Ignore` annotations (which delegate to
    // JUnit 4 on JVM). Depend on `kotlin-test-junit` directly so
    // dependency-analysis sees the resolved capability — declaring
    // `kotlin("test")` (the umbrella alias) is flagged as unused because DA
    // resolves it to a different artifact than the one source references.
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.konsist)
}
