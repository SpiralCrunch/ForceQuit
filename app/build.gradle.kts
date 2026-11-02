plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.forcequit.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.forcequit.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"
    }

    // Release signing credentials live in ~/.gradle/gradle.properties, never in this repo.
    val keystorePath = providers.gradleProperty("FORCEQUIT_KEYSTORE").orNull
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.gradleProperty("FORCEQUIT_KEYSTORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("FORCEQUIT_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("FORCEQUIT_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            // Strips unused library code/resources (Compose is most of the APK's size).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
}
