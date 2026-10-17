import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nextcraft.forge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nextcraft.forge"
        minSdk = 26
        targetSdk = 35
        versionCode = 100
        versionName = "1.0.0-forge"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("String", "APP_VARIANT", "\"debug\"")
        buildConfigField("String", "APP_UPDATE_MANIFEST_URL", "\"\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "APP_VARIANT", "\"debug\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "APP_VARIANT", "\"release\"")
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    // Build libforgespawn.so whenever an NDK can be located; without it NativeSpawnProcess
    // silently falls back to ProcessBuilder (no PTY, no process-group kill).
    val ndkCandidates = listOfNotNull(
        System.getenv("ANDROID_NDK_HOME")?.let(::File),
        System.getenv("ANDROID_NDK_ROOT")?.let(::File),
        System.getenv("ANDROID_HOME")?.let { File(it, "ndk") },
        System.getenv("ANDROID_SDK_ROOT")?.let { File(it, "ndk") },
        File("/root/android-sdk/ndk"),
    )
    val ndkAvailable = ndkCandidates.any { it.isDirectory && !it.listFiles().isNullOrEmpty() }
    if (ndkAvailable) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2022.10.00"))
    implementation("androidx.core:core-ktx:1.8.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.5.1")
    implementation("androidx.activity:activity-compose:1.5.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("org.apache.commons:commons-compress:1.21")
    // ZstdCompressorInputStream needs zstd-jni at runtime; rootfs bundles are .tar.zst.
    implementation("com.github.luben:zstd-jni:1.5.5-6@aar")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
