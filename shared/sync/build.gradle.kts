plugins {
    id("kmp-test")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core"))
            implementation(project(":shared:repository"))
            implementation(project(":shared:cloud"))
            implementation(project(":shared:sync-io"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
