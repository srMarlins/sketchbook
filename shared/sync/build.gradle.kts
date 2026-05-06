plugins {
    id("kmp-test")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core"))
            implementation(project(":shared:repository"))
            implementation(project(":shared:cloud"))
            implementation(project(":shared:catalog"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
        }
        jvmMain.dependencies {
            // JvmBlobCache uses the catalog's blob_cache table directly via the SQLDelight
            // queries; pulling :shared:catalog here keeps the JVM cache impl jvmMain-local
            // without dragging SQL into commonMain.
            implementation(project(":shared:catalog"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.sqldelight.jvm.driver)
        }
    }
}
