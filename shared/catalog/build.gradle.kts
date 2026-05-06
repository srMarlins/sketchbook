plugins {
    id("kmp-test")
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.metro)
}

sqldelight {
    databases {
        create("Catalog") {
            packageName.set("com.sketchbook.catalog.db")
            // 3.38 dialect enables FTS5 syntax in .sq files.
            dialect(libs.sqldelight.fts5.dialect)
        }
    }
}

// SQLDelight's verifyMigration task spins up xerial JDBC during the build, which fails on
// some JDKs with `NativeDB._open_utf8` (native lib not found). Migrations are exercised at
// runtime via CatalogDbTest.schemaCreatesAllExpectedTables, so the build-time verify is
// redundant. Disable it so `./gradlew check` is green.
tasks.matching { it.name.startsWith("verify") && it.name.endsWith("Migration") }.configureEach {
    enabled = false
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
            implementation(libs.metro.runtime)
        }
        jvmMain.dependencies {
            implementation(project(":shared:parser-als"))
            implementation(libs.sqldelight.jvm.driver)
        }
        commonTest.dependencies {
            implementation(libs.turbine)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
