plugins {
    id("kmp-test")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core"))
            implementation(project(":shared:catalog"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
            implementation(libs.metro.runtime)
        }
        commonTest.dependencies {
            implementation(libs.turbine)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // Used by SqlRepairRepositoryAlsRewriteTest to exercise the real StAX rewrite path
            // when asserting that both the primary FileRef and the OriginalFileRef sibling get
            // patched atomically — the substring-replace recording fake can't prove that.
            implementation(project(":shared:parser-als"))
        }
    }
}
