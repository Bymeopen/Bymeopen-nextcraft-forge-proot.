# Blok 7 — Konfigurasi Build Gradle, AndroidManifest, Sumber Daya Multi-Bahasa & Dokumentasi Bundle Runtime

Blok 7 melengkapi proyek **NextCraft Forge** dengan seluruh file konfigurasi sistem build Gradle (Kotlin DSL), manifes aplikasi Android, kebijakan keamanan jaringan & `FileProvider`, ikon adaptif, sumber daya terjemahan multi-bahasa, serta dokumentasi skrip pembuat bundle runtime `.tar.zst`.

### Fungsi Utama Blok 7:
1. **Konfigurasi Sistem Build Gradle (`settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `app/build.gradle.kts`)**:
   - Mengonfigurasi Android Gradle Plugin `8.11.0`, Kotlin `1.9.22`, Compose Compiler `1.5.10`, `compileSdk = 36`, `minSdk = 26`, `targetSdk = 35`, target ABI `arm64-v8a`, serta integrasi opsional CMake (`app/src/main/cpp/CMakeLists.txt`) untuk membangun pustaka JNI `libforgespawn.so` secara otomatis apabila Android NDK tersedia.
2. **Manifes Aplikasi & Deklarasi Layanan (`app/src/main/AndroidManifest.xml`)**:
   - Mendeklarasikan izin jaringan, `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, dan `REQUEST_INSTALL_PACKAGES`.
   - Mendaftarkan `MainActivity`, layanan latar depan `RuntimeExecutionService` (`specialUse`) dan `RuntimeSetupService` (`dataSync`), penerima siaran hasil instalasi APK `AndroidAppInstallReceiver`, serta dua otoritas `FileProvider` (`${applicationId}.fileprovider` dan `${applicationId}.files`) yang dibatasi secara ketat hanya pada subdirektori yang aman.
3. **Sumber Daya Multi-Bahasa, Tema, Ikon & Keamanan (`app/src/main/res/**`)**:
   - Menyediakan `strings.xml` untuk bahasa default (Inggris) beserta 6 lokalisasi tambahan (`values-in`, `values-es`, `values-zh-rCN`, `values-ar`, `values-hi`, `values-ru`), tema dasar Material (`themes.xml`), pembatasan jalur `FileProvider` (`file_paths.xml`), konfigurasi keamanan jaringan (`network_security_config.xml`), serta vektor ikon peluncur adaptif dan ikon notifikasi.
4. **Panduan Pembuatan Bundle Runtime Portabel (`scripts/runtime-bundles/README.md`)**:
   - Mendokumentasikan arsitektur 4 bundle `.tar.zst` (`core`, `claude`, `agy`, `dsh`) dan prosedur pembuatan arsip deterministik di perangkat Android ARM64 yang di-root.

## Daftar File dalam Blok Ini
- `settings.gradle.kts` (18 baris)
- `build.gradle.kts` (4 baris)
- `gradle.properties` (5 baris)
- `app/build.gradle.kts` (103 baris)
- `app/src/main/AndroidManifest.xml` (61 baris)
- `app/src/main/res/values/strings.xml` (5 baris)
- `app/src/main/res/values-in/strings.xml` (5 baris)
- `app/src/main/res/values-es/strings.xml` (5 baris)
- `app/src/main/res/values-zh-rCN/strings.xml` (5 baris)
- `app/src/main/res/values-ar/strings.xml` (5 baris)
- `app/src/main/res/values-hi/strings.xml` (5 baris)
- `app/src/main/res/values-ru/strings.xml` (5 baris)
- `app/src/main/res/values/themes.xml` (7 baris)
- `app/src/main/res/xml/file_paths.xml` (7 baris)
- `app/src/main/res/xml/network_security_config.xml` (13 baris)
- `app/src/main/res/drawable/ic_launcher_background.xml` (13 baris)
- `app/src/main/res/drawable/ic_launcher_foreground.xml` (13 baris)
- `app/src/main/res/drawable/ic_notification.xml` (10 baris)
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (5 baris)
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` (5 baris)
- `scripts/runtime-bundles/README.md` (14 baris)

---

## 1. `settings.gradle.kts` (18 baris)

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NextCraftForge"
include(":app")
```

---

## 2. `build.gradle.kts` (4 baris)

```kotlin
plugins {
    id("com.android.application") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
```

---

## 3. `gradle.properties` (5 baris)

```properties
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
org.gradle.daemon=false
org.gradle.workers.max=2
```

---

## 4. `app/build.gradle.kts` (103 baris)

```kotlin
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
```

---

## 5. `app/src/main/AndroidManifest.xml` (61 baris)

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
    <uses-permission
        android:name="android.permission.QUERY_ALL_PACKAGES"
        tools:ignore="QueryAllPackagesPermission" />

    <application
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:largeHeap="true"
        android:networkSecurityConfig="@xml/network_security_config"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.NextCraftForge">

        <activity
            android:name=".MainActivity"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".runtime.RuntimeExecutionService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />

        <service
            android:name=".runtime.RuntimeSetupService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />

        <receiver
            android:name=".runtime.AndroidAppInstallReceiver"
            android:exported="false" />

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
```

---

## 6. `app/src/main/res/values/strings.xml` (5 baris)

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">NextCraft Forge</string>
    <string name="app_tagline">Root Chroot AI Coding Studio for Android</string>
</resources>
```

---

## 7. `app/src/main/res/values-in/strings.xml` (5 baris)

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">NextCraft Forge</string>
    <string name="app_tagline">Studio Coding AI Root Chroot untuk Android</string>
</resources>
```

---

## 8. `app/src/main/res/values-es/strings.xml` (5 baris)

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">NextCraft Forge</string>
    <string name="app_tagline">Estudio de Programación IA Root Chroot para Android</string>
</resources>
```

---

## 9. `app/src/main/res/values-zh-rCN/strings.xml` (5 baris)

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">NextCraft Forge</string>
    <string name="app_tagline">适用于 Android 的 Root Chroot AI 编程工作室</string>
</resources>
```

---

## 10. `app/src/main/res/values-ar/strings.xml` (5 baris)

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">NextCraft Forge</string>
    <string name="app_tagline">استوديو البرمجة بالذكاء الاصطناعي Root Chroot لنظام أندرويد</string>
</resources>
```

---

## 11. `app/src/main/res/values-hi/strings.xml` (5 baris)

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">NextCraft Forge</string>
    <string name="app_tagline">Android के लिए Root Chroot AI कोडिंग स्टूडियो</string>
</resources>
```

---

## 12. `app/src/main/res/values-ru/strings.xml` (5 baris)

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">NextCraft Forge</string>
    <string name="app_tagline">ИИ-студия разработки Root Chroot для Android</string>
</resources>
```

---

## 13. `app/src/main/res/values/themes.xml` (7 baris)

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.NextCraftForge" parent="Theme.MaterialComponents.DayNight.NoActionBar">
        <item name="android:statusBarColor">#0B0F17</item>
        <item name="android:navigationBarColor">#0B0F17</item>
    </style>
</resources>
```

---

## 14. `app/src/main/res/xml/file_paths.xml` (7 baris)

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <!-- Only locations that APKs are shared from; never expose the whole filesDir (runtime env, chats, rootfs). -->
    <files-path name="workspaces" path="workspaces/" />
    <files-path name="updates" path="updates/" />
    <cache-path name="internal_cache" path="." />
</paths>
```

---

## 15. `app/src/main/res/xml/network_security_config.xml` (13 baris)

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- HTTPS everywhere; plain HTTP only for the on-device loopback (gateways, dev-server preview). -->
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">localhost</domain>
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
</network-security-config>
```

---

## 16. `app/src/main/res/drawable/ic_launcher_background.xml` (13 baris)

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#0B0F17"
        android:pathData="M0,0h108v108h-108z" />
    <path
        android:fillColor="#151E2E"
        android:pathData="M18,18h72v72h-72z" />
</vector>
```

---

## 17. `app/src/main/res/drawable/ic_launcher_foreground.xml` (13 baris)

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#38BDF8"
        android:pathData="M34,36L50,54L34,72L29,67L40.5,54L29,41L34,36z" />
    <path
        android:fillColor="#34D399"
        android:pathData="M54,65h24v7h-24z" />
</vector>
```

---

## 18. `app/src/main/res/drawable/ic_notification.xml` (10 baris)

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M4,5h16a2,2 0,0 1,2 2v10a2,2 0,0 1,-2 2H4a2,2 0,0 1,-2 -2V7a2,2 0,0 1,2 -2zM6.5,9L10,12L6.5,15L5.5,14L7.8,12L5.5,10L6.5,9zM11,14h6v1.5h-6V14z" />
</vector>
```

---

## 19. `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (5 baris)

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

---

## 20. `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` (5 baris)

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

---

## 21. `scripts/runtime-bundles/README.md` (14 baris)

```markdown
# NextCraft Forge Runtime Scripts & Bundle Builders (`Pure Root Chroot Edition`)

Direktori ini berisi dua kelompok skrip untuk **NextCraft Forge (`com.nextcraft.forge`)**:

1. **On-Device Root Chroot Supervisor Scripts** (dipasang ke `/data/data/com.nextcraft.forge/files/runtime/bin/`):
   - `chroot-exec.sh`: Supervisor eksekusi `su -mm` yang mengelola *idempotent kernel mounts* (`/dev`, `/dev/pts`, `/proc`, `/sys`, `/tmp`, `/dev/shm`), *read-only bind mounts* partisi Android (`/system`, `/apex`, `/vendor`, `/product` untuk binary Bionic `aapt2`), *dynamic workspace & bridge bind mounts* (`/workspace/nextcraft`, `/nextcraft-bridge`), pembersihan variabel environment Bionic tanpa menghapus variabel AI/Gateway, kompatibilitas `GLIBC_TUNABLES=glibc.pthread.rseq=0`, dan *Exit Trap* `chown -R $APP_UID:$APP_UID`.
   - `unmount-chroot.sh`: Guard pelepas seluruh mount kernel secara terbalik (`sort -r` pada `/proc/mounts`) dan memverifikasi 0 mount tersisa sebelum operasi `RootfsRepair` diizinkan menghapus atau mengganti direktori rootfs.

2. **Reproducible Runtime Bundle Builders** (untuk menghasilkan artefak `.tar.zst` deterministik):
   - `build-core-on-rooted-android.sh`: Membangun bundel `core` dari image resmi **Ubuntu 24.04.5 LTS (`noble`) ARM64** (`ubuntu-base-24.04.5-base-arm64.tar.gz`, SHA-256 terverifikasi), Node.js `v24.19.0`, `git`, `ripgrep`, `jq`, serta konfigurasi jaringan kernel Android (`aid_inet:x:3003:root,_apt` dan `99android-chroot`).
   - `build-agy-from-official.sh`: Mengemas binary resmi Linux ARM64 **Antigravity CLI (`agy`)** dengan verifikasi `SHA-512`.
   - `build-claude-from-installed-android.sh`: Mengekspor binary **Claude Code (`claude`)** ARM64 terverifikasi `SHA-256` tanpa kredensial atau data pengguna.
   - `build-dsh-from-installed-android.sh`: Mengemas payload npm **DeepSeek Harness (`dsh`)** beserta symlink peluncurnya.
   - `create_deterministic_tar.py`: Menghasilkan arsip GNU tar yang 100% deterministik (`uid=0`, `gid=0`, `mtime=2026-09-06T00:00:00Z`, urutan alfabetis, mempertahankan symlink `followlinks=False` / `dereference=False`, dan membuang metadata macOS `._*`).
```
