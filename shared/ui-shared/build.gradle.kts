plugins {
    id("kmp-compose")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// Per design doc §2.2 ui-shared may depend only on `core`. Today it transitively re-exports
// nothing from core, so no project dep is declared (DA would flag it as unused). If a shared
// composable starts surfacing a `core` type, restore `implementation(project(":shared:core"))`.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
