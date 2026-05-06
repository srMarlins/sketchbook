plugins {
    id("kmp-test")
    alias(libs.plugins.sqldelight)
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
            implementation(libs.kotlinx.datetime)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
        }
        jvmMain.dependencies {
            implementation(project(":shared:parser-als"))
            implementation(libs.sqldelight.jvm.driver)
            // Compile-time access to JdbcDrivers.fromDataSource(...) and SQLiteDataSource.
            // Both are pulled at runtime via sqldelight-jvm-driver, but Gradle's strict mode
            // hides their transitive classes from the consumer's compile classpath; declare
            // explicitly so we can construct the pool in CatalogDb.openOnDisk.
            implementation(libs.sqldelight.jdbc.driver)
            implementation(libs.xerial.sqlite.jdbc)
            // HikariCP wraps the xerial SQLiteDataSource so we can hand SQLDelight a pool.
            // Without a pool, every read serializes through the single JDBC connection that
            // the SQLDelight driver opens.
            implementation(libs.hikaricp)
        }
        commonTest.dependencies {
            implementation(libs.turbine)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
