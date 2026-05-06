plugins {
    id("kmp-library")
    id("org.jetbrains.kotlin.plugin.power-assert")
}

kotlin {
    sourceSets {
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

powerAssert {
    functions =
        listOf(
            "kotlin.assert",
            "kotlin.test.assertTrue",
            "kotlin.test.assertEquals",
            "kotlin.test.assertNull",
            "kotlin.test.assertNotNull",
        )
}
