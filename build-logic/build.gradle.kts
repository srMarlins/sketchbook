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
}
