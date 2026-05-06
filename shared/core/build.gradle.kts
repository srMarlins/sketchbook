plugins {
    id("kmp-test")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.metro.runtime)
        }
        commonTest.dependencies {
            implementation(libs.kotest.assertions.core)
        }
    }
}
