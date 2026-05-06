plugins {
    id("kmp-test")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core"))
            implementation(project(":shared:catalog"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
        }
        commonTest.dependencies {
            implementation(libs.turbine)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.jvm.driver)
            // Used by SqlRepairRepositoryAlsRewriteTest to exercise the real StAX rewrite path
            // when asserting that both the primary FileRef and the OriginalFileRef sibling get
            // patched atomically — the substring-replace recording fake can't prove that.
            implementation(project(":shared:parser-als"))
        }
    }
}
