import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nextcraft.forge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nextcraft.forge.proot"
        minSdk = 26
        targetSdk = 35
        // The release workflow passes -PversionCode=... -PversionName=...; local builds keep the defaults.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 100
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.0-proot"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("String", "APP_VARIANT", "\"debug\"")
        // Manifest asset of the latest GitHub release; build with -PappUpdateManifestUrl=... to point elsewhere.
        val updateManifestUrl = (project.findProperty("appUpdateManifestUrl") as String?)
            ?: "https://github.com/Bymeopen/nextcraft-forge-proot/releases/latest/download/update-manifest.json"
        buildConfigField("String", "APP_UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"")
    }

    // Release signing comes from the environment so no key material lives in the repo.
    val releaseKeystore = System.getenv("FORGE_KEYSTORE_FILE")
    if (!releaseKeystore.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("FORGE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FORGE_KEY_ALIAS")
                keyPassword = System.getenv("FORGE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "APP_VARIANT", "\"debug\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
        // AGP prefers its own default NDK and fails when only another version is installed,
        // so pin to the version found under <sdk>/ndk when there is exactly one.
        ndkCandidates
            .firstOrNull { it.name == "ndk" && it.listFiles()?.size == 1 }
            ?.listFiles()?.single()?.name
            ?.let { ndkVersion = it }
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
        // libproot.so (src/main/jniLibs) is executed, not dlopen'ed: it must be extracted to nativeLibraryDir.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.core:core-ktx:1.8.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.5.1")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.apache.commons:commons-compress:1.26.2")
    // ZstdCompressorInputStream needs zstd-jni at runtime; rootfs bundles are .tar.zst.
    implementation("com.github.luben:zstd-jni:1.5.5-6@aar")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
