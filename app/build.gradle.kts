import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.devshield"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.devshield"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()

    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { stream ->
            keystoreProperties.load(stream)
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                val storeFilePath: String = keystoreProperties.getProperty("DEVSHIELD_STORE_FILE") ?: ""
                val storePasswordVal: String = keystoreProperties.getProperty("DEVSHIELD_STORE_PASSWORD") ?: ""
                val keyAliasVal: String = keystoreProperties.getProperty("DEVSHIELD_KEY_ALIAS") ?: ""
                val keyPasswordVal: String = keystoreProperties.getProperty("DEVSHIELD_KEY_PASSWORD") ?: ""

                if (storeFilePath.isBlank()) throw GradleException("Missing or blank 'DEVSHIELD_STORE_FILE' in keystore.properties")
                if (storePasswordVal.isBlank()) throw GradleException("Missing or blank 'DEVSHIELD_STORE_PASSWORD' in keystore.properties")
                if (keyAliasVal.isBlank()) throw GradleException("Missing or blank 'DEVSHIELD_KEY_ALIAS' in keystore.properties")
                if (keyPasswordVal.isBlank()) throw GradleException("Missing or blank 'DEVSHIELD_KEY_PASSWORD' in keystore.properties")

                val keystoreFile = File(storeFilePath)
                if (!keystoreFile.exists()) {
                    throw GradleException("Keystore file does not exist at '$storeFilePath' (specified in keystore.properties)")
                }

                storeFile = keystoreFile
                storePassword = storePasswordVal
                keyAlias = keyAliasVal
                keyPassword = keyPasswordVal
            } else {
                val isReleaseRequested = gradle.startParameter.taskNames.any {
                    it.contains("Release", ignoreCase = true) || it == "build"
                }
                if (isReleaseRequested) {
                    throw GradleException("Release build failed: 'keystore.properties' was not found at project root. Please provide keystore.properties with DEVSHIELD_STORE_FILE, DEVSHIELD_STORE_PASSWORD, DEVSHIELD_KEY_ALIAS, and DEVSHIELD_KEY_PASSWORD.")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.ext)
    testImplementation(libs.robolectric)
}
