plugins {
    id("kmp-test")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
        }
        jvmMain.dependencies {
            implementation(project(":shared:sync"))
            implementation(project(":shared:cloud"))
            implementation(project(":shared:catalog"))
            implementation(project(":shared:repository"))
            implementation(libs.directory.watcher)
            implementation(libs.blake3)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.sqldelight.jvm.driver)
        }
        commonTest.dependencies {
            implementation(libs.turbine)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
