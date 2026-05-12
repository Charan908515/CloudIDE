plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.cloudide.android"
    compileSdk = 34
    // Pin to the working NDK install. AGP otherwise auto-selects 26.1.10909125
    // which on this machine is a partial install missing source.properties.
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.cloudide.android"
        minSdk = 24
        targetSdk = 28 // Legacy mode: disables W^X / seccomp / Phantom Process Killer
        versionCode = 1
        versionName = "1.0.0"

        // The OAuth Web Client ID from Google Cloud Console (same project as desktop).
        // Set GOOGLE_WEB_CLIENT_ID in your local.properties or environment.
        val webClientId = (project.findProperty("GOOGLE_WEB_CLIENT_ID") as String?)
            ?: System.getenv("GOOGLE_WEB_CLIENT_ID")
            ?: ""
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$webClientId\"")

        // Build only arm64 (matches the bundled libproot.so).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cFlags += listOf("-Wall", "-Wextra")
            }
        }
    }

    // CMake build of libcloudide-pty.so (forkpty/exec wrapper for the shell).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // AGP 8.x defaults useLegacyPackaging=false, which forces
        // extractNativeLibs=false in the merged manifest regardless of what
        // AndroidManifest.xml says. Flip it so the installer actually extracts
        // lib/<abi>/libproot.so (and the loader stubs + libtalloc) to
        // nativeLibraryDir as real files — required to exec libproot via
        // ProcessBuilder. Without this, proot fails to launch on AGP 8 builds.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.play.services.auth)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.xz)

    debugImplementation(libs.androidx.ui.tooling)
}
