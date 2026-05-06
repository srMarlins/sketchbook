plugins {
    `kotlin-dsl`
}

// Pull version-catalog entries so precompiled-script convention plugins can
// resolve the same Kotlin / Compose / etc. versions as the root build.
val libs = the<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

fun version(name: String): String = libs.findVersion(name).orElseThrow { IllegalStateException("missing version: $name") }.requiredVersion

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${version("kotlin")}")
    implementation("org.jetbrains.kotlin:kotlin-power-assert:${version("kotlin")}")
    // detekt 2.x publishes its Gradle plugin under the `dev.detekt` group; the
    // jar is served from the Gradle Plugin Portal (not Maven Central) for the
    // 2.0.0-alpha line.
    implementation("dev.detekt:detekt-gradle-plugin:${version("detekt")}")
    // Dependency-analysis must be on the build-logic classpath so convention
    // plugins (kmp-library) can apply it via `id("com.autonomousapps.dependency-analysis")`.
    implementation("com.autonomousapps:dependency-analysis-gradle-plugin:${version("dependency-analysis")}")
}
