# Blok 4 — Pipeline Build Android Lokal, APK Installer, Foreground Services, Jaringan & Self-Updater

Blok 4 menyediakan fitur-fitur integrasi sistem Android dan jaringan yang membuat NextCraft Forge dapat **membangun proyek Android menjadi APK secara lokal di ponsel, menginstal APK dengan 1 ketukan, menjaga sesi agen tetap hidup di latar belakang, mengelola repositori GitHub, dan memperbarui dirinya sendiri**.

### Fungsi Utama Blok 4:
1. **Generator Proyek & Pipeline Build Android (`AndroidProjectTemplateGenerator.kt` & `AndroidBuildSupport.kt`)**:
   - Menghasilkan proyek Android siap-pakai (Jetpack Compose Material 3 maupun XML Views) lengkap dengan konfigurasi AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, dan Java 17 yang selaras dengan cache Maven offline bawaan.
   - Mendeteksi root proyek Android, menyusun perintah `gradle assembleDebug` yang dioptimalkan untuk ARM64 (`aapt2FromMavenOverride`, `--offline` fast-path jika APK debug sebelumnya sudah ada), mendeteksi tahapan build (`RESOLVING`, `COMPILING`, `RESOURCES`, `PACKAGING`), menemukan file APK hasil build (dengan proteksi path traversal pada `output-metadata.json`), dan mem-parsing baris error `kotlinc`/`javac`/`aapt2` menjadi daftar `AndroidBuildIssue` lengkap dengan path file dan nomor baris.
2. **Instalasi APK 1-Tap via Root & PackageInstaller (`AndroidAppInstaller.kt` & `AndroidAppInstallReceiver.kt`)**:
   - Pada perangkat root, menginstal APK hasil build secara senyap melalui streaming stdin ke `su -c "pm install -r -d -S <size>"` lalu langsung meluncurkan aplikasi yang baru diinstal.
   - Jika instalasi senyap gagal atau saat melakukan self-update, otomatis beralih ke sesi `PackageInstaller.Session` Android standar (atau intent `FileProvider` pada perangkat MIUI) dan menerima callback status instalasi melalui `AndroidAppInstallReceiver`.
3. **Foreground Services Eksekusi & Instalasi (`RuntimeExecutionService.kt` & `RuntimeSetupService.kt`)**:
   - Menjaga proses agen coding dan proses download/ekstraksi toolchain tetap berjalan meskipun pengguna berpindah aplikasi atau layar mati, menggunakan `PARTIAL_WAKE_LOCK`, notifikasi progres real-time, serta tombol aksi **Stop**.
4. **Klien Jaringan, Validasi Provider, Pemindai Port Lokal & Pembaruan Aplikasi (`ProviderApiClient.kt`, `LocalNetwork.kt`, `GitHubClient.kt`, `AppUpdater.kt`)**:
   - `ProviderApiClient`: Menguji koneksi API key dan mengambil daftar katalog model terbaru dari Anthropic, OpenAI, OpenRouter, DeepSeek, Kimi, OpenCode Zen, NVIDIA NIM, atau server lokal.
   - `LocalNetwork`: Memvalidasi kebijakan keamanan HTTP lokal (loopback, LAN RFC1918, Tailscale CGNAT `100.64.0.0/10`, IPv6 ULA, akhiran `.local`/`.lan`/`.home`/`.internal`/`.ts.net`) dan memindai port server dev lokal yang terbuka di `127.0.0.1` untuk fitur Live Preview.
   - `GitHubClient`: Mengambil profil akun dan daftar repositori GitHub pengguna menggunakan Personal Access Token (`X-GitHub-Api-Version: 2022-11-28`).
   - `AppUpdater`: Memeriksa `update-manifest.json`, mengunduh APK pembaruan NextCraft Forge, dan mewajibkan verifikasi integritas SHA-256 sebelum menginstal update.

