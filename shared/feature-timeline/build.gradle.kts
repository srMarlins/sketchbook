plugins {
    id("kmp-compose")
    id("screenshot-tests")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core"))
            implementation(project(":shared:repository"))
            implementation(project(":shared:ui-shared"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(libs.metro.runtime)
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.metro.viewmodel)
            implementation(libs.metro.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.datetime)
            implementation(libs.turbine)
        }
    }
}
