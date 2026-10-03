buildscript {
    dependencies {
        // Upgrade the built-in Kotlin (AGP 9 default: 2.2.10) to Kotlin 2.4.0
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

plugins {
    id("com.android.application") version "9.3.1" apply false
    // Compose Compiler Gradle Plugin (Kotlin 2.4.0) — needed for Compose UI.
    // compose-pipette targets androidx.compose.* 1.10.1 on Android; we pin
    // the BOM to a matching 1.12.x and rely on standard resolution.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
}