## Daftar File dalam Blok Ini
- `app/src/main/java/com/nextcraft/forge/runtime/AndroidProjectTemplateGenerator.kt` (308 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/AndroidBuildSupport.kt` (338 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/AndroidAppInstaller.kt` (273 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/AndroidAppInstallReceiver.kt` (51 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/RuntimeExecutionService.kt` (232 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/RuntimeSetupService.kt` (425 baris)
- `app/src/main/java/com/nextcraft/forge/network/ProviderApiClient.kt` (364 baris)
- `app/src/main/java/com/nextcraft/forge/network/LocalNetwork.kt` (114 baris)
- `app/src/main/java/com/nextcraft/forge/network/GitHubClient.kt` (229 baris)
- `app/src/main/java/com/nextcraft/forge/update/AppUpdater.kt` (214 baris)

---

## 1. `app/src/main/java/com/nextcraft/forge/runtime/AndroidProjectTemplateGenerator.kt` (308 baris)

```kotlin
package com.nextcraft.forge.runtime

import com.nextcraft.forge.model.AndroidTemplate
import com.nextcraft.forge.model.projectSlug
import java.io.File
import java.nio.file.Files

object AndroidProjectTemplateGenerator {
    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "true", "false", "null",
    )

    fun packageName(name: String): String {
        val raw = projectSlug(name).replace('-', '_').take(40).ifBlank { "app" }
        val suffix = if (raw.first().isLetter() && raw !in JAVA_KEYWORDS) raw else "app_$raw"
        return "com.nextcraft.$suffix"
    }

    fun generate(destination: File, appName: String, template: AndroidTemplate) {
        val parent = requireNotNull(destination.parentFile) { "Destination must have a parent directory" }
        parent.mkdirs()
        val staging = File(parent, ".${destination.name}.android-template")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not prepare the Android project" }
        try {
            val pkg = packageName(appName)
            writeCommon(staging, appName, pkg, template)
            when (template) {
                AndroidTemplate.COMPOSE -> writeCompose(staging, pkg)
                AndroidTemplate.XML -> writeXml(staging, pkg)
            }
            if (destination.exists()) {
                check(destination.listFiles().isNullOrEmpty()) { "Project folder is not empty" }
                check(destination.delete()) { "Could not replace the empty project folder" }
            }
            check(staging.renameTo(destination)) { "Could not finish creating the Android project" }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun writeCommon(root: File, appName: String, namespace: String, template: AndroidTemplate) {
        val compose = template == AndroidTemplate.COMPOSE
        write(
            root,
            "settings.gradle.kts",
            """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
            rootProject.name = ${kotlinString(appName)}
            include(":app")
            """,
        )
        write(
            root,
            "build.gradle.kts",
            """
            plugins {
                id("com.android.application") version "8.11.0" apply false
                id("org.jetbrains.kotlin.android") version "1.9.22" apply false
            }
            """,
        )
        write(
            root,
            "gradle.properties",
            """
            android.useAndroidX=true
            kotlin.code.style=official
            android.nonTransitiveRClass=true
            """,
        )
        write(
            root,
            "app/build.gradle.kts",
            """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
            }

            android {
                namespace = "$namespace"
                compileSdk = 36

                defaultConfig {
                    applicationId = "$namespace"
                    minSdk = 28
                    targetSdk = 36
                    versionCode = 1
                    versionName = "1.0"
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                kotlinOptions { jvmTarget = "17" }
                buildFeatures { compose = $compose }
                ${if (compose) "composeOptions { kotlinCompilerExtensionVersion = \"1.5.10\" }" else ""}
            }

            dependencies {
                implementation("androidx.core:core-ktx:1.8.0")
                ${if (compose) """
                implementation("androidx.activity:activity-compose:1.5.1")
                implementation("androidx.compose.ui:ui:1.3.0")
                implementation("androidx.compose.foundation:foundation:1.3.0")
                implementation("androidx.compose.material3:material3:1.0.0")
                """.trimIndent() else """
                implementation("androidx.appcompat:appcompat:1.6.1")
                implementation("com.google.android.material:material:1.9.0")
                """.trimIndent()}
            }
            """,
        )
        write(
            root,
            ".gitignore",
            """
            .gradle/
            local.properties
            **/build/
            *.iml
            """,
        )
        val projectJson = """{"schemaVersion":1,"template":"${template.name}","packageName":"$namespace"}"""
        write(root, ".pocketdev/android-project.json", projectJson)
        write(root, ".nextcraft/android-project.json", projectJson)
        write(
            root,
            "app/src/main/res/values/strings.xml",
            """
            <resources><string name="app_name">${xmlEscape(appName)}</string></resources>
            """,
        )
    }

    private fun writeCompose(root: File, namespace: String) {
        val packagePath = namespace.replace('.', '/')
        write(
            root,
            "app/src/main/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:theme="@style/Theme.PocketDevApp" android:label="@string/app_name">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """,
        )
        write(
            root,
            "app/src/main/res/values/themes.xml",
            """
            <resources>
                <style name="Theme.PocketDevApp" parent="android:style/Theme.Material.Light.NoActionBar">
                    <item name="android:fontFamily">sans</item>
                    <item name="android:colorAccent">#6750A4</item>
                    <item name="android:statusBarColor">#FFFBFE</item>
                    <item name="android:navigationBarColor">#FFFBFE</item>
                    <item name="android:windowLightStatusBar">true</item>
                    <item name="android:windowLightNavigationBar">true</item>
                </style>
            </resources>
            """,
        )
        write(
            root,
            "app/src/main/java/$packagePath/MainActivity.kt",
            """
            package $namespace

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.compose.foundation.layout.Arrangement
            import androidx.compose.foundation.layout.Column
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Alignment
            import androidx.compose.ui.Modifier

            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent { MaterialTheme { Welcome() } }
                }
            }

            @Composable
            private fun Welcome() {
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text("Built with NextCraft Forge")
                }
            }
            """,
        )
    }

    private fun writeXml(root: File, namespace: String) {
        val packagePath = namespace.replace('.', '/')
        write(
            root,
            "app/src/main/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:theme="@style/Theme.PocketDevApp" android:label="@string/app_name">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """,
        )
        write(
            root,
            "app/src/main/res/values/themes.xml",
            """
            <resources>
                <style name="Theme.PocketDevApp" parent="Theme.MaterialComponents.DayNight.NoActionBar">
                    <item name="colorPrimary">#6750A4</item>
                    <item name="android:statusBarColor">?android:colorBackground</item>
                    <item name="android:navigationBarColor">?android:colorBackground</item>
                    <item name="android:windowLightStatusBar">true</item>
                    <item name="android:windowLightNavigationBar">true</item>
                </style>
            </resources>
            """,
        )
        write(
            root,
            "app/src/main/res/values-night/themes.xml",
            """
            <resources>
                <style name="Theme.PocketDevApp" parent="Theme.MaterialComponents.DayNight.NoActionBar">
                    <item name="colorPrimary">#D0BCFF</item>
                    <item name="android:statusBarColor">?android:colorBackground</item>
                    <item name="android:navigationBarColor">?android:colorBackground</item>
                    <item name="android:windowLightStatusBar">false</item>
                    <item name="android:windowLightNavigationBar">false</item>
                </style>
            </resources>
            """,
        )
        write(
            root,
            "app/src/main/res/layout/activity_main.xml",
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="match_parent" android:layout_height="match_parent" android:gravity="center" android:orientation="vertical">
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Built with NextCraft Forge" android:textSize="22sp" />
            </LinearLayout>
            """,
        )
        write(
            root,
            "app/src/main/java/$packagePath/MainActivity.kt",
            """
            package $namespace

            import android.os.Bundle
            import androidx.appcompat.app.AppCompatActivity

            class MainActivity : AppCompatActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(R.layout.activity_main)
                }
            }
            """,
        )
    }

    private fun write(root: File, relative: String, content: String) {
        val rootPath = root.canonicalFile.toPath()
        val target = File(root, relative)
        target.parentFile?.mkdirs()
        require(!Files.isSymbolicLink(target.toPath())) { "Refusing to write template through symlink" }
        require(target.canonicalFile.toPath().startsWith(rootPath)) { "Template path escapes root" }
        target.writeText(content.trimIndent().trim() + "\n")
    }

    private fun kotlinString(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("$", "\\$").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")}\""

    private fun xmlEscape(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
```

## 2. `app/src/main/java/com/nextcraft/forge/runtime/AndroidBuildSupport.kt` (338 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class AndroidBuildPhase { IDLE, PREPARING, BUILDING, CANCELLING, SUCCEEDED, FAILED, CANCELLED }
enum class AndroidAction { NONE, BUILD, FORCE_BUILD, INSTALL, RUN, BUILD_AND_RUN, CLEAN }
enum class AndroidBuildStage {
    IDLE, PREPARING, RESOLVING, COMPILING, RESOURCES, PACKAGING, FINDING_APK,
    INSTALLING, LAUNCHING, COMPLETE, FAILED, CANCELLING, CANCELLED,
}
enum class AndroidIssueSeverity { INFO, WARNING, ERROR }

data class AndroidBuildIssue(
    val severity: AndroidIssueSeverity = AndroidIssueSeverity.ERROR,
    val title: String,
    val detail: String,
    val suggestion: String? = null,
    val filePath: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

data class AndroidApkInfo(
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val builtAtMillis: Long,
    val packageName: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val stale: Boolean = false,
    val installed: Boolean = false,
    val installedMatches: Boolean = false,
)

enum class AndroidHealthStatus { RUNNING, PASSED, WARNING, FAILED }
enum class AndroidHealthFix {
    NONE, INSTALL_TOOLS, OPEN_INSTALL_SETTINGS, OPEN_NETWORK_SETTINGS,
    OPEN_STORAGE_SETTINGS, CLEAN, COPY_LOGCAT_COMMAND, REFRESH,
}

data class AndroidHealthCheck(
    val id: String,
    val title: String,
    val detail: String,
    val status: AndroidHealthStatus,
    val fix: AndroidHealthFix = AndroidHealthFix.NONE,
)

enum class AndroidLogLevel { VERBOSE, DEBUG, INFO, WARNING, ERROR, FATAL }

data class AndroidLogLine(
    val id: Long,
    val text: String,
    val level: AndroidLogLevel,
    val filePath: String? = null,
    val line: Int? = null,
)

data class AndroidLogcatState(
    val available: Boolean = false,
    val running: Boolean = false,
    val paused: Boolean = false,
    val message: String? = null,
    val packageName: String? = null,
    val lines: List<AndroidLogLine> = emptyList(),
    val minimumLevel: AndroidLogLevel = AndroidLogLevel.VERBOSE,
    val query: String = "",
)

data class AndroidBuildRecord(
    val phase: AndroidBuildPhase = AndroidBuildPhase.IDLE,
    val message: String? = null,
    val log: String = "",
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val apkPath: String? = null,
    val apkSizeBytes: Long? = null,
    val action: AndroidAction = AndroidAction.NONE,
    val stage: AndroidBuildStage = AndroidBuildStage.IDLE,
    val issues: List<AndroidBuildIssue> = emptyList(),
    val recentDurationsMillis: List<Long> = emptyList(),
)

private val SAFE_GRADLE_TASK_REGEX = Regex("^[A-Za-z0-9_.:-]+(\\s+[A-Za-z0-9_.:-]+)*$")

internal fun androidGradleCommand(root: File, task: String): String {
    val cleanTask = task.trim()
    require(cleanTask.isNotEmpty() && SAFE_GRADLE_TASK_REGEX.matches(cleanTask)) {
        "Unsafe Gradle task name: '$task'"
    }
    val launcher = File(root, "gradlew")
        .takeIf { it.isFile && !Files.isSymbolicLink(it.toPath()) }
        ?.let { "bash ./gradlew" }
        ?: "gradle"
    return "$launcher -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 " +
        "--init-script /root/.gradle/init.d/pocketdev-android.gradle " +
        "-Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 " +
        "$cleanTask --console=plain --stacktrace"
}

internal fun findAndroidProjectRoot(workspace: File): File? {
    if (!workspace.isDirectory) return null
    val workspacePath = runCatching { workspace.canonicalFile.toPath() }.getOrNull() ?: return null
    val settingsNames = setOf("settings.gradle", "settings.gradle.kts", "settings.gradle.dcl")
    return workspace.walkTopDown()
        .onEnter { dir ->
            dir == workspace || (
                !Files.isSymbolicLink(dir.toPath()) &&
                    runCatching { dir.canonicalFile.toPath().startsWith(workspacePath) }.getOrDefault(false)
                )
        }
        .maxDepth(4)
        .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name in settingsNames }
        .mapNotNull(File::getParentFile)
        .sortedBy { it.absolutePath.length }
        .firstOrNull { root ->
            val rootPath = runCatching { root.canonicalFile.toPath() }.getOrNull() ?: return@firstOrNull false
            root.walkTopDown()
                .onEnter { dir ->
                    dir == root || (
                        !Files.isSymbolicLink(dir.toPath()) &&
                            runCatching { dir.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                        )
                }
                .maxDepth(5)
                .any {
                    it.isFile &&
                        !Files.isSymbolicLink(it.toPath()) &&
                        it.invariantSeparatorsPath.endsWith("src/main/AndroidManifest.xml")
                }
        }
}

internal fun findDebugApk(projectRoot: File): File? {
    if (!projectRoot.isDirectory) return null
    val rootPath = runCatching { projectRoot.canonicalFile.toPath() }.getOrNull() ?: return null
    val metadata = projectRoot.walkTopDown()
        .onEnter { dir ->
            dir == projectRoot || (
                !Files.isSymbolicLink(dir.toPath()) &&
                    runCatching { dir.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                )
        }
        .maxDepth(8)
        .filter {
            it.isFile &&
                !Files.isSymbolicLink(it.toPath()) &&
                it.name == "output-metadata.json" &&
                "/outputs/apk/" in it.invariantSeparatorsPath
        }
        .maxByOrNull(File::lastModified)
    if (metadata != null) {
        runCatching {
            val json = JSONObject(metadata.readText())
            val elements = json.optJSONArray("elements") ?: return@runCatching null
            (0 until elements.length()).asSequence()
                .mapNotNull { elements.optJSONObject(it)?.optString("outputFile") }
                .filter { it.isNotBlank() && !it.contains('\u0000') }
                .map { File(metadata.parentFile, it) }
                .firstOrNull { candidate ->
                    candidate.isFile &&
                        !Files.isSymbolicLink(candidate.toPath()) &&
                        candidate.length() > 0L &&
                        runCatching { candidate.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                }
        }.getOrNull()?.let { return it }
    }
    return projectRoot.walkTopDown()
        .onEnter { dir ->
            dir == projectRoot || (
                !Files.isSymbolicLink(dir.toPath()) &&
                    runCatching { dir.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                )
        }
        .maxDepth(10)
        .filter {
            it.isFile &&
                !Files.isSymbolicLink(it.toPath()) &&
                it.extension.equals("apk", true) &&
                "/outputs/apk/debug/" in it.invariantSeparatorsPath &&
                runCatching { it.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
        }
        .maxByOrNull(File::lastModified)
}

/** Returns the last debug APK only when every build input is older than it. */
internal fun findReusableDebugApk(projectRoot: File): File? {
    val apk = findDebugApk(projectRoot) ?: return null
    val rootPath = runCatching { projectRoot.canonicalFile.toPath() }.getOrNull() ?: return null
    val ignoredDirectories = setOf("build", ".gradle", ".idea", ".git")
    val buildInputExtensions = setOf(
        "kt", "java", "xml", "gradle", "kts", "properties", "toml", "pro", "json", "jar", "aar",
    )
    val changedAfterApk = projectRoot.walkTopDown()
        .onEnter { directory ->
            directory == projectRoot || (
                directory.name !in ignoredDirectories &&
                    !Files.isSymbolicLink(directory.toPath()) &&
                    runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                )
        }
        .filter { file ->
            if (!file.isFile || Files.isSymbolicLink(file.toPath())) return@filter false
            val relative = file.relativeTo(projectRoot).invariantSeparatorsPath
            val isAndroidSource = relative.startsWith("src/") || "/src/" in relative
            isAndroidSource || file.extension.lowercase() in buildInputExtensions ||
                file.name in setOf("gradlew", "gradlew.bat")
        }
        .any { it.lastModified() > apk.lastModified() }
    return apk.takeUnless { changedAfterApk }
}

internal fun isDebugApkStale(projectRoot: File, apk: File): Boolean = findReusableDebugApk(projectRoot) != apk

internal fun inferAndroidBuildStage(output: String, current: AndroidBuildStage): AndroidBuildStage {
    val text = output.lowercase(Locale.US)
    return when {
        "packagedebug" in text || ("assembledebug" in text && "> task" in text) -> AndroidBuildStage.PACKAGING
        ("merge" in text && "resource" in text) || "processdebugresources" in text || "aapt2" in text -> AndroidBuildStage.RESOURCES
        "compiledebugkotlin" in text || "compiledebugjava" in text || "kotlinc" in text || "javac" in text -> AndroidBuildStage.COMPILING
        "download" in text || "resolve" in text || "dependency" in text -> AndroidBuildStage.RESOLVING
        else -> current
    }
}

internal fun parseAndroidBuildIssues(output: String, projectRoot: File, exitCode: Int): List<AndroidBuildIssue> {
    val issues = mutableListOf<AndroidBuildIssue>()
    val location = Regex("(?m)^(.+?\\.(?:kt|java|xml|gradle|kts)):(\\d+)(?::(\\d+))?[: ]+(?:error: )?(.+)$", RegexOption.IGNORE_CASE)
    val rootCanonical = runCatching { projectRoot.canonicalFile }.getOrDefault(projectRoot)
    location.findAll(output).take(12).forEach { match ->
        val rawPath = match.groupValues[1].trim()
            .replace(Regex("^(?:e:|w:|error:|warning:)\\s*", RegexOption.IGNORE_CASE), "")
            .removePrefix("file://")
            .trim()
        val file = File(rawPath)
        val relative = when {
            file.isAbsolute && runCatching { file.canonicalFile.toPath().startsWith(rootCanonical.toPath()) }.getOrDefault(false) ->
                file.canonicalFile.relativeTo(rootCanonical).invariantSeparatorsPath
            rawPath.startsWith("/workspace/") -> rawPath.substringAfter("/workspace/").substringAfter('/', "")
            else -> rawPath.takeIf {
                val candidate = File(projectRoot, it)
                candidate.isFile && runCatching { candidate.canonicalFile.toPath().startsWith(rootCanonical.toPath()) }.getOrDefault(false)
            }
        }
        issues += AndroidBuildIssue(
            title = "Source error",
            detail = match.groupValues[4].trim().take(400),
            suggestion = "Open the file and correct the reported line.",
            filePath = relative,
            line = match.groupValues[2].toIntOrNull(),
            column = match.groupValues[3].toIntOrNull(),
        )
    }
    if (issues.isNotEmpty()) return issues.distinctBy { Triple(it.filePath, it.line, it.detail) }
    val summary = diagnoseAndroidBuildFailure(output, exitCode)
    val lower = output.lowercase(Locale.US)
    val suggestion = when {
        "no space left" in lower -> "Free device storage, then retry the build."
        "could not resolve" in lower || "could not get resource" in lower -> "Check the network connection or use a cached dependency version."
        "manifest merger failed" in lower -> "Review duplicate or conflicting manifest declarations."
        "aapt2" in lower || "resource linking failed" in lower -> "Review resource names and XML diagnostics in the build output."
        "outofmemory" in lower || "heap space" in lower -> "Close other apps and retry."
        else -> "Open the build details for the complete Gradle output."
    }
    return listOf(AndroidBuildIssue(title = "Build failed", detail = summary, suggestion = suggestion))
}

internal fun diagnoseAndroidBuildFailure(output: String, exitCode: Int): String {
    val text = output.lowercase(Locale.US)
    return when {
        "no space left on device" in text -> "The device does not have enough free storage for this build."
        "outofmemoryerror" in text || "java heap space" in text -> "Gradle ran out of memory. Close other apps and retry."
        "could not resolve" in text || "could not get resource" in text -> "A project dependency is unavailable. Connect to the internet or use an offline-cached version."
        "manifest merger failed" in text -> "Android manifest merge failed. Open the build log for the conflicting declaration."
        "aapt2" in text || "android resource linking failed" in text -> "Android resources could not be compiled. Open the build log for the file and line."
        "compilation error" in text || "compilation failed" in text || "unresolved reference" in text -> "Kotlin or Java compilation failed. Open the build log for source errors."
        "minimum supported gradle version" in text || "maximum supported gradle" in text -> "This project's Gradle and Android plugin versions are incompatible."
        else -> "Gradle build failed (exit code $exitCode)."
    }
}

/**
 * Checks whether the host has either `READ_LOGS` permission or working Root (`su`) access
 * for streaming package-scoped `logcat`.
 */
internal fun hasAndroidLogcatAccess(context: Context): Boolean {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) {
        return true
    }
    return runCatching {
        val process = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
        val finished = process.waitFor(1_500L, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching false
        }
        process.exitValue() == 0
    }.getOrDefault(false)
}

/**
 * Starts a UID-scoped `logcat` process for [packageName] using Root (`su -c`) when
 * `READ_LOGS` is not held directly by the app.
 */
internal fun startPackageLogcatProcess(
    context: Context,
    packageName: String,
    sinceMillis: Long? = null,
): Process {
    val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
    require(uid > 0) { "Invalid application UID for $packageName" }
    val since = sinceMillis?.takeIf { it > 0L }?.let {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(it))
    } ?: "1"
    val useRoot = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED
    return if (useRoot) {
        val safeSince = since.replace("'", "")
        val logCommand = "/system/bin/logcat --uid=$uid -v threadtime -T '$safeSince'"
        ProcessBuilder("su", "-c", logCommand).redirectErrorStream(true).start()
    } else {
        ProcessBuilder("/system/bin/logcat", "--uid=$uid", "-v", "threadtime", "-T", since)
            .redirectErrorStream(true)
            .start()
    }
}
```

## 3. `app/src/main/java/com/nextcraft/forge/runtime/AndroidAppInstaller.kt` (273 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

data class AndroidInstallEvent(
    val operationId: String,
    val packageName: String?,
    val success: Boolean,
    val launched: Boolean,
    val message: String? = null,
)

/**
 * Installs a locally-built APK on the device.
 *
 * On rooted devices (NextCraft Forge's primary target), attempts a 1-tap silent
 * `su -c "pm install -r -d -S <size>"` stream install first (excluding self-updates
 * of NextCraft Forge itself), and automatically falls back to Android's standard
 * [PackageInstaller.Session] + [AndroidAppInstallReceiver] flow if root install fails.
 */
object AndroidAppInstaller {
    private val _events = MutableSharedFlow<AndroidInstallEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    fun openIfAlreadyInstalled(context: Context, apk: File): Boolean {
        if (!apk.isFile || Files.isSymbolicLink(apk.toPath())) return false
        val packageName = archivePackageName(context, apk) ?: return false
        if (installedFingerprint(context, packageName) != fingerprint(apk)) return false
        return launch(context, packageName)
    }

    fun install(
        context: Context,
        apk: File,
        launchAfterInstall: Boolean = true,
        operationId: String = UUID.randomUUID().toString(),
    ): String {
        require(
            apk.isFile &&
                !Files.isSymbolicLink(apk.toPath()) &&
                apk.extension.equals("apk", ignoreCase = true) &&
                apk.length() > 0L,
        ) {
            "APK file '${apk.name}' is missing or invalid."
        }
        val appContext = context.applicationContext ?: context
        val pkgName = archivePackageName(appContext, apk)
        val isSelfUpdate = pkgName == appContext.packageName

        Thread({
            val apkHash = runCatching { fingerprint(apk) }.getOrNull()
            if (!isSelfUpdate && pkgName != null && apkHash != null && installSilentlyViaRoot(apk)) {
                rememberInstalled(appContext, pkgName, apkHash)
                val launched = launchAfterInstall && launch(appContext, pkgName)
                publish(AndroidInstallEvent(operationId, pkgName, success = true, launched = launched))
                return@Thread
            }
            runCatching {
                installViaSessionOrIntent(appContext, apk, pkgName, apkHash ?: fingerprint(apk), launchAfterInstall, operationId)
            }.onFailure { error ->
                publish(
                    AndroidInstallEvent(
                        operationId = operationId,
                        packageName = pkgName,
                        success = false,
                        launched = false,
                        message = error.message ?: "APK installation failed.",
                    ),
                )
            }
        }, "forge-apk-installer").apply { isDaemon = true }.start()
        return operationId
    }

    /**
     * Streams [apk] over stdin to `su -c "pm install -r -d -S <bytes>"` so `system_server`
     * never needs direct SELinux filesystem access to the app's private data directory.
     */
    internal fun installSilentlyViaRoot(apk: File): Boolean = runCatching {
        val sizeBytes = apk.length()
        if (sizeBytes <= 0L) return@runCatching false
        val proc = ProcessBuilder("su", "-c", "pm install -r -d -S $sizeBytes")
            .redirectErrorStream(true)
            .start()
        try {
            apk.inputStream().buffered().use { input ->
                proc.outputStream.use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            if (!proc.waitFor(60L, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return@runCatching false
            }
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            proc.exitValue() == 0 && out.contains("Success", ignoreCase = true)
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
        }
    }.getOrDefault(false)

    private fun installViaSessionOrIntent(
        context: Context,
        apk: File,
        packageName: String?,
        apkHash: String,
        launchAfterInstall: Boolean,
        operationId: String,
    ) {
        if (isMiuiDevice()) {
            val uri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
            }.getOrElse {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            }
            @Suppress("DEPRECATION")
            context.startActivity(
                Intent(Intent.ACTION_INSTALL_PACKAGE, uri).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            publish(
                AndroidInstallEvent(
                    operationId = operationId,
                    packageName = packageName,
                    success = true,
                    launched = false,
                    message = "Opened system package installer.",
                ),
            )
            return
        }
        val installer = context.packageManager.packageInstaller
        val isUpdate = packageName?.let { isInstalled(context, it) } == true
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setSize(apk.length())
                setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                setOriginatingUid(Process.myUid())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(
                        if (isUpdate) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                        else PackageInstaller.SessionParams.USER_ACTION_REQUIRED,
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
                }
            }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(apk.name, 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callback = Intent(context, AndroidAppInstallReceiver::class.java)
                    .setAction(ACTION_INSTALL_RESULT)
                    .setPackage(context.packageName)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                    .putExtra(EXTRA_APK_FINGERPRINT, apkHash)
                    .putExtra(EXTRA_OPERATION_ID, operationId)
                    .putExtra(EXTRA_LAUNCH_AFTER_INSTALL, launchAfterInstall)
                val mutabilityFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag,
                )
                session.commit(pending.intentSender)
            }
        } catch (error: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    const val ACTION_INSTALL_RESULT = "com.nextcraft.forge.action.APK_INSTALL_RESULT"
    const val EXTRA_APK_FINGERPRINT = "com.nextcraft.forge.extra.APK_FINGERPRINT"
    const val EXTRA_OPERATION_ID = "com.nextcraft.forge.extra.OPERATION_ID"
    const val EXTRA_LAUNCH_AFTER_INSTALL = "com.nextcraft.forge.extra.LAUNCH_AFTER_INSTALL"

    internal fun publish(event: AndroidInstallEvent) {
        _events.tryEmit(event)
    }

    fun packageName(context: Context, apk: File): String? = archivePackageName(context, apk)

    fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    fun installedMatches(context: Context, apk: File): Boolean {
        if (!apk.isFile || Files.isSymbolicLink(apk.toPath())) return false
        val packageName = archivePackageName(context, apk) ?: return false
        return installedFingerprint(context, packageName) == fingerprint(apk)
    }

    fun apkFingerprint(apk: File): String = fingerprint(apk)

    internal fun rememberInstalled(context: Context, packageName: String, fingerprint: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(packageName, fingerprint)
            .apply()
    }

    internal fun launch(context: Context, packageName: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getLaunchIntentSenderForPackage(packageName).sendIntent(
                context, 0, null, null, null,
            )
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
        true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun archivePackageName(context: Context, apk: File): String? =
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName

    private fun installedFingerprint(context: Context, packageName: String): String? =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(packageName, null)

    private fun fingerprint(apk: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private const val PREFERENCES = "installed_android_apks"

    private fun isMiuiDevice(): Boolean = Build.MANUFACTURER.lowercase(Locale.US) in
        setOf("xiaomi", "redmi", "poco")
}
```

## 4. `app/src/main/java/com/nextcraft/forge/runtime/AndroidAppInstallReceiver.kt` (51 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

class AndroidAppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidAppInstaller.ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val operationId = intent.getStringExtra(AndroidAppInstaller.EXTRA_OPERATION_ID).orEmpty()
        val launchAfterInstall = intent.getBooleanExtra(AndroidAppInstaller.EXTRA_LAUNCH_AFTER_INSTALL, true)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val userAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (userAction != null) {
                // Prevent untrusted broadcast extras from targeting internal app components
                if (userAction.`package` == context.packageName || userAction.component?.packageName == context.packageName) {
                    return
                }
                userAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(userAction) }
            }
            return
        }
        if (status != PackageInstaller.STATUS_SUCCESS) {
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                ?.takeIf(String::isNotBlank)
                ?: "APK installation failed."
            AndroidAppInstaller.publish(AndroidInstallEvent(operationId, null, false, false, message))
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return
        }
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: return
        intent.getStringExtra(AndroidAppInstaller.EXTRA_APK_FINGERPRINT)?.let { fingerprint ->
            AndroidAppInstaller.rememberInstalled(context, packageName, fingerprint)
        }
        val launched = launchAfterInstall && AndroidAppInstaller.launch(context, packageName)
        AndroidAppInstaller.publish(AndroidInstallEvent(operationId, packageName, true, launched))
        if (launchAfterInstall && !launched) {
            Toast.makeText(context, "Installed $packageName. Open it from the launcher.", Toast.LENGTH_LONG).show()
        }
    }
}
```

## 5. `app/src/main/java/com/nextcraft/forge/runtime/RuntimeExecutionService.kt` (232 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class RuntimeExecutionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var projectNameOverride: String? = null
    private var notificationTitleOverride: String? = null
    private val projectName: String
        get() = projectNameOverride ?: "Forge Workspace"
    private val notificationTitle: String
        get() = notificationTitleOverride ?: "Forge Runtime Active"
    private var canStop: Boolean = true
    private var taskRunning: Boolean = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        val incomingProjectName = intent?.getStringExtra(EXTRA_PROJECT_NAME)
            ?: intent?.getStringExtra(LEGACY_EXTRA_PROJECT_NAME)
        val incomingTitle = intent?.getStringExtra(EXTRA_TITLE)
            ?: intent?.getStringExtra(LEGACY_EXTRA_TITLE)
        val incomingDetail = intent?.getStringExtra(EXTRA_DETAIL)
            ?: intent?.getStringExtra(LEGACY_EXTRA_DETAIL)

        incomingProjectName?.takeIf(String::isNotBlank)?.let { projectNameOverride = it }

        when (action) {
            ACTION_STOP, LEGACY_ACTION_STOP -> {
                RuntimeTaskController.requestStop()
                getSystemService(NotificationManager::class.java)?.notify(
                    RUNNING_NOTIFICATION_ID,
                    runningNotification("Stopping active task…", includeStop = false),
                )
            }
            ACTION_PROGRESS, LEGACY_ACTION_PROGRESS -> {
                if (!taskRunning) return START_NOT_STICKY
                incomingTitle?.takeIf(String::isNotBlank)?.let { notificationTitleOverride = it }
                if (intent?.hasExtra(EXTRA_CAN_STOP) == true) {
                    canStop = intent.getBooleanExtra(EXTRA_CAN_STOP, true)
                } else if (intent?.hasExtra(LEGACY_EXTRA_CAN_STOP) == true) {
                    canStop = intent.getBooleanExtra(LEGACY_EXTRA_CAN_STOP, true)
                }
                val detail = incomingDetail?.takeIf(String::isNotBlank)
                    ?: "Working in $projectName"
                getSystemService(NotificationManager::class.java)?.notify(
                    RUNNING_NOTIFICATION_ID,
                    runningNotification(detail, includeStop = canStop),
                )
            }
            ACTION_COMPLETE, LEGACY_ACTION_COMPLETE -> finishTask(
                title = "Task completed",
                detail = incomingDetail?.takeIf(String::isNotBlank) ?: "Finished task in $projectName",
                failed = false,
            )
            ACTION_FAILED, LEGACY_ACTION_FAILED -> finishTask(
                title = "Task needs attention",
                detail = incomingDetail?.takeIf(String::isNotBlank) ?: "Could not finish the active task.",
                failed = true,
            )
            ACTION_CANCELLED, LEGACY_ACTION_CANCELLED -> {
                taskRunning = false
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                // Reset per-task overrides on fresh start so previous non-stoppable or custom-titled
                // tasks never bleed into subsequent coding sessions.
                notificationTitleOverride = incomingTitle?.takeIf(String::isNotBlank)
                canStop = when {
                    intent?.hasExtra(EXTRA_CAN_STOP) == true -> intent.getBooleanExtra(EXTRA_CAN_STOP, true)
                    intent?.hasExtra(LEGACY_EXTRA_CAN_STOP) == true -> intent.getBooleanExtra(LEGACY_EXTRA_CAN_STOP, true)
                    else -> true
                }
                taskRunning = true
                val detail = incomingDetail?.takeIf(String::isNotBlank)
                    ?: "Working in $projectName"
                startForeground(
                    RUNNING_NOTIFICATION_ID,
                    runningNotification(detail, includeStop = canStop),
                )
                acquireWakeLock()
            }
        }
        return START_NOT_STICKY
    }

    private fun runningNotification(detail: String, includeStop: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, RUNNING_CHANNEL_ID)
            .setSmallIcon(smallIconRes())
            .setContentTitle(notificationTitle)
            .setContentText(detail)
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (includeStop) {
            val stopIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, RuntimeExecutionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Stop task", stopIntent)
        }
        return builder.build()
    }

    private fun finishTask(title: String, detail: String, failed: Boolean) {
        taskRunning = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setSmallIcon(smallIconRes())
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setCategory(if (failed) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(RESULT_NOTIFICATION_ID, notification)
        stopSelf()
    }

    private fun smallIconRes(): Int =
        applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.stat_notify_sync

    private fun openAppIntent(): PendingIntent {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: runCatching { Intent(this, Class.forName("$packageName.MainActivity")) }.getOrNull()
            ?: runCatching { Intent(this, Class.forName("com.nextcraft.forge.MainActivity")) }.getOrNull()
            ?: Intent().setPackage(packageName)
        launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            this,
            1,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.nextcraft.forge:active-coding-task")
            ?.apply { acquire(MAX_WAKE_LOCK_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.let { lock ->
            runCatching { lock.release() }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = RuntimeExecutionServiceContract.ACTION_START
        const val ACTION_STOP = "com.nextcraft.forge.runtime.action.STOP"
        const val ACTION_PROGRESS = RuntimeExecutionServiceContract.ACTION_PROGRESS
        const val ACTION_COMPLETE = RuntimeExecutionServiceContract.ACTION_COMPLETE
        const val ACTION_FAILED = RuntimeExecutionServiceContract.ACTION_FAILED
        const val ACTION_CANCELLED = RuntimeExecutionServiceContract.ACTION_CANCELLED

        const val EXTRA_PROJECT_NAME = RuntimeExecutionServiceContract.EXTRA_PROJECT_NAME
        const val EXTRA_DETAIL = RuntimeExecutionServiceContract.EXTRA_DETAIL
        const val EXTRA_TITLE = "com.nextcraft.forge.runtime.extra.TITLE"
        const val EXTRA_CAN_STOP = "com.nextcraft.forge.runtime.extra.CAN_STOP"

        private const val LEGACY_ACTION_STOP = "com.nextcraft.forge.STOP_RUNTIME"
        private const val LEGACY_ACTION_PROGRESS = "com.nextcraft.forge.PROGRESS_RUNTIME"
        private const val LEGACY_ACTION_COMPLETE = "com.nextcraft.forge.COMPLETE_RUNTIME"
        private const val LEGACY_ACTION_FAILED = "com.nextcraft.forge.FAIL_RUNTIME"
        private const val LEGACY_ACTION_CANCELLED = "com.nextcraft.forge.CANCEL_RUNTIME"
        private const val LEGACY_EXTRA_PROJECT_NAME = "project_name"
        private const val LEGACY_EXTRA_DETAIL = "detail"
        private const val LEGACY_EXTRA_TITLE = "title"
        private const val LEGACY_EXTRA_CAN_STOP = "can_stop"

        private const val RUNNING_CHANNEL_ID = "runtime"
        private const val RESULT_CHANNEL_ID = "task-results"
        private const val RUNNING_NOTIFICATION_ID = 41
        private const val RESULT_NOTIFICATION_ID = 42
        private const val MAX_WAKE_LOCK_MS = 90 * 60 * 1_000L

        fun ensureNotificationChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    RUNNING_CHANNEL_ID,
                    "Active coding tasks",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows live progress while an agent or build task is running."
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    RESULT_CHANNEL_ID,
                    "Task results",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Notifies when background coding or build tasks complete."
                },
            )
        }
    }
}
```

## 6. `app/src/main/java/com/nextcraft/forge/runtime/RuntimeSetupService.kt` (425 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.nextcraft.forge.data.AppPreferences
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.DevStack
import java.io.File
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class RuntimeSetupStatus { IDLE, RUNNING, COMPLETE, ERROR, CANCELLED }

data class RuntimeSetupSnapshot(
    val status: RuntimeSetupStatus = RuntimeSetupStatus.IDLE,
    val message: String = "Preparing your private coding workspace",
    val progress: Float = 0f,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val indeterminate: Boolean = false,
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null,
    val offline: Boolean = false,
)

object RuntimeSetupController {
    private val mutableSnapshot = MutableStateFlow(RuntimeSetupSnapshot())
    val snapshot: StateFlow<RuntimeSetupSnapshot> = mutableSnapshot.asStateFlow()

    @Synchronized
    fun restore(context: Context) {
        val file = stateFile(context)
        if (!file.isFile) return
        runCatching {
            val json = JSONObject(file.readText())
            val logsJson = json.optJSONArray("logs") ?: JSONArray()
            val logs = (0 until logsJson.length()).map { logsJson.optString(it) }
            val rawProgress = json.optDouble("progress", 0.0).toFloat()
            val safeProgress = if (rawProgress.isFinite()) rawProgress.coerceIn(0f, 1f) else 0f
            mutableSnapshot.value = RuntimeSetupSnapshot(
                status = runCatching { RuntimeSetupStatus.valueOf(json.optString("status")) }
                    .getOrDefault(RuntimeSetupStatus.IDLE),
                message = json.optString("message", DEFAULT_PREPARING_MESSAGE).ifBlank { DEFAULT_PREPARING_MESSAGE },
                progress = safeProgress,
                downloadedBytes = json.optLongOrNull("downloadedBytes"),
                totalBytes = json.optLongOrNull("totalBytes"),
                indeterminate = json.optBoolean("indeterminate"),
                logs = logs.takeLast(MAX_LOG_LINES),
                errorMessage = json.optString("errorMessage").takeIf(String::isNotBlank),
                offline = json.optBoolean("offline"),
            )
        }
    }

    @Synchronized
    fun begin(context: Context) {
        val previous = mutableSnapshot.value.logs
        val logs = (previous + "— Resuming NextCraft Forge setup —").takeLast(MAX_LOG_LINES)
        set(
            context,
            RuntimeSetupSnapshot(
                status = RuntimeSetupStatus.RUNNING,
                message = DEFAULT_PREPARING_MESSAGE,
                progress = 0.01f,
                logs = logs,
            ),
        )
    }

    @Synchronized
    fun update(context: Context, event: RuntimeInstallProgress) {
        val current = mutableSnapshot.value
        val line = event.terminalLine ?: when {
            event.downloadedBytes != null && event.totalBytes != null ->
                "Downloading: %.1f / %.1f MB".format(event.downloadedBytes / MB, event.totalBytes / MB)
            event.event == RuntimeInstallEvent.STAGE -> "• ${event.message}"
            else -> null
        }
        val sanitizedLine = line?.takeIf(String::isNotBlank)?.let(::sanitize)
        val isDownloadUpdate = event.downloadedBytes != null && event.totalBytes != null
        val logs = when {
            sanitizedLine == null -> current.logs
            isDownloadUpdate && current.logs.lastOrNull()?.startsWith(DOWNLOAD_PREFIX) == true ->
                (current.logs.dropLast(1) + sanitizedLine).takeLast(MAX_LOG_LINES)
            else -> (current.logs + sanitizedLine).takeLast(MAX_LOG_LINES)
        }
        if (!isDownloadUpdate || current.logs.lastOrNull()?.startsWith(DOWNLOAD_PREFIX) != true) {
            appendLog(context, sanitizedLine)
        }
        val safeFraction = if (event.fraction.isFinite()) event.fraction.coerceIn(0f, 1f) else current.progress
        set(
            context,
            current.copy(
                status = RuntimeSetupStatus.RUNNING,
                message = when (event.event) {
                    RuntimeInstallEvent.STAGE, RuntimeInstallEvent.DOWNLOAD -> event.message
                    else -> current.message
                },
                progress = maxOf(current.progress, safeFraction),
                downloadedBytes = event.downloadedBytes,
                totalBytes = event.totalBytes,
                indeterminate = event.indeterminate,
                logs = logs,
                errorMessage = null,
                offline = false,
            ),
        )
    }

    @Synchronized
    fun complete(context: Context) {
        val current = mutableSnapshot.value
        set(
            context,
            current.copy(
                status = RuntimeSetupStatus.COMPLETE,
                message = "Linux chroot runtime is ready",
                progress = 1f,
                indeterminate = false,
                downloadedBytes = null,
                totalBytes = null,
                logs = (current.logs + "✓ Setup completed successfully").takeLast(MAX_LOG_LINES),
            ),
        )
    }

    @Synchronized
    fun fail(context: Context, error: Throwable) {
        val causes = generateSequence(error as Throwable?) { it.cause }.toList()
        val offline = causes.any {
            it is UnknownHostException ||
                it.message.orEmpty().contains("unable to resolve host", true) ||
                it.message.orEmpty().contains("no address associated with hostname", true)
        }
        val interruptedDpkg = causes.any {
            it.message.orEmpty().contains("dpkg was interrupted", true) ||
                it.message.orEmpty().contains("dpkg --configure -a", true)
        }
        val friendly = when {
            offline -> "No internet connection. Connect to Wi-Fi or mobile data and retry."
            interruptedDpkg -> "Package installation was interrupted. Retry setup to repair automatically."
            else -> error.message.orEmpty().lineSequence().lastOrNull { it.isNotBlank() }
                ?.take(220)
                ?: "Runtime setup could not complete."
        }
        val current = mutableSnapshot.value
        set(
            context,
            current.copy(
                status = RuntimeSetupStatus.ERROR,
                errorMessage = friendly,
                offline = offline,
                indeterminate = false,
                logs = (current.logs + "✕ $friendly").takeLast(MAX_LOG_LINES),
            ),
        )
    }

    @Synchronized
    fun cancelled(context: Context) {
        val current = mutableSnapshot.value
        set(
            context,
            current.copy(
                status = RuntimeSetupStatus.CANCELLED,
                message = "Runtime setup paused",
                indeterminate = false,
                logs = (current.logs + "• Setup paused safely").takeLast(MAX_LOG_LINES),
            ),
        )
    }

    fun fullLog(context: Context): String = logFile(context).readTailText(MAX_LOG_BYTES.toInt())

    private fun set(context: Context, value: RuntimeSetupSnapshot) {
        mutableSnapshot.value = value
        persist(context, value)
    }

    private fun persist(context: Context, value: RuntimeSetupSnapshot) {
        runCatching {
            val safeProgress = if (value.progress.isFinite()) value.progress.coerceIn(0f, 1f) else 0f
            val json = JSONObject()
                .put("status", value.status.name)
                .put("message", value.message)
                .put("progress", safeProgress.toDouble())
                .put("indeterminate", value.indeterminate)
                .put("offline", value.offline)
                .put("logs", JSONArray(value.logs))
            value.downloadedBytes?.let { json.put("downloadedBytes", it) }
            value.totalBytes?.let { json.put("totalBytes", it) }
            value.errorMessage?.let { json.put("errorMessage", it) }
            val target = stateFile(context)
            val staged = File(target.parentFile, "${target.name}.tmp")
            staged.writeText(json.toString())
            if (!staged.renameTo(target)) {
                target.delete()
                if (!staged.renameTo(target)) {
                    android.util.Log.e("SetupController", "Failed to persist setup state — data may be lost")
                }
            }
        }
    }

    private fun appendLog(context: Context, line: String?) {
        if (line.isNullOrBlank()) return
        runCatching {
            val file = logFile(context)
            file.parentFile?.mkdirs()
            file.appendText(sanitize(line) + "\n")
            if (file.length() > MAX_LOG_BYTES) {
                val tail = file.readTailText(MAX_LOG_BYTES.toInt())
                file.writeText(tail.substringAfter('\n', tail))
            }
        }
    }

    private fun sanitize(line: String): String = line.filter { it == '\t' || it.code >= 32 }.take(500)
    private fun stateFile(context: Context) = File(context.filesDir, "setup/runtime-setup-state.json").apply { parentFile?.mkdirs() }
    private fun logFile(context: Context) = File(context.filesDir, "setup/runtime-setup.log")
    private fun JSONObject.optLongOrNull(name: String): Long? = if (has(name) && !isNull(name)) optLong(name) else null

    private const val DEFAULT_PREPARING_MESSAGE = "Preparing your private coding workspace"
    private const val MAX_LOG_LINES = 400
    private const val MAX_LOG_BYTES = 1_000_000L
    private const val MB = 1_048_576.0
    private const val DOWNLOAD_PREFIX = "Downloading:"
}

class RuntimeSetupService : Service() {
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var installJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
        RuntimeSetupController.restore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            installJob?.cancel(CancellationException("Stopped by user"))
            RuntimeSetupController.cancelled(this)
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, setupNotification(RuntimeSetupController.snapshot.value))
        acquireWakeLock()
        if (installJob?.isActive != true) {
            val stacks = intent?.getStringExtra(EXTRA_STACKS).orEmpty().split(',')
                .mapNotNull { name -> runCatching { DevStack.valueOf(name.trim()) }.getOrNull() }
                .toSet()
            val agent = AgentKind.fromStored(intent?.getStringExtra(EXTRA_AGENT)?.trim())
            RuntimeSetupController.begin(this)
            installJob = scope.launch {
                try {
                    RuntimeInstaller(this@RuntimeSetupService).ensureInstalled(stacks, agent) { progress ->
                        RuntimeSetupController.update(this@RuntimeSetupService, progress)
                        updateNotification(progress.event == RuntimeInstallEvent.COMMAND_COMPLETED)
                    }
                    AppPreferences(this@RuntimeSetupService).runtimeSetupComplete = true
                    RuntimeSetupController.complete(this@RuntimeSetupService)
                    showFinishedNotification(success = true)
                } catch (_: CancellationException) {
                    RuntimeSetupController.cancelled(this@RuntimeSetupService)
                } catch (error: Throwable) {
                    RuntimeSetupController.fail(this@RuntimeSetupService, error)
                    showFinishedNotification(success = false)
                } finally {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun updateNotification(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationAt < NOTIFICATION_THROTTLE_MS) return
        lastNotificationAt = now
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, setupNotification(RuntimeSetupController.snapshot.value))
    }

    private fun setupNotification(state: RuntimeSetupSnapshot): Notification {
        val latest = state.logs.lastOrNull().orEmpty().take(180)
        val bodyText = latest.ifBlank { state.message }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIconRes())
            .setContentTitle("Setting up Linux chroot runtime")
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (state.indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, (state.progress * 100).toInt().coerceIn(0, 100), false)
        }
        builder.addAction(
            0,
            "Pause",
            PendingIntent.getService(
                this,
                102,
                Intent(this, RuntimeSetupService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return builder.build()
    }

    private fun showFinishedNotification(success: Boolean) {
        val state = RuntimeSetupController.snapshot.value
        val title = if (success) "Linux chroot runtime is ready" else "Runtime setup needs attention"
        val detail = if (success) {
            "Your Ubuntu ARM64 chroot environment and coding tools are ready."
        } else {
            state.errorMessage ?: "Runtime setup encountered an error."
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIconRes())
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun smallIconRes(): Int =
        applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.stat_notify_sync

    private fun openAppIntent(): PendingIntent {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: runCatching { Intent(this, Class.forName("$packageName.MainActivity")) }.getOrNull()
            ?: runCatching { Intent(this, Class.forName("com.nextcraft.forge.MainActivity")) }.getOrNull()
            ?: Intent().setPackage(packageName)
        launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            this,
            101,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.nextcraft.forge:runtime-setup")
            ?.apply { acquire(MAX_WAKE_LOCK_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.let { lock ->
            runCatching { lock.release() }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.nextcraft.forge.START_SETUP"
        const val ACTION_STOP = "com.nextcraft.forge.STOP_SETUP"
        const val EXTRA_STACKS = "selected_stacks"
        const val EXTRA_AGENT = "selected_agent"
        private const val CHANNEL_ID = "runtime-setup"
        private const val NOTIFICATION_ID = 51
        private const val RESULT_NOTIFICATION_ID = 52
        private const val NOTIFICATION_THROTTLE_MS = 750L
        private const val MAX_WAKE_LOCK_MS = 45 * 60 * 1_000L

        fun ensureNotificationChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Runtime setup",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows download and extraction progress during Linux chroot setup."
                },
            )
        }
    }
}
```

## 7. `app/src/main/java/com/nextcraft/forge/network/ProviderApiClient.kt` (364 baris)

```kotlin
package com.nextcraft.forge.network

import android.content.Context
import com.nextcraft.forge.model.ProviderProtocol
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class DiscoveredModel(
    val id: String,
    val displayName: String = id,
    val isFree: Boolean = false,
)

sealed interface ModelDiscoveryResult {
    data class Success(val models: List<DiscoveredModel>, val endpoint: String) : ModelDiscoveryResult
    data class Failure(val message: String, val providerMessage: String? = null) : ModelDiscoveryResult
}

sealed interface ConnectionValidation {
    data class Success(val message: String) : ConnectionValidation
    data class Failure(
        val message: String,
        val providerMessage: String? = null,
        val label: String = "Failed",
    ) : ConnectionValidation
}

class ProviderApiClient(@Suppress("UNUSED_PARAMETER") private val context: Context? = null) {

    suspend fun discoverModels(
        baseUrl: String,
        apiKey: String,
        protocol: ProviderProtocol,
    ): ModelDiscoveryResult = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext ModelDiscoveryResult.Failure("Enter a base URL first.")
        }

        var authError = false
        var lastMessage = "This provider did not expose a model list. You can enter a custom model name."
        var lastProviderMessage: String? = null
        for (endpoint in modelEndpoints(baseUrl, protocol)) {
            // OpenRouter's complete catalog is public. Fetch it anonymously even when
            // OpenRouter is configured through Custom API so an account-scoped key does
            // not reduce discovery to the models allowed by that key's preferences.
            val discoveryKey = if (isOpenRouterCatalogEndpoint(endpoint)) "" else apiKey
            val response = request(endpoint, "GET", discoveryKey, protocol = protocol)
            when {
                response.code == 401 || response.code == 403 -> {
                    authError = true
                    lastProviderMessage = providerErrorMessage(response.body)
                }
                response.code in 200..299 -> {
                    val models = ModelResponseParser.parse(response.body)
                    if (models.isNotEmpty()) return@withContext ModelDiscoveryResult.Success(models, endpoint)
                    lastMessage = "The provider replied, but its model list was empty or unsupported."
                }
                response.code > 0 && response.code != 404 -> {
                    lastMessage = friendlyHttpError(response.code)
                    lastProviderMessage = providerErrorMessage(response.body)
                }
                response.error != null -> lastMessage = response.error
            }
        }
        ModelDiscoveryResult.Failure(
            if (authError) {
                "Check the saved API key, then try refreshing again."
            } else {
                lastMessage
            },
            lastProviderMessage,
        )
    }

    suspend fun validate(
        baseUrl: String,
        model: String,
        apiKey: String,
        protocol: ProviderProtocol,
        @Suppress("UNUSED_PARAMETER") discoveredModels: List<DiscoveredModel> = emptyList(),
        openRouterProviderOrder: String = "",
        openRouterAllowFallbacks: Boolean = true,
    ): ConnectionValidation = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || model.isBlank()) {
            return@withContext ConnectionValidation.Failure("Base URL, model, and API key are required.")
        }
        val endpoint = messagesEndpoint(baseUrl, protocol)
        val body = validationBody(model, protocol, openRouterProviderOrder, openRouterAllowFallbacks)
        val response = request(
            endpoint = endpoint,
            method = "POST",
            apiKey = apiKey,
            body = body,
            protocol = protocol,
            connectTimeoutMs = 12_000,
            readTimeoutMs = 45_000,
        )
        when {
            response.code in 200..299 -> ConnectionValidation.Success("Connection verified.")
            response.code == 401 || response.code == 403 -> ConnectionValidation.Failure(
                "Check this API key or select another saved key.",
                providerErrorMessage(response.body),
                "Rejected",
            )
            response.code == 404 -> ConnectionValidation.Failure(
                "Check the Base URL and selected gateway protocol.",
                providerErrorMessage(response.body),
                "Endpoint error",
            )
            response.code == 400 && response.body.contains("model", ignoreCase = true) ->
                ConnectionValidation.Failure(
                    "Refresh the model list or select a different model.",
                    providerErrorMessage(response.body),
                    "Model error",
                )
            response.code == 429 -> ConnectionValidation.Failure(
                "Wait a moment, then retry or use another API key.",
                providerErrorMessage(response.body),
                "Rate limited",
            )
            response.code in 500..599 -> ConnectionValidation.Failure(
                "The provider is temporarily unavailable. Try again shortly.",
                providerErrorMessage(response.body),
                "Provider error",
            )
            response.code > 0 -> ConnectionValidation.Failure(
                "Review the model, protocol, and endpoint settings.",
                providerErrorMessage(response.body),
                "Request failed",
            )
            response.error?.contains("timeout", ignoreCase = true) == true ||
                response.error?.contains("timed out", ignoreCase = true) == true ->
                ConnectionValidation.Failure(
                    "Check your connection and try again.",
                    response.error,
                    "Timed out",
                )
            else -> ConnectionValidation.Failure(
                "Check your internet connection and provider settings.",
                response.error,
                "Network error",
            )
        }
    }

    private fun request(
        endpoint: String,
        method: String,
        apiKey: String,
        body: String? = null,
        protocol: ProviderProtocol,
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
    ): HttpResult {
        if (!LocalNetwork.isPlainHttpAllowed(endpoint)) {
            return HttpResult(
                0,
                "",
                "Plain http:// is only allowed for localhost. Use https:// for this server.",
            )
        }
        return runCatching {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                if (endpoint.startsWith("https://opencode.ai/zen/")) {
                    setRequestProperty("User-Agent", "opencode/1.18.20")
                    setRequestProperty("x-session-id", "session-${UUID.randomUUID()}")
                }
                if (
                    apiKey.isNotBlank() &&
                    protocol != ProviderProtocol.OPENROUTER &&
                    protocol != ProviderProtocol.OPENAI_CHAT &&
                    protocol != ProviderProtocol.OPENAI_RESPONSES
                ) {
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", "2023-06-01")
                }
                if (body != null) doOutput = true
            }
            try {
                if (body != null) {
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    buildString {
                        val buffer = CharArray(8 * 1024)
                        while (length < MAX_PROVIDER_RESPONSE_CHARS) {
                            val count = reader.read(buffer, 0, minOf(buffer.size, MAX_PROVIDER_RESPONSE_CHARS - length))
                            if (count <= 0) break
                            append(buffer, 0, count)
                        }
                    }
                }.orEmpty()
                HttpResult(code, responseBody)
            } finally {
                connection.disconnect()
            }
        }.getOrElse { HttpResult(0, "", it.message ?: "Network connection failed") }
    }

    private fun modelEndpoints(baseUrl: String, protocol: ProviderProtocol): List<String> {
        val base = baseUrl.trim().trimEnd('/')
        val withoutV1 = base.removeSuffix("/v1")
        val withoutAnthropic = base.removeSuffix("/anthropic")
        val candidates = when (protocol) {
            ProviderProtocol.OPENROUTER -> listOf("$withoutV1/v1/models", "$base/models")
            ProviderProtocol.OPENAI_CHAT, ProviderProtocol.OPENAI_RESPONSES -> listOf("$base/models", "$withoutV1/v1/models")
            else -> listOf("$withoutV1/v1/models", "$base/models", "$withoutAnthropic/models", "$withoutAnthropic/v1/models")
        }
        return candidates.distinct()
    }

    private fun messagesEndpoint(baseUrl: String, protocol: ProviderProtocol): String {
        val base = baseUrl.trim().trimEnd('/')
        val withoutV1 = base.removeSuffix("/v1")
        return when (protocol) {
            ProviderProtocol.OPENROUTER -> "$withoutV1/v1/messages"
            ProviderProtocol.OPENAI_CHAT -> "${base.removeSuffix("/chat/completions")}/chat/completions"
            ProviderProtocol.OPENAI_RESPONSES -> "${base.removeSuffix("/responses")}/responses"
            else -> "$withoutV1/v1/messages"
        }
    }

    private fun isOpenRouterCatalogEndpoint(endpoint: String): Boolean = runCatching {
        val url = URL(endpoint)
        url.host.equals("openrouter.ai", ignoreCase = true) &&
            url.path.trimEnd('/').endsWith("/models")
    }.getOrDefault(false)

    internal fun validationBody(
        model: String,
        protocol: ProviderProtocol,
        openRouterProviderOrder: String = "",
        openRouterAllowFallbacks: Boolean = true,
    ): String = when (protocol) {
        ProviderProtocol.OPENAI_RESPONSES -> JSONObject()
            .put("model", model)
            .put(
                "input",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "input_text")
                                    .put("text", "Hello, reply with 1 word."),
                            ),
                        ),
                ),
            )
            .toString()
        ProviderProtocol.OPENAI_CHAT -> JSONObject()
            .put("model", model)
            .put("max_tokens", VALIDATION_MAX_TOKENS)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK")))
            .toString()
        else -> JSONObject()
            .put("model", model)
            .put("max_tokens", VALIDATION_MAX_TOKENS)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK")))
            .also { body ->
                val providers = openRouterProviderOrder.split(',')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                if (protocol == ProviderProtocol.OPENROUTER && providers.isNotEmpty()) {
                    body.put(
                        "provider",
                        JSONObject()
                            .put("order", JSONArray(providers))
                            .put("allow_fallbacks", openRouterAllowFallbacks),
                    )
                }
            }
            .toString()
    }

    private fun friendlyHttpError(code: Int): String = when (code) {
        429 -> "The provider rate limit was reached. Wait a moment and try again."
        in 500..599 -> "The provider is temporarily unavailable (HTTP $code)."
        else -> "The provider returned HTTP $code. Check the URL and account access."
    }

    private fun providerErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        val extracted = runCatching {
            val root = JSONObject(body)
            when (val error = root.opt("error")) {
                is JSONObject -> error.optString("message").ifBlank { error.optString("detail") }
                is String -> error
                else -> root.optString("message").ifBlank { root.optString("detail") }
            }
        }.getOrNull().orEmpty()
        if (extracted.isBlank()) return null
        return extracted
            .replace(Regex("(?i)bearer\\s+\\S+"), "Bearer ••••")
            .replace(Regex("(?i)sk-[a-z0-9_-]{8,}"), "sk-••••")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(280)
    }

    private data class HttpResult(val code: Int, val body: String, val error: String? = null)

    private companion object {
        const val VALIDATION_MAX_TOKENS = 8
        const val MAX_PROVIDER_RESPONSE_CHARS = 2 * 1024 * 1024
    }
}

object ModelResponseParser {
    fun parse(json: String): List<DiscoveredModel> = runCatching {
        val trimmed = json.trim()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            }
        }
        buildList {
            for (index in 0 until array.length()) {
                when (val item = array.opt(index)) {
                    is String -> if (item.isNotBlank()) add(DiscoveredModel(item))
                    is JSONObject -> {
                        val id = item.optString("id")
                            .ifBlank { item.optString("name") }
                            .ifBlank { item.optString("model") }
                        if (id.isNotBlank()) {
                            val label = item.optString("display_name")
                                .ifBlank { item.optString("displayName") }
                                .ifBlank { id }
                            val pricing = item.optJSONObject("pricing")
                            val free = id.endsWith(":free", ignoreCase = true) || pricing?.let {
                                listOf("prompt", "completion", "request").all { field ->
                                    it.optString(field, "0").toDoubleOrNull() == 0.0
                                }
                            } == true
                            add(DiscoveredModel(id, label, free))
                        }
                    }
                }
            }
        }.distinctBy { it.id }.sortedWith(
            compareByDescending<DiscoveredModel> { it.isFree }.thenBy { it.displayName.lowercase() },
        )
    }.getOrDefault(emptyList())
}
```

## 8. `app/src/main/java/com/nextcraft/forge/network/LocalNetwork.kt` (114 baris)

```kotlin
package com.nextcraft.forge.network

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Plain-HTTP policy and localhost port-discovery utilities for NextCraft Forge.
 *
 * Self-hosted model servers (llama.cpp, LM Studio, Ollama, vLLM, LocalFormatGateway, etc.)
 * and local dev servers inside the Ubuntu chroot speak plain HTTP on loopback, LAN, or Tailscale.
 * Public internet endpoints must always use HTTPS.
 */
object LocalNetwork {
    private val localSuffixes = listOf(
        ".local",
        ".lan",
        ".home",
        ".home.arpa",
        ".internal",
        ".localdomain",
        ".ts.net",
    )

    val DEFAULT_PREVIEW_PORTS: List<Int> = listOf(
        3000,
        4173,
        4200,
        5000,
        5173,
        8000,
        8080,
        8081,
        8787,
        8888,
    )

    fun isPlainHttpAllowed(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.takeIf(String::isNotBlank) ?: return false
        return when (scheme) {
            "https" -> true
            "http" -> isLoopbackHost(host)
            else -> false
        }
    }

    /** Matches the hosts the network security config allows over cleartext. */
    fun isLoopbackHost(rawHost: String): Boolean {
        val host = rawHost.trim().trim('[', ']').lowercase().trimEnd('.')
        return host == "localhost" || host == "127.0.0.1"
    }

    fun isLocalHost(rawHost: String): Boolean {
        val host = rawHost
            .trim()
            .trim('[', ']')
            .substringBefore('%') // Strip IPv6 scope IDs like %wlan0
            .lowercase()
            .trimEnd('.')
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "0.0.0.0" || localSuffixes.any(host::endsWith)) return true
        if (host.contains(':')) return isLocalIpv6(host)
        val octets = host.split('.')
        if (octets.size == 4 && octets.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) {
            val (a, b) = octets.take(2).map(String::toInt)
            return a == 10 ||
                a == 127 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254) ||
                (a == 100 && b in 64..127) // Tailscale / carrier-grade NAT
        }
        return false
    }

    private fun isLocalIpv6(host: String): Boolean {
        if (host == "::1" || host == "0:0:0:0:0:0:0:1") return true
        if (host.startsWith("::ffff:")) {
            val mappedIpv4 = host.removePrefix("::ffff:")
            if (mappedIpv4.contains('.')) return isLocalHost(mappedIpv4)
        }
        val firstHextet = host.substringBefore(':')
        if (firstHextet.length == 4 && firstHextet.all { it in '0'..'9' || it in 'a'..'f' }) {
            val prefix2 = firstHextet.take(2)
            val prefix3 = firstHextet.take(3)
            if (prefix2 == "fc" || prefix2 == "fd") return true // Unique Local (fc00::/7)
            if (prefix3 == "fe8" || prefix3 == "fe9" || prefix3 == "fea" || prefix3 == "feb") return true // Link-Local (fe80::/10)
        }
        return false
    }

    /**
     * Probes whether a TCP server is listening on `127.0.0.1:[port]` (shared with the chroot via host netns).
     */
    fun isLocalPortOpen(port: Int, timeoutMs: Int = 150): Boolean {
        if (port !in 1..65535) return false
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs.coerceAtLeast(25))
                true
            }
        }.getOrDefault(false)
    }

    /**
     * Scans candidate localhost ports and returns those currently accepting TCP connections.
     */
    fun scanOpenLocalPorts(
        ports: Iterable<Int> = DEFAULT_PREVIEW_PORTS,
        timeoutMs: Int = 120,
    ): List<Int> = ports.distinct().filter { port -> isLocalPortOpen(port, timeoutMs) }
}
```

## 9. `app/src/main/java/com/nextcraft/forge/network/GitHubClient.kt` (229 baris)

```kotlin
package com.nextcraft.forge.network

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

data class GitHubAccount(val login: String, val avatarUrl: String)

data class GitHubRepository(
    val fullName: String,
    val cloneUrl: String,
    val private: Boolean,
    val defaultBranch: String,
    val description: String,
    val updatedAt: String,
)

sealed interface GitHubTokenPoll {
    data class Success(val accessToken: String) : GitHubTokenPoll
    data class Pending(val slowDown: Boolean = false) : GitHubTokenPoll
    data class Failure(val message: String) : GitHubTokenPoll
}

class GitHubClient(@Suppress("UNUSED_PARAMETER") private val context: Context? = null) {

    fun requestDeviceCode(
        clientId: String = DEFAULT_CLIENT_ID,
        scope: String = DEFAULT_SCOPES,
    ): GitHubDeviceCode {
        val resolvedClientId = clientId.trim().ifEmpty { DEFAULT_CLIENT_ID }
        val resolvedScope = scope.trim().ifEmpty { DEFAULT_SCOPES }
        val json = postForm(
            "https://github.com/login/device/code",
            mapOf(
                "client_id" to resolvedClientId,
                "scope" to resolvedScope,
            ),
        )
        return GitHubDeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.optString("verification_uri", "https://github.com/login/device"),
            expiresInSeconds = json.optLong("expires_in", 900L),
            intervalSeconds = json.optLong("interval", 5L).coerceAtLeast(5L),
        )
    }

    fun pollDeviceToken(clientId: String = DEFAULT_CLIENT_ID, deviceCode: String): GitHubTokenPoll {
        val resolvedClientId = clientId.trim().ifEmpty { DEFAULT_CLIENT_ID }
        val json = postForm(
            "https://github.com/login/oauth/access_token",
            mapOf(
                "client_id" to resolvedClientId,
                "device_code" to deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
        )
        json.optString("access_token").takeIf(String::isNotBlank)?.let { return GitHubTokenPoll.Success(it) }
        return when (val error = json.optString("error")) {
            "authorization_pending" -> GitHubTokenPoll.Pending()
            "slow_down" -> GitHubTokenPoll.Pending(slowDown = true)
            "access_denied" -> GitHubTokenPoll.Failure("GitHub authorization was cancelled")
            "expired_token" -> GitHubTokenPoll.Failure("The GitHub sign-in code expired. Try again.")
            else -> GitHubTokenPoll.Failure(
                json.optString("error_description").ifBlank {
                    error.ifBlank { "GitHub sign-in failed" }
                },
            )
        }
    }

    fun account(token: String): GitHubAccount {
        val json = getJson("https://api.github.com/user", token) as? JSONObject
            ?: error("Unexpected GitHub user profile response")
        return GitHubAccount(json.getString("login"), json.optString("avatar_url"))
    }

    fun createRepository(
        token: String,
        name: String,
        description: String = "",
        private: Boolean = true,
        autoInit: Boolean = false,
    ): GitHubRepository {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Repository name must not be empty" }
        val payload = JSONObject()
            .put("name", cleanName)
            .put("description", description.trim())
            .put("private", private)
            .put("auto_init", autoInit)
        val item = postJson("https://api.github.com/user/repos", token, payload) as? JSONObject
            ?: error("Unexpected GitHub create repository response")
        return GitHubRepository(
            fullName = item.getString("full_name"),
            cloneUrl = item.getString("clone_url"),
            private = item.optBoolean("private", private),
            defaultBranch = item.optString("default_branch", "main").ifBlank { "main" },
            description = item.optString("description"),
            updatedAt = item.optString("updated_at"),
        )
    }

    fun repositories(token: String): List<GitHubRepository> {
        val result = LinkedHashMap<String, GitHubRepository>()
        var page = 1
        while (page <= 10) {
            val array = getJson(
                "https://api.github.com/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=100&page=$page",
                token,
            ) as? JSONArray ?: error("Unexpected GitHub repository list response")
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val fullName = item.optString("full_name")
                val cloneUrl = item.optString("clone_url")
                if (fullName.isBlank() || cloneUrl.isBlank()) continue
                val repo = GitHubRepository(
                    fullName = fullName,
                    cloneUrl = cloneUrl,
                    private = item.optBoolean("private"),
                    defaultBranch = item.optString("default_branch", "main").ifBlank { "main" },
                    description = item.optString("description"),
                    updatedAt = item.optString("updated_at"),
                )
                result[repo.fullName] = repo
            }
            if (array.length() < 100) break
            page++
        }
        return result.values.toList()
    }

    private fun postForm(endpoint: String, values: Map<String, String>): JSONObject {
        val body = values.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }.toByteArray(Charsets.UTF_8)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            connection.outputStream.use { it.write(body) }
            readResponse(connection) as? JSONObject ?: error("Unexpected GitHub OAuth response")
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(endpoint: String, token: String, payload: JSONObject): Any {
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            connection.outputStream.use { it.write(body) }
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(endpoint: String, token: String): Any {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponse(connection: HttpURLConnection): Any {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            buildString {
                val buffer = CharArray(8 * 1024)
                while (length < MAX_GITHUB_RESPONSE_CHARS) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, MAX_GITHUB_RESPONSE_CHARS - length))
                    if (count <= 0) break
                    append(buffer, 0, count)
                }
            }
        }.orEmpty()
        if (status !in 200..299) {
            val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
            error(message?.takeIf(String::isNotBlank) ?: "GitHub returned HTTP $status")
        }
        return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
    }

    companion object {
        const val DEFAULT_CLIENT_ID = "178c6fc778ccc68e1d6a"
        const val DEFAULT_SCOPES = "repo read:user user:email workflow"
        private const val USER_AGENT = "NextCraft-Forge-Android"
        private const val MAX_GITHUB_RESPONSE_CHARS = 4 * 1024 * 1024
    }
}
```

## 10. `app/src/main/java/com/nextcraft/forge/update/AppUpdater.kt` (214 baris)

```kotlin
package com.nextcraft.forge.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
)

class AppUpdater(
    private val context: Context,
    /**
     * Optional manifest URL override. When non-empty, used in place of
     * `BuildConfig.APP_UPDATE_MANIFEST_URL`.
     */
    private val manifestUrlOverride: String = "",
) {
    fun check(): AppUpdateInfo? {
        val manifestUrl = manifestUrlOverride.ifBlank { defaultManifestUrl() }.trim()
        check(manifestUrl.startsWith("https://")) { "No HTTPS update manifest URL is configured." }
        val connection = URL(manifestUrl).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) throw java.io.IOException("Update manifest request failed (HTTP $code)")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                buildString {
                    val buffer = CharArray(4 * 1024)
                    while (length < MAX_MANIFEST_CHARS) {
                        val count = reader.read(buffer, 0, minOf(buffer.size, MAX_MANIFEST_CHARS - length))
                        if (count <= 0) break
                        append(buffer, 0, count)
                    }
                }
            }
            val root = JSONObject(body)
            val versionCode = root.optLong("versionCode")
            if (versionCode <= currentVersionCode()) return null
            val variant = appVariant()
            val artifact = root.optJSONObject("artifacts")?.optJSONObject(variant)
                ?: root.optJSONObject(variant)
                ?: root
            val url = artifact.optString("url").ifBlank { artifact.optString("apkUrl") }.trim()
            if (!url.startsWith("https://")) return null
            AppUpdateInfo(
                versionCode = versionCode,
                versionName = root.optString("versionName", versionCode.toString()),
                apkUrl = url,
                sha256 = artifact.optString("sha256").trim().lowercase(),
                sizeBytes = artifact.optLong("sizeBytes", -1L),
                notes = root.optString("notes"),
            )
        } finally {
            connection.disconnect()
        }
    }

    fun download(info: AppUpdateInfo, progress: (Long, Long) -> Unit): File {
        require(info.apkUrl.startsWith("https://")) { "Update APK URL must use HTTPS." }
        val variant = appVariant()
        val directory = File(context.filesDir, "updates").also { it.mkdirs() }
        val partial = File(directory, "nextcraft-forge-$variant.apk.part")
        val target = File(directory, "nextcraft-forge-$variant.apk")
        try {
            val connection = URL(info.apkUrl).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                val code = connection.responseCode
                check(code in 200..299) { "Update download failed (HTTP $code)." }
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            progress(downloaded, total)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            check(info.sha256.isNotBlank()) {
                "Update manifest is missing SHA-256 checksum — refusing install."
            }
            val actual = sha256(partial)
            check(actual.equals(info.sha256, ignoreCase = true)) {
                "Downloaded update failed SHA-256 checksum verification."
            }
            verifyApk(partial, info.versionCode)
            if (target.exists() && !target.delete()) {
                error("Could not replace previous staged update APK.")
            }
            check(partial.renameTo(target)) { "Could not finalize downloaded update APK." }
            return target
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File, expectedVersionCode: Long) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("Downloaded file is not a valid Android APK archive.")
        check(archive.packageName == context.packageName) {
            "Downloaded APK package (${archive.packageName}) does not match installed app (${context.packageName})."
        }
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            archive.versionCode.toLong()
        }
        check(archiveVersion == expectedVersionCode && archiveVersion > currentVersionCode()) {
            "Downloaded APK version ($archiveVersion) does not match expected update version ($expectedVersionCode)."
        }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archiveSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.signingInfo?.apkContentsSigners
        } else {
            archive.signatures
        }
        val installedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installed.signingInfo?.apkContentsSigners
        } else {
            installed.signatures
        }
        check(
            !archiveSignatures.isNullOrEmpty() &&
                !installedSignatures.isNullOrEmpty() &&
                archiveSignatures.map { sha256(it.toByteArray()) }.toSet() ==
                installedSignatures.map { sha256(it.toByteArray()) }.toSet(),
        ) {
            "Downloaded APK signing certificate does not match the installed app."
        }
    }

    @Suppress("DEPRECATION")
    private fun currentVersionCode(): Long {
        buildConfigLong("VERSION_CODE")?.let { return it }
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        }.getOrDefault(1L)
    }

    private fun appVariant(): String =
        buildConfigString("APP_VARIANT")?.takeIf(String::isNotBlank) ?: "debug"

    private fun defaultManifestUrl(): String =
        buildConfigString("APP_UPDATE_MANIFEST_URL").orEmpty()

    private fun resolveBuildConfigClass(): Class<*>? =
        runCatching { Class.forName("${context.packageName}.BuildConfig") }.getOrNull()
            ?: runCatching { Class.forName("com.nextcraft.forge.BuildConfig") }.getOrNull()

    private fun buildConfigString(fieldName: String): String? = runCatching {
        val cls = resolveBuildConfigClass() ?: return@runCatching null
        cls.getField(fieldName).get(null) as? String
    }.getOrNull()

    private fun buildConfigLong(fieldName: String): Long? = runCatching {
        val cls = resolveBuildConfigClass() ?: return@runCatching null
        when (val value = cls.getField(fieldName).get(null)) {
            is Long -> value
            is Int -> value.toLong()
            else -> null
        }
    }.getOrNull()

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_MANIFEST_CHARS = 256 * 1024
    }
}
```
