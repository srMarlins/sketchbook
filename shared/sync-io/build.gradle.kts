plugins {
    id("kmp-test")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
        jvmMain.dependencies {
            implementation(libs.directory.watcher)
        }
        commonTest.dependencies {
            implementation(libs.turbine)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
