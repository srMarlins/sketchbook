plugins {
    id("kmp-test")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core"))
            implementation(project(":shared:catalog"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.turbine)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
