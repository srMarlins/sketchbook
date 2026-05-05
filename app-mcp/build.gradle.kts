plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

application {
    mainClass.set("com.sketchbook.mcp.app.MainKt")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared:mcp-server"))
    implementation(project(":shared:repository"))
    implementation(libs.kotlinx.coroutines.core)
}
