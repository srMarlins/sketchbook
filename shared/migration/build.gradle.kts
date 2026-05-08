plugins {
    id("kmp-test")
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core"))
            implementation(project(":shared:catalog"))
            implementation(project(":shared:cloud"))
            implementation(project(":shared:repository"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.metro.runtime)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
