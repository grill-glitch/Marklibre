import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: read from keystore.properties (gitignored). When the
// file is absent the release build stays unsigned.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "org.librelab.marklibre"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.librelab.marklibre"
        // minSdk 35 means D8/R8 emits native multidex on its own: no
        // multiDexEnabled flag and no androidx.multidex dependency are
        // needed (both would be dead configuration at this API level).
        minSdk = 35
        targetSdk = 36
        versionCode = 237
        versionName = "1.2.3"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinking, optimization and obfuscation, release only. The
            // debug build type is left at the AGP defaults (no minification)
            // so it stays easy to debug.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.activity:activity-ktx:1.13.0")

    // Compose: used only for the color picker popup. compose-pipette targets
    // androidx.compose.* 1.10.1 on Android; BOM 2025.12.01 resolves to 1.10.0
    // and still supports compileSdk = 36 (BOM 2026.x requires compileSdk 37).
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    // The picker itself: HsvColor + Square/Ring/CircularColorPicker.
    implementation("dev.zt64.compose.pipette:compose-pipette:2.0.0")

    testImplementation("junit:junit:4.13.2")
}
