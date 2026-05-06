plugins {
    id("kmp-test")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Empty — this module exists only for tests. commonMain is required by KMP.
        }
        jvmTest.dependencies {
            implementation(project(":shared:core"))
            implementation(project(":shared:catalog"))
            implementation(project(":shared:repository"))
            implementation(project(":shared:actions"))
            implementation(project(":shared:cloud"))
            implementation(project(":shared:sync"))
            implementation(project(":shared:sync-io"))
            implementation(project(":shared:parser-als"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.io.core)
            implementation(libs.sqldelight.jvm.driver)
            implementation(libs.turbine)
        }
    }
}
