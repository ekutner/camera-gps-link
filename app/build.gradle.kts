import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.kutner.cameragpslink"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.kutner.cameragpslink"
        minSdk = 24
        targetSdk = 36
        versionCode = 7
        versionName = "0.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            // 1. Load keystore.properties (Local Only)
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            val keystoreProperties = Properties()

            if (keystorePropertiesFile.exists()) {
                keystorePropertiesFile.inputStream().use {
                    keystoreProperties.load(it)
                }
            }

            // 2. Determine Keystore Path
            // Priority: Properties File -> Env Var -> Default to root "keystore.jks"
            val keystorePath = keystoreProperties.getProperty("SIGNING_KEY_STORE_PATH")
                ?: System.getenv("SIGNING_KEY_STORE_PATH")
                ?: "keystore.jks"

            // 3. Resolve File Object Correctly
            // If path is absolute (CI), use it directly.
            // If relative (Local), resolve relative to ROOT project, not app module.
            val keystoreFile = if (File(keystorePath).isAbsolute) {
                File(keystorePath)
            } else {
                rootProject.file(keystorePath)
            }

            // 4. Configure Signing if file exists
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = keystoreProperties.getProperty("KEYSTORE_PASSWORD")
                    ?: System.getenv("KEYSTORE_PASSWORD")
                keyAlias = keystoreProperties.getProperty("KEY_ALIAS")
                    ?: System.getenv("KEY_ALIAS")
                keyPassword = keystoreProperties.getProperty("KEY_PASSWORD")
                    ?: System.getenv("KEY_PASSWORD")

                println("Release signing configured with: ${keystoreFile.absolutePath}")
            } else {
                println("Warning: Keystore not found at: ${keystoreFile.absolutePath}")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Assign the signing config directly.
            // If the keystore logic above failed, this will just be empty/unsigned
            // or fail depending on strictness, which is better than silent failure.
            signingConfig = signingConfigs.getByName("release")
        }
    }
//    signingConfigs {
//        create("release") {
//            // Load properties from keystore.properties file (for local dev)
//            // This is separate from local.properties to avoid Android Studio conflicts
//            val keystorePropertiesFile = File(rootProject.projectDir, "keystore.properties")
//            val keystoreProperties = Properties()
//
//            if (keystorePropertiesFile.exists()) {
//                keystoreProperties.load(keystorePropertiesFile.inputStream())
//            }
//
//            // Priority: keystore.properties (local) -> environment variables (CI) -> default
//            val keystorePath = keystoreProperties.getProperty("SIGNING_KEY_STORE_PATH")
//                ?: System.getenv("SIGNING_KEY_STORE_PATH")
//                ?: "keystore.jks"
//            val keystoreFile = file(keystorePath)
//
//            if (keystoreFile.exists()) {
//                storeFile = keystoreFile
//                // For each property, try keystore.properties first, then environment variable
//                storePassword = keystoreProperties.getProperty("KEYSTORE_PASSWORD")
//                    ?: System.getenv("KEYSTORE_PASSWORD")
//                keyAlias = keystoreProperties.getProperty("KEY_ALIAS")
//                    ?: System.getenv("KEY_ALIAS")
//                keyPassword = keystoreProperties.getProperty("KEY_PASSWORD")
//                    ?: System.getenv("KEY_PASSWORD")
//
//                println("Release signing configured with keystore at: $keystorePath")
//            } else {
//                println("Warning: Keystore file not found at $keystorePath. Release signing will be skipped.")
//            }
//        }
//    }
//
//    buildTypes {
//        release {
//            isMinifyEnabled = false
//            proguardFiles(
//                getDefaultProguardFile("proguard-android-optimize.txt"),
//                "proguard-rules.pro"
//            )
//
//            // Only apply signing config if keystore exists
//            val keystorePropertiesFile = File(rootProject.projectDir, "keystore.properties")
//            val keystoreProperties = Properties()
//            if (keystorePropertiesFile.exists()) {
//                keystoreProperties.load(keystorePropertiesFile.inputStream())
//            }
//
//            val keystorePath = keystoreProperties.getProperty("SIGNING_KEY_STORE_PATH")
//                ?: System.getenv("SIGNING_KEY_STORE_PATH")
//                ?: "keystore.jks"
//
//            if (file(keystorePath).exists()) {
//                signingConfig = signingConfigs.getByName("release")
//            }
//        }
//    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Material Icons Extended
    implementation("androidx.compose.material:material-icons-extended:1.7.5")

    // Explicitly add Fragment dependency to fix ActivityResult API issue
    implementation(libs.androidx.fragment.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Google Play Services Location
    implementation(libs.play.services.location)
}