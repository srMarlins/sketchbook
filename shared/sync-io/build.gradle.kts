plugins {
    id("kmp-test")
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
            implementation(libs.metro.runtime)
        }
        jvmMain.dependencies {
            implementation(project(":shared:sync"))
            implementation(project(":shared:cloud"))
            implementation(project(":shared:catalog"))
            implementation(project(":shared:repository"))
            implementation(project(":shared:parser-als"))
            implementation(libs.directory.watcher)
            implementation(libs.blake3)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        commonTest.dependencies {
            implementation(libs.turbine)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
