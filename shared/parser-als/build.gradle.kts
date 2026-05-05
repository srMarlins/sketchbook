plugins {
    id("kmp-test")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core"))
        }
    }
}
