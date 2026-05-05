plugins {
    id("kmp-compose")
}

// Depends on `core` only (per design doc §2.2). No data flow.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core"))
        }
    }
}
