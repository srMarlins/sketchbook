import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.conveyor)
}

kotlin {
    jvm()
    jvmToolchain(17)

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":shared:core"))
            implementation(project(":shared:repository"))
            implementation(project(":shared:ui-shared"))
            implementation(project(":shared:feature-projects"))
            implementation(project(":shared:feature-project-detail"))
            implementation(project(":shared:feature-timeline"))
            implementation(project(":shared:feature-proposals"))
            implementation(project(":shared:feature-needs-attention"))
            implementation(project(":shared:feature-settings"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.sketchbook.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "Sketchbook"
            packageVersion = "0.1.0"
        }
    }
}
