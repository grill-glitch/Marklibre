buildscript {
    dependencies {
        // Upgrade the built-in Kotlin (AGP 9 default: 2.2.10) to Kotlin 2.4.0
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

plugins {
    id("com.android.application") version "9.3.1" apply false
}
