package com.nextcraft.forge.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.os.Process as AndroidProcess
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.DevStack
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject

data class InstalledRuntime(
    val chrootExec: File,
    val rootfs: File,
) {
    /** Compatibility alias for callers referencing the runtime launcher executable. */
    val proot: File get() = chrootExec
    val binDir: File get() = chrootExec.parentFile ?: rootfs
}

data class RuntimeInstallProgress(
    val message: String,
    val fraction: Float,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val terminalLine: String? = null,
    val indeterminate: Boolean = false,
    val event: RuntimeInstallEvent = RuntimeInstallEvent.STAGE,
    /** True when [downloadedBytes] counts archive bytes unpacked rather than downloaded. */
    val unpacking: Boolean = false,
)

data class AgentUpdateInfo(
    val installedVersion: String,
    val latestVersion: String,
)

internal fun isSupportedDshVersion(version: String): Boolean =
    version == RuntimeInstaller.DSH_VERSION

enum class RuntimeInstallEvent { STAGE, COMMAND, OUTPUT, DOWNLOAD, COMMAND_COMPLETED, COMPLETED }

private data class RuntimeBundle(
    val label: String,
    val fileName: String,
    val sha256: String,
    val compressedBytes: Long,
    val baseUrl: String = RUNTIME_RELEASE_BASE_URL,
)

/** Release hosting the Ubuntu 24.04 bundles built from scripts/runtime-bundles. */
private const val RUNTIME_RELEASE_BASE_URL = "https://github.com/bymeopen/nextcraft-forge/releases/download/runtime-2026.10.1"

/** Release hosting the Ubuntu 20.04 bundles; still serves the Android bundle and the Python 3.8 overlay. */
private const val LEGACY_RUNTIME_RELEASE_BASE_URL = "https://github.com/techjarves/Mobile-Harness/releases/download/runtime-2026.09.4"

class RuntimeInstaller(private val context: Context) {
    private val runtimeDir = File(context.filesDir, "runtime")
    private val runtimeBinDir = File(runtimeDir, "bin")
    private val chrootExecFile = File(runtimeBinDir, "chroot-exec.sh")
    private val unmountChrootFile = File(runtimeBinDir, "unmount-chroot.sh")
    private val rootfs = File(runtimeDir, "ubuntu")
    private val downloads = File(context.cacheDir, "runtime-downloads")
    private val coreReadyMarker = File(rootfs, ".pocket-runtime-ready")
    private val nextcraftReadyMarker = File(rootfs, ".nextcraft-runtime-ready")
    private val claudeMarker = File(rootfs, ".pocket-claude-version")
    private val bundledClaudeMarker = File(rootfs, ".pocket-bundled-claude-version")
    private val rootfsMarker = File(rootfs, ".pocket-rootfs-version")
    private val languageToolsMarker = File(rootfs, ".pocket-language-tools-version")
    private val coreToolsMarker = File(rootfs, ".pocket-core-tools-version")
    private val systemUpgradeMarker = File(rootfs, ".pocket-system-upgrade-version")
    private val devStacksFile = File(rootfs, ".pocket-dev-stacks.json")
    private val dshMarker = File(rootfs, ".pocket-dsh-version")
    private val agyMarker = File(rootfs, ".pocket-agy-version")
    private val githubCliMarker = File(rootfs, ".pocket-github-cli-version")
    private val dshAndroidCompatibilityMarker = File(rootfs, ".pocket-dsh-android-compat-version")
    private val macosMetadataRepairMarker = File(rootfs, ".pocket-macos-metadata-repair")

    fun isInstalled(): Boolean {
        val chrootScriptsReady = runCatching { ensureChrootScripts() }.isSuccess
        val rootfsLayoutReady = ensureRootfsCompatibilityLinks()
        val legacyLanguageTools = languageToolsMarker.readTextOrNull() == LANGUAGE_TOOLS_VERSION
        val coreToolsReady = File(rootfs, "usr/bin/git").exists() && isSupportedCoreToolsVersion()
        val ready = chrootScriptsReady &&
            chrootExecFile.canExecute() &&
            rootfsLayoutReady &&
            File(rootfs, "usr/bin/bash").exists() &&
            isSupportedRootfsVersion(rootfsMarker.readTextOrNull()) &&
            File(rootfs, "usr/local/bin/node").exists() &&
            (legacyLanguageTools || coreToolsReady) &&
            (coreReadyMarker.exists() || nextcraftReadyMarker.exists())
        if (ready) repairLegacyMacosMetadata()
        return ready
    }

    private fun repairLegacyMacosMetadata() {
        if (macosMetadataRepairMarker.isFile) return
        if (!rootfs.isDirectory) return
        // isInstalled() can run while a chroot session is mounted: stay out of the bind mounts (/proc, /system,
        // /workspace, ...) so the walk is neither huge nor able to delete "._*" files from a user's project.
        stripMacosMetadataArtifacts(rootfs, skipTopLevel = BIND_MOUNTED_TOP_LEVEL)
        macosMetadataRepairMarker.parentFile?.mkdirs()
        macosMetadataRepairMarker.writeText("1")
    }

    /** Returns the already verified Root Chroot runtime without performing network or update checks. */
    fun installedRuntime(): InstalledRuntime {
        check(isInstalled()) { CORE_MISSING_MESSAGE }
        return InstalledRuntime(
            chrootExec = chrootExecFile,
            rootfs = rootfs,
        )
    }

    /**
     * Ensures `chroot-exec.sh` and `unmount-chroot.sh` are installed in `filesDir/runtime/bin`
     * with executable permissions (`0755`).
     */
    @Synchronized
    fun ensureChrootScripts(): File {
        runtimeBinDir.mkdirs()
        if (!chrootExecFile.isFile || chrootExecFile.readTextOrNull() != CHROOT_EXEC_SCRIPT.trim()) {
            writeScriptAtomically(chrootExecFile, CHROOT_EXEC_SCRIPT)
        }
        Os.chmod(chrootExecFile.absolutePath, 0b111101101)

        if (!unmountChrootFile.isFile || unmountChrootFile.readTextOrNull() != UNMOUNT_CHROOT_SCRIPT.trim()) {
            writeScriptAtomically(unmountChrootFile, UNMOUNT_CHROOT_SCRIPT)
        }
        Os.chmod(unmountChrootFile.absolutePath, 0b111101101)
        return chrootExecFile
    }

    /** Replaces the script via rename so a concurrent launch never executes a half-written file. */
    private fun writeScriptAtomically(target: File, content: String) {
        // Unique per call: several RuntimeInstaller instances can refresh the scripts at the same time.
        val staged = File(target.parentFile, "${target.name}.${AndroidProcess.myPid()}.${System.nanoTime()}.tmp")
        try {
            staged.writeText(content)
            Os.rename(staged.absolutePath, target.absolutePath)
        } catch (e: Throwable) {
            staged.delete()
            throw e
        }
    }

    /** Unmounts all kernel mounts under [targetRootfs] before deleting or swapping rootfs directories. */
    fun unmountChrootMounts(targetRootfs: File = rootfs): Boolean {
        ensureChrootScripts()
        return RootfsRepair.ensureUnmounted(targetRootfs)
    }

    /** Removes only scaffolding written automatically by earlier alpha builds. */
    fun cleanupLegacyWorkspaceScaffolding() {
        val workspaces = File(context.filesDir, "workspaces")
        workspaces.listFiles { file -> file.isDirectory }.orEmpty().forEach { workspace ->
            File(workspace, "README.md").deleteIfExact(LEGACY_README)
            File(workspace, "index.html").deleteIfExact(LEGACY_INDEX)

            listOf(
                File(workspace, ".claude/settings.json"),
                File(workspace, ".claude.json"),
            ).forEach { settings ->
                if (settings.isFile && settings.readTextOrNull()?.contains("/opt/pocket/permission-hook.sh") == true) {
                    settings.delete()
                }
            }
            File(workspace, ".claude").takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
        }
    }

    private fun File.deleteIfExact(expected: String) {
        if (isFile && runCatching { readText() }.getOrNull() == expected) delete()
    }

    suspend fun ensureInstalled(
        selectedStacks: Set<DevStack> = emptySet(),
        agent: AgentKind = AgentKind.CLAUDE_CODE,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): InstalledRuntime {
        require(
            supportsArm64Runtime(
                android.os.Build.SUPPORTED_ABIS,
                System.getProperty("os.arch"),
            ),
        ) { "NextCraft Forge requires an ARM64 (arm64-v8a / aarch64) Android device." }
        // The first su call shows the Magisk/KernelSU/APatch grant dialog; the 8 s default kills su before the user can tap Allow.
        require(isRootAccessAvailable(ROOT_GRANT_TIMEOUT_SECONDS)) {
            "Root access (su -mm) is required by NextCraft Forge's Ubuntu 24.04 LTS Chroot engine. Grant root permission in Magisk, KernelSU, or APatch and try again."
        }
        val chrootExec = ensureChrootScripts()
        require(chrootExec.canExecute()) { "The Root Chroot supervisor script is unavailable" }

        val staging = File(runtimeDir, "ubuntu.installing")
        val repairBackup = File(runtimeDir, "ubuntu.repair-backup")
        RootfsRepair.reconcileInterruptedSwap(
            rootfs = rootfs,
            staging = staging,
            backup = repairBackup,
            validate = ::isValidCoreRootfs,
        )
        val layoutBroken = rootfs.isDirectory && !ensureRootfsCompatibilityLinks()
        if (layoutBroken ||
            !File(rootfs, "usr/bin/bash").exists() ||
            !isSupportedRootfsVersion(rootfsMarker.readTextOrNull())
        ) {
            onProgress(RuntimeInstallProgress("Preparing Ubuntu Root Chroot runtime…", 0.03f))
            val archive = obtainRuntimeBundle(
                CORE_BUNDLE,
                preferEmbedded = isAssetAvailable("runtime/${CORE_BUNDLE.fileName}"),
                from = 0.03f,
                to = 0.25f,
                onProgress = onProgress,
            )
            onProgress(RuntimeInstallProgress("Unpacking Core Linux rootfs…", 0.28f))
            check(RootfsRepair.ensureUnmounted(staging)) { "Staging rootfs still has active mounts" }
            staging.deleteRecursively()
            staging.mkdirs()
            extractZstdTar(archive, staging) { done, total ->
                onProgress(unpackProgress("Unpacking Core Linux rootfs…", 0.28f, 0.34f, done, total))
            }
            stripMacosMetadataArtifacts(staging)
            ensureAndroidSocketGroupConfiguration(staging)
            RootfsRepair.activate(
                rootfs = rootfs,
                staging = staging,
                backup = repairBackup,
                validate = ::isValidCoreRootfs,
            )
            writeResolver()
            if (archive.parentFile == downloads) archive.delete()
        }

        check(ensureRootfsCompatibilityLinks()) { "Core runtime has an invalid Linux filesystem layout" }
        ensureAndroidSocketGroupConfiguration(rootfs)

        migrateLegacyClaudeMarker()
        ensureSettingsAndHooks()

        val coreNeeded = !File(rootfs, "usr/bin/git").exists() || !isSupportedCoreToolsVersion()
        // isInstalled() also requires Node; a missing/dangling node must be repaired even when git and the marker are fine.
        val nodeMissing = !File(rootfs, "usr/local/bin/node").exists()
        if (coreNeeded || nodeMissing) {
            installNodeIfNeeded(chrootExec, 0.58f, 0.66f, onProgress)
        }
        if (systemUpgradeMarker.readTextOrNull() != SYSTEM_UPGRADE_VERSION) {
            runSystemMaintenance(chrootExec, onProgress)
            systemUpgradeMarker.writeText(SYSTEM_UPGRADE_VERSION)
        }
        if (coreNeeded) {
            aptInstall(
                chrootExec,
                listOf("git", "ca-certificates"),
                "Installing Git and base SSL certificates…",
                0.70f,
                onProgress,
            )
            writeResolver()
            verifyGuest(chrootExec, "git --version", "Failed to verify Git inside Root Chroot")
            coreToolsMarker.writeText(CORE_TOOLS_VERSION)
        }
        if (!coreReadyMarker.exists()) coreReadyMarker.writeText(CORE_TOOLS_VERSION)
        if (!nextcraftReadyMarker.exists()) nextcraftReadyMarker.writeText(CORE_TOOLS_VERSION)

        val removedStacks = if (selectedStacks.isNotEmpty()) {
            (installedStacks() - selectedStacks) - DevStack.WEB
        } else {
            emptySet()
        }
        val missingStacks = selectedStacks.filterNot(::isStackInstalled)
        val totalStackOps = maxOf(1, removedStacks.size + missingStacks.size)
        var opIndex = 0
        removedStacks.forEach { stack ->
            val slice = 0.26f / totalStackOps
            val from = 0.72f + opIndex * slice
            removeStack(stack) { p ->
                onProgress(p.copy(fraction = from + p.fraction.coerceIn(0f, 1f) * slice))
            }
            opIndex++
        }
        // One broken stack (e.g. Android tools that cannot run) must not keep the others from installing:
        // install every selected stack, then report all failures together.
        val failedStacks = mutableListOf<Pair<DevStack, Throwable>>()
        missingStacks.forEach { stack ->
            val slice = 0.26f / totalStackOps
            val from = 0.72f + opIndex * slice
            try {
                applyStack(chrootExec, stack, from, from + slice, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("RuntimeInstaller", "Installing ${stack.label} failed", e)
                failedStacks += stack to e
                onProgress(
                    RuntimeInstallProgress(
                        "${stack.label} failed: ${e.message?.take(160).orEmpty()}",
                        from + slice,
                    ),
                )
            }
            opIndex++
        }

        when (agent) {
            AgentKind.CLAUDE_CODE -> ensureClaudeInstalled(chrootExec, 0.985f, onProgress)
            AgentKind.DEEPSEEK_HARNESS -> ensureDshInstalled(chrootExec, 0.985f, onProgress)
            AgentKind.ANTIGRAVITY -> ensureAgyInstalled(chrootExec, 0.985f, onProgress)
        }
        check(failedStacks.isEmpty()) {
            failedStacks.joinToString(separator = "\n") { (stack, error) ->
                "${stack.label}: ${error.message?.take(200).orEmpty()}"
            }
        }
        onProgress(RuntimeInstallProgress("NextCraft Forge Root Chroot setup complete", 1f))
        return InstalledRuntime(chrootExec, rootfs)
    }

    /**
     * Installs one coding agent on demand. Safe to call again: an already-installed
     * agent returns immediately without network access.
     */
    suspend fun ensureAgentInstalled(
        agent: AgentKind,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        when (agent) {
            AgentKind.CLAUDE_CODE -> ensureClaudeInstalled(runtime.chrootExec, 0.05f, onProgress)
            AgentKind.DEEPSEEK_HARNESS -> ensureDshInstalled(runtime.chrootExec, 0.05f, onProgress)
            AgentKind.ANTIGRAVITY -> ensureAgyInstalled(runtime.chrootExec, 0.05f, onProgress)
        }
        onProgress(RuntimeInstallProgress("${agent.title} is ready", 1f))
    }

    /**
     * Removes one coding agent's binaries and version markers. Projects, API keys and the Core runtime are untouched;
     * the agent's own login/config under /root is kept so a reinstall does not force a new sign-in.
     */
    fun removeAgent(agent: AgentKind) {
        installedRuntime()
        when (agent) {
            AgentKind.CLAUDE_CODE -> {
                removePath(File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/")))
                listOf(".pocket-claude-version", ".nextcraft-claude-version", ".pocket-bundled-claude-version")
                    .forEach { File(rootfs, it).delete() }
            }
            AgentKind.DEEPSEEK_HARNESS -> {
                removePath(File(rootfs, "usr/local/bin/dsh"))
                removePath(File(rootfs, "usr/local/lib/dsh"))
                listOf(".pocket-dsh-version", ".nextcraft-dsh-version", ".pocket-dsh-android-compat-version")
                    .forEach { File(rootfs, it).delete() }
            }
            AgentKind.ANTIGRAVITY -> {
                removePath(File(rootfs, AGY_GUEST_PATH.removePrefix("/")))
                listOf(".pocket-agy-version", ".nextcraft-agy-version").forEach { File(rootfs, it).delete() }
            }
        }
        check(!isAgentInstalled(agent)) { "Could not fully remove ${agent.title}" }
    }

    fun isAgentInstalled(agent: AgentKind): Boolean {
        return when (agent) {
            AgentKind.CLAUDE_CODE -> {
                migrateLegacyClaudeMarker()
                isInstalled() && File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/")).canExecute() &&
                    !claudeMarker.readTextOrNull().isNullOrBlank()
            }
            AgentKind.DEEPSEEK_HARNESS -> isInstalled() &&
                File(rootfs, "usr/local/lib/dsh/node_modules/.bin/dsh").isFile &&
                isSupportedDshVersion(dshMarker.readTextOrNull().orEmpty())
            AgentKind.ANTIGRAVITY -> isInstalled() &&
                File(rootfs, AGY_GUEST_PATH.removePrefix("/")).canExecute() &&
                !agyMarker.readTextOrNull().isNullOrBlank()
        }
    }

    val dshVersion: String get() = dshMarker.readTextOrNull().orEmpty()

    val claudeVersion: String
        get() {
            migrateLegacyClaudeMarker()
            return claudeMarker.readTextOrNull().orEmpty()
        }

    val agyVersion: String get() = agyMarker.readTextOrNull().orEmpty()

    val githubCliVersion: String get() = githubCliMarker.readTextOrNull().orEmpty()

    fun isGitHubCliInstalled(): Boolean = isInstalled() &&
        File(rootfs, GITHUB_CLI_GUEST_PATH.removePrefix("/")).canExecute() &&
        githubCliMarker.readTextOrNull() == GITHUB_CLI_VERSION

    /** Installs GitHub's official ARM64 CLI on demand; it is not bundled in the APK. */
    suspend fun ensureGitHubCliInstalled(onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        if (isGitHubCliInstalled()) return
        writeResolver()
        downloads.mkdirs()
        val downloaded = File(downloads, "gh-$GITHUB_CLI_VERSION-linux-arm64.tar.gz")
        onProgress(RuntimeInstallProgress("Downloading GitHub CLI…", 0.05f))
        val githubDownloadLabel = "Downloading GitHub CLI $GITHUB_CLI_VERSION…"
        downloadVerified(GITHUB_CLI_RELEASE_URL, downloaded, GITHUB_CLI_RELEASE_SHA256) { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(
                RuntimeInstallProgress(
                    message = githubDownloadLabel,
                    fraction = 0.05f + ratio * 0.75f,
                    downloadedBytes = bytes,
                    totalBytes = total.takeIf { it > 0L },
                    event = RuntimeInstallEvent.DOWNLOAD,
                ),
            )
        }
        onProgress(RuntimeInstallProgress("Installing GitHub CLI $GITHUB_CLI_VERSION…", 0.85f, indeterminate = true))
        val destination = File(rootfs, GITHUB_CLI_GUEST_PATH.removePrefix("/"))
        destination.parentFile?.mkdirs()
        var found = false
        try {
            @Suppress("DEPRECATION")
            TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(downloaded.inputStream()))).use { archive ->
                var entry = archive.nextTarEntry
                while (entry != null) {
                    if (entry.isFile && entry.name.removePrefix("./").endsWith("/bin/gh")) {
                        val staged = File(destination.parentFile, ".gh-$GITHUB_CLI_VERSION.installing")
                        try {
                            FileOutputStream(staged).use { archive.copyTo(it) }
                            Os.chmod(staged.absolutePath, 0b111101101)
                            Os.rename(staged.absolutePath, destination.absolutePath)
                        } catch (e: Throwable) {
                            staged.delete()
                            throw e
                        }
                        found = true
                        break
                    }
                    entry = archive.nextTarEntry
                }
            }
            check(found) { "Official GitHub CLI archive did not contain the expected binary" }
        } finally {
            downloaded.delete()
        }
        verifyGuest(
            chrootExec = installedRuntime().chrootExec,
            command = "$GITHUB_CLI_GUEST_PATH --version",
            failureMessage = "Failed to verify GitHub CLI",
        )
        githubCliMarker.writeText(GITHUB_CLI_VERSION)
        check(isGitHubCliInstalled()) { "GitHub CLI installation is incomplete" }
        onProgress(RuntimeInstallProgress("GitHub CLI is ready", 1f))
    }

    /** Provisions Git credential store and GitHub CLI hosts.yml inside the guest rootfs with 0600 permissions. */
    fun configureGitHubAccess(token: String, login: String) {
        val cleanToken = token.trim().replace("\r", "").replace("\n", "")
        if (cleanToken.isEmpty()) return
        val cleanLogin = login.trim().replace("\r", "").replace("\n", "").ifEmpty { "x-access-token" }
        val rootHome = File(rootfs, "root").apply { mkdirs() }
        val gitCredentials = File(rootHome, ".git-credentials")
        // git/gh rewrite these as root inside the chroot; reclaim ownership instead of failing with EACCES.
        writeRootfsFileAtomically(gitCredentials, "https://$cleanLogin:$cleanToken@github.com\n")
        runCatching { Os.chmod(gitCredentials.absolutePath, 384) } // 0600

        // Merge instead of replacing: the user's own name, email, aliases and editor settings must survive connecting
        // GitHub. Only the credential helper is forced; identity and default branch are filled in when missing.
        val gitConfig = File(rootHome, ".gitconfig")
        if (gitConfig.exists() && !gitConfig.canRead()) RootfsRepair.reclaimOwnership(gitConfig, AndroidProcess.myUid())
        val existingConfig = if (gitConfig.isFile) {
            // An unreadable existing file must not be replaced by a template that drops the user's settings.
            checkNotNull(runCatching { gitConfig.readText() }.getOrNull()) { "Could not read ~/.gitconfig" }
        } else {
            ""
        }
        writeRootfsFileAtomically(
            gitConfig,
            mergeGitConfig(
                existingConfig,
                listOf(
                    GitConfigEntry("credential", "helper", "store", replace = true),
                    GitConfigEntry("user", "name", cleanLogin),
                    GitConfigEntry("user", "email", "$cleanLogin@users.noreply.github.com"),
                    GitConfigEntry("init", "defaultBranch", "main"),
                    // Workspaces belong to the app UID while git runs as root, so git needs this to trust them.
                    GitConfigEntry("safe", "directory", "*", multiValue = true),
                ),
            ),
        )
        runCatching { Os.chmod(gitConfig.absolutePath, 384) } // 0600

        val ghConfigDir = File(rootHome, ".config/gh").apply { mkdirs() }
        val ghHosts = File(ghConfigDir, "hosts.yml")
        writeRootfsFileAtomically(
            ghHosts,
            buildString {
                appendLine("github.com:")
                appendLine("    oauth_token: $cleanToken")
                appendLine("    user: $cleanLogin")
                appendLine("    git_protocol: https")
            },
        )
        runCatching { Os.chmod(ghHosts.absolutePath, 384) } // 0600
    }

    /** Removes stored Git and GitHub CLI credentials from the guest rootfs. */
    fun clearGitHubAccess() {
        val rootHome = File(rootfs, "root")
        listOf(File(rootHome, ".git-credentials"), File(rootHome, ".config/gh/hosts.yml")).forEach { file ->
            if (!file.exists() || file.delete()) return@forEach
            // Root-owned after git/gh rewrote it: take it back, otherwise "Disconnect" would leave the token on disk.
            RootfsRepair.reclaimOwnership(file, AndroidProcess.myUid())
            if (!file.delete() && file.exists()) Log.w("RuntimeInstaller", "Could not remove ${file.name}")
        }
    }

    /**
     * True when `claude auth login` left a usable login (access + refresh token) in the guest. The file is written by
     * root inside the chroot, so the app UID usually cannot read it; ask root.
     */
    fun hasClaudeLoginCredential(): Boolean {
        val file = File(rootfs, "root/.claude/.credentials.json")
        val quoted = "'${file.absolutePath.replace("'", "'\\''")}'"
        return runCatching {
            val process = ProcessBuilder("su", "-c", "grep -q '\"refreshToken\"' $quoted").start()
            if (!process.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        }.getOrDefault(false)
    }

    /** Versions recorded after each real agent binary has been installed and verified. */
    fun installedAgentVersions(): Map<AgentKind, String> = buildMap {
        migrateLegacyClaudeMarker()
        claudeMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { isAgentInstalled(AgentKind.CLAUDE_CODE) && it.matches(CLAUDE_VERSION_PATTERN) }
            ?.let { put(AgentKind.CLAUDE_CODE, it) }

        dshMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { isAgentInstalled(AgentKind.DEEPSEEK_HARNESS) }
            ?.let { put(AgentKind.DEEPSEEK_HARNESS, it) }

        agyMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && File(rootfs, AGY_GUEST_PATH.removePrefix("/")).canExecute() }
            ?.let { put(AgentKind.ANTIGRAVITY, it) }
    }

    /** Checks each installed agent against its own authoritative release source. */
    suspend fun checkAgentUpdates(): Map<AgentKind, AgentUpdateInfo> {
        val installed = installedAgentVersions()
        return buildMap {
            installed[AgentKind.CLAUDE_CODE]?.let { current ->
                runCatching {
                    JSONObject(fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest")).getString("version")
                }.getOrNull()?.takeIf { isVersionNewer(it, current) }?.let { latest ->
                    put(AgentKind.CLAUDE_CODE, AgentUpdateInfo(current, latest))
                }
            }
            installed[AgentKind.DEEPSEEK_HARNESS]?.let { current ->
                DSH_VERSION.takeIf { isVersionNewer(it, current) }?.let { compatible ->
                    put(AgentKind.DEEPSEEK_HARNESS, AgentUpdateInfo(current, compatible))
                }
            }
            installed[AgentKind.ANTIGRAVITY]?.let { current ->
                runCatching { fetchAgyManifest().getString("version") }.getOrNull()
                    ?.takeIf { isVersionNewer(it, current) }?.let { latest ->
                        put(AgentKind.ANTIGRAVITY, AgentUpdateInfo(current, latest))
                    }
            }
        }
    }

    suspend fun updateAgent(
        agent: AgentKind,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        when (agent) {
            AgentKind.CLAUDE_CODE -> updateClaude(runtime, expectedVersion, onProgress)
            AgentKind.DEEPSEEK_HARNESS -> updateDsh(runtime, expectedVersion, onProgress)
            AgentKind.ANTIGRAVITY -> updateAgy(runtime, expectedVersion, onProgress)
        }
        onProgress(
            RuntimeInstallProgress(
                "${agent.title} $expectedVersion is ready",
                1f,
                event = RuntimeInstallEvent.COMPLETED,
            ),
        )
    }

    /**
     * Swaps [target] for a new binary and keeps the old one until [verify] passes. A binary that downloads and
     * checksums fine but cannot run (e.g. a glibc mismatch) is rolled back instead of leaving a broken agent that
     * still counts as installed.
     */
    private suspend fun replaceBinaryWithRollback(
        target: File,
        install: () -> Unit,
        verify: suspend () -> Unit,
    ) {
        val previous = File(target.parentFile, ".${target.name}.previous")
        // An earlier update killed between the rename below and its cleanup leaves the only good binary in .previous.
        if (previous.exists() && !target.exists()) runCatching { Os.rename(previous.absolutePath, target.absolutePath) }
        previous.delete()
        if (target.exists()) Os.rename(target.absolutePath, previous.absolutePath)
        try {
            install()
            verify()
            previous.delete()
        } catch (e: Throwable) {
            target.delete()
            if (previous.exists()) runCatching { Os.rename(previous.absolutePath, target.absolutePath) }
            throw e
        }
    }

    private suspend fun updateClaude(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val latest = JSONObject(fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest")).getString("version")
        check(latest == expectedVersion) { "A newer Claude Code release is available. Refresh updates and try again." }
        val base = "https://downloads.claude.ai/claude-code-releases/$latest"
        val manifest = JSONObject(fetchText("$base/manifest.json"))
        val checksum = manifest.getJSONObject("platforms").getJSONObject("linux-arm64").getString("checksum")
        val downloaded = File(downloads, "claude-$latest")
        val claudeDownloadLabel = "Downloading Claude Code $latest…"
        downloadVerified("$base/linux-arm64/claude", downloaded, checksum) { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(claudeDownloadLabel, ratio * 0.9f, bytes, total.takeIf { it > 0L }, event = RuntimeInstallEvent.DOWNLOAD))
        }
        val claude = File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/"))
        claude.parentFile?.mkdirs()
        val staged = File(claude.parentFile, ".claude-$latest.installing")
        try {
            replaceBinaryWithRollback(
                target = claude,
                install = {
                    try {
                        downloaded.copyTo(staged, overwrite = true)
                        Os.chmod(staged.absolutePath, 0b111101101)
                        Os.rename(staged.absolutePath, claude.absolutePath)
                    } catch (e: Throwable) {
                        staged.delete()
                        throw e
                    }
                },
                verify = { verifyGuest(runtime.chrootExec, "$CLAUDE_GUEST_PATH --version", "Failed to verify updated Claude Code") },
            )
        } finally {
            downloaded.delete()
        }
        claudeMarker.writeText(latest)
    }

    private suspend fun updateAgy(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val manifest = fetchAgyManifest()
        val latest = manifest.getString("version")
        check(latest == expectedVersion) { "A newer Antigravity CLI release is available. Refresh updates and try again." }
        val downloaded = File(downloads, "antigravity-$latest-linux-arm64.tar.gz")
        val agyDownloadLabel = "Downloading Antigravity CLI $latest…"
        downloadVerified(manifest.getString("url"), downloaded, manifest.getString("sha512"), algorithm = "SHA-512") { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(agyDownloadLabel, ratio * 0.9f, bytes, total.takeIf { it > 0L }, event = RuntimeInstallEvent.DOWNLOAD))
        }
        val destination = File(rootfs, AGY_GUEST_PATH.removePrefix("/"))
        var found = false
        try {
          replaceBinaryWithRollback(
            target = destination,
            verify = { verifyGuest(runtime.chrootExec, "$AGY_GUEST_PATH --version", "Failed to verify updated Antigravity CLI") },
            install = {
            @Suppress("DEPRECATION")
            TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(downloaded.inputStream()))).use { archive ->
                var entry = archive.nextTarEntry
                while (entry != null) {
                    if (entry.isFile && entry.name.removePrefix("./") == "antigravity") {
                        val staged = File(destination.parentFile, ".agy-$latest.installing")
                        try {
                            FileOutputStream(staged).use { archive.copyTo(it) }
                            Os.chmod(staged.absolutePath, 0b111101101)
                            Os.rename(staged.absolutePath, destination.absolutePath)
                        } catch (e: Throwable) {
                            staged.delete()
                            throw e
                        }
                        found = true
                        break
                    }
                    entry = archive.nextTarEntry
                }
            }
            check(found) { "Antigravity update archive is incomplete" }
            },
          )
        } finally {
            downloaded.delete()
        }
        agyMarker.writeText(latest)
    }

    private suspend fun updateDsh(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        check(isSupportedDshVersion(expectedVersion)) {
            "DeepSeek Harness $expectedVersion is not compatible with this NextCraft Forge build"
        }
        val quotedVersion = expectedVersion.replace(Regex("[^0-9A-Za-z.+-]"), "")
        check(quotedVersion == expectedVersion) { "Invalid DeepSeek Harness version" }
        runGuestCommand(
            chrootExec = runtime.chrootExec,
            command = "set -e; next=/usr/local/lib/dsh.updating; " +
                "rm -rf \"${'$'}next\"; mkdir -p \"${'$'}next\"; " +
                "cd \"${'$'}next\"; npm init -y >/dev/null; " +
                "npm install --omit=dev --no-audit --no-fund @deepseek-ai/dsh@$quotedVersion; " +
                // npm runs as root; the app patches the tree next (applyDshAndroidCompatibility) as its own UID.
                "chown -R -h ${AndroidProcess.myUid()}:${AndroidProcess.myUid()} \"${'$'}next\"",
            displayCommand = "npm install @deepseek-ai/dsh@$quotedVersion",
            fraction = 0.55f,
            timeoutMs = 20 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Failed to update DeepSeek Harness",
        )
        val staged = File(rootfs, "usr/local/lib/dsh.updating")
        applyDshAndroidCompatibility(staged, expectedVersion)
        runGuestCommand(
            chrootExec = runtime.chrootExec,
            command = "set -e; old=/usr/local/lib/dsh.previous; rm -rf \"${'$'}old\"; " +
                "mv /usr/local/lib/dsh \"${'$'}old\"; " +
                "if mv /usr/local/lib/dsh.updating /usr/local/lib/dsh; then :; " +
                "else mv \"${'$'}old\" /usr/local/lib/dsh; exit 1; fi",
            displayCommand = "Activate DeepSeek Harness $quotedVersion",
            fraction = 0.9f,
            timeoutMs = 60_000L,
            onProgress = onProgress,
            failureMessage = "Failed to activate DeepSeek Harness update",
        )
        val verified = runCatching {
            verifyGuest(runtime.chrootExec, "/usr/local/bin/dsh --profile headless --help", "Failed to verify updated DeepSeek Harness")
        }
        if (verified.isFailure) {
            runCatching {
                runGuestCommand(
                    chrootExec = runtime.chrootExec,
                    command = "set -e; rm -rf /usr/local/lib/dsh; mv /usr/local/lib/dsh.previous /usr/local/lib/dsh",
                    displayCommand = "Restore previous DeepSeek Harness",
                    fraction = 0.95f,
                    timeoutMs = 60_000L,
                    onProgress = onProgress,
                    failureMessage = "Failed to restore previous DeepSeek Harness",
                )
            }
            verified.getOrThrow()
        }
        val previous = File(rootfs, "usr/local/lib/dsh.previous")
        if (!previous.deleteRecursively() && !RootfsRepair.safeDeleteRecursively(previous)) {
            Log.w("RuntimeInstaller", "Could not remove the previous DeepSeek Harness copy")
        }
        dshMarker.writeText(expectedVersion)
        dshAndroidCompatibilityMarker.writeText(DSH_ANDROID_COMPATIBILITY_VERSION)
    }

    private fun fetchAgyManifest(): JSONObject = JSONObject(
        fetchText("https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json"),
    )

    private fun isVersionNewer(candidate: String, current: String): Boolean {
        fun numericParts(value: String) = value.substringBefore('-').substringBefore('+')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }
        val left = numericParts(candidate)
        val right = numericParts(current)
        for (index in 0 until maxOf(left.size, right.size)) {
            val comparison = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
            if (comparison != 0) return comparison > 0
        }
        val candidatePreTag = candidate.substringBefore('+').substringAfter('-', "")
        val currentPreTag = current.substringBefore('+').substringAfter('-', "")
        if (candidatePreTag.isEmpty() || currentPreTag.isEmpty()) {
            // A release (no pre-release tag) always outranks a pre-release of the same numeric version.
            return currentPreTag.isNotEmpty() && candidatePreTag.isEmpty()
        }
        // Same numeric version, both pre-release (e.g. "rc.1" vs "rc.2"): compare identifiers in order.
        val candidateIds = candidatePreTag.split('.')
        val currentIds = currentPreTag.split('.')
        for (index in 0 until maxOf(candidateIds.size, currentIds.size)) {
            val candidateId = candidateIds.getOrNull(index)
            val currentId = currentIds.getOrNull(index)
            if (candidateId == null) return false
            if (currentId == null) return true
            val candidateNum = candidateId.toIntOrNull()
            val currentNum = currentId.toIntOrNull()
            val comparison = if (candidateNum != null && currentNum != null) {
                candidateNum.compareTo(currentNum)
            } else {
                candidateId.compareTo(currentId)
            }
            if (comparison != 0) return comparison > 0
        }
        return false
    }

    private fun migrateLegacyClaudeMarker() {
        if (claudeMarker.isFile) return
        val claude = File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/"))
        if (!claude.isFile) return
        val legacyVersion = sequenceOf(
            coreReadyMarker.readTextOrNull(),
            bundledClaudeMarker.readTextOrNull(),
        ).mapNotNull { it?.trim() }.firstOrNull { it.matches(CLAUDE_VERSION_PATTERN) } ?: return
        claudeMarker.writeText(legacyVersion)
    }

    private fun isSupportedCoreToolsVersion(): Boolean = coreToolsMarker.readTextOrNull() in setOf(
        CORE_TOOLS_VERSION,
        LEGACY_CORE_TOOLS_VERSION,
        PREVIOUS_CORE_TOOLS_VERSION,
        NEXTCRAFT_CORE_TOOLS_VERSION,
    )

    private fun pythonBundle(): RuntimeBundle =
        if (rootfsMarker.readTextOrNull() == LEGACY_ROOTFS_VERSION) LEGACY_PYTHON_BUNDLE else PYTHON_BUNDLE

    private fun isSupportedRootfsVersion(version: String?): Boolean = version in setOf(
        ROOTFS_VERSION,
        LEGACY_ROOTFS_VERSION,
    )

    private suspend fun ensureClaudeInstalled(
        chrootExec: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        migrateLegacyClaudeMarker()
        if (isAgentInstalled(AgentKind.CLAUDE_CODE)) return
        installRuntimeOverlay(
            bundle = CLAUDE_BUNDLE,
            message = "Installing Claude Code $CLAUDE_BUNDLED_VERSION…",
            from = fraction,
            to = 0.995f,
            onProgress = onProgress,
        )
        verifyGuest(chrootExec, "$CLAUDE_GUEST_PATH --version", "Failed to verify Claude Code")
        migrateLegacyClaudeMarker()
        require(claudeMarker.readTextOrNull() == CLAUDE_BUNDLED_VERSION) {
            "The Claude Code runtime bundle is incomplete"
        }
    }

    private suspend fun ensureAgyInstalled(
        chrootExec: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isAgentInstalled(AgentKind.ANTIGRAVITY)) return
        installRuntimeOverlay(
            bundle = AGY_BUNDLE,
            message = "Installing Antigravity CLI $AGY_VERSION…",
            from = fraction,
            to = 0.995f,
            onProgress = onProgress,
            forceEmbedded = isAssetAvailable("runtime/${AGY_BUNDLE.fileName}"),
        )
        verifyGuest(chrootExec, "$AGY_GUEST_PATH --version", "Failed to verify Antigravity CLI")
        agyMarker.writeText(AGY_VERSION)
        require(isAgentInstalled(AgentKind.ANTIGRAVITY)) {
            "Antigravity CLI installation is incomplete"
        }
    }

    private suspend fun ensureDshInstalled(
        chrootExec: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isAgentInstalled(AgentKind.DEEPSEEK_HARNESS)) {
            ensureDshAndroidCompatibility()
            return
        }
        val dshDir = File(rootfs, "usr/local/lib/dsh")
        val dshBackup = File(rootfs, "usr/local/lib/dsh.bak")
        val dshMarkerBackup = File(dshMarker.parentFile, "${dshMarker.name}.bak")
        val compatMarkerBackup = File(dshAndroidCompatibilityMarker.parentFile, "${dshAndroidCompatibilityMarker.name}.bak")
        File(rootfs, "usr/local/lib/dsh.updating").deleteRecursively()
        File(rootfs, "usr/local/lib/dsh.previous").deleteRecursively()
        dshBackup.deleteRecursively()
        dshMarkerBackup.delete()
        compatMarkerBackup.delete()
        if (dshDir.exists()) dshDir.renameTo(dshBackup)
        if (dshMarker.exists()) dshMarker.renameTo(dshMarkerBackup)
        if (dshAndroidCompatibilityMarker.exists()) dshAndroidCompatibilityMarker.renameTo(compatMarkerBackup)
        try {
            installRuntimeOverlay(
                bundle = DSH_BUNDLE,
                message = "Installing DeepSeek Harness $DSH_VERSION…",
                from = fraction,
                to = 0.995f,
                onProgress = onProgress,
            )
            ensureDshAndroidCompatibility()
            verifyGuest(chrootExec, "/usr/local/bin/dsh --profile headless --help", "Failed to verify DeepSeek Harness")
            require(isAgentInstalled(AgentKind.DEEPSEEK_HARNESS)) {
                "The DeepSeek Harness runtime bundle is incomplete"
            }
            dshBackup.deleteRecursively()
            dshMarkerBackup.delete()
            compatMarkerBackup.delete()
        } catch (e: Throwable) {
            dshDir.deleteRecursively()
            if (dshBackup.exists()) dshBackup.renameTo(dshDir)
            if (dshMarkerBackup.exists()) dshMarkerBackup.renameTo(dshMarker)
            if (compatMarkerBackup.exists()) compatMarkerBackup.renameTo(dshAndroidCompatibilityMarker)
            throw e
        }
    }

    /**
     * Ensures DSH uses COPYFILE_EXCL (`copyFile(tmp, finalPath, 1)`) for atomic no-clobber publication.
     */
    fun ensureDshAndroidCompatibility() {
        if (!isAgentInstalled(AgentKind.DEEPSEEK_HARNESS)) return
        val dshRoot = File(rootfs, "usr/local/lib/dsh")
        try {
            applyDshAndroidCompatibility(dshRoot, dshMarker.readTextOrNull().orEmpty())
        } catch (e: java.io.IOException) {
            // A tree installed by an earlier root npm is root-owned; take it back and retry once.
            if (!RootfsRepair.reclaimOwnership(dshRoot, AndroidProcess.myUid())) throw e
            applyDshAndroidCompatibility(dshRoot, dshMarker.readTextOrNull().orEmpty())
        }
        dshAndroidCompatibilityMarker.writeText(DSH_ANDROID_COMPATIBILITY_VERSION)
    }

    private fun applyDshAndroidCompatibility(dshRoot: File, version: String) {
        check(isSupportedDshVersion(version)) {
            "DeepSeek Harness $version is not compatible with this NextCraft Forge build"
        }
        val persistence = File(
            dshRoot,
            "node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
        )
        val localFs = File(
            dshRoot,
            "node_modules/@deepseek-ai/dsh-fs-local/lib/index.js",
        )
        patchDshHardLinkPublication(
            file = persistence,
            importBefore = "import { link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";",
            importAfter = "import { copyFile, link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";",
            callBefore = "await link(tmp, finalPath);",
            callAfter = "await copyFile(tmp, finalPath, 1);",
        )
        patchDshHardLinkPublication(
            file = localFs,
            importBefore = "import { chmod, link, lstat, mkdir, open, readFile, readdir, realpath, rename, rm, stat } from \"node:fs/promises\";",
            importAfter = "import { chmod, copyFile, link, lstat, mkdir, open, readFile, readdir, realpath, rename, rm, stat } from \"node:fs/promises\";",
            callBefore = "await linkFile(tempPath, absolutePath);",
            callAfter = "await copyFile(tempPath, absolutePath, 1);",
        )
    }

    private fun patchDshHardLinkPublication(
        file: File,
        importBefore: String,
        importAfter: String,
        callBefore: String,
        callAfter: String,
    ) {
        check(file.isFile) { "DeepSeek Harness compatibility file is missing: ${file.name}" }
        var source = file.readText()
        if (callAfter in source && importAfter in source) return
        check(callBefore in source && importBefore in source) {
            "Installed DeepSeek Harness source is not compatible with this NextCraft Forge build"
        }
        source = source.replace(importBefore, importAfter).replace(callBefore, callAfter)
        val staging = File(file.parentFile, "${file.name}.tmp")
        staging.writeText(source)
        if (!staging.renameTo(file)) {
            staging.copyTo(file, overwrite = true)
            staging.delete()
        }
    }

    /**
     * Installs one optional development stack inside Ubuntu Root Chroot.
     */
    suspend fun ensureStackInstalled(
        stack: DevStack,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        if (isStackInstalled(stack) && stack != DevStack.ANDROID) return
        applyStack(runtime.chrootExec, stack, 0.05f, 0.95f, onProgress)
        onProgress(RuntimeInstallProgress("${stack.label} stack is ready", 1f))
    }

    /**
     * Removes an optional development stack without touching projects or the core
     * Node.js/Git runtime.
     */
    suspend fun removeStack(
        stack: DevStack,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(stack != DevStack.WEB) { "Web (Node.js / npm) is part of the Core runtime and cannot be removed." }
        val runtime = installedRuntime()
        if (!isStackInstalled(stack)) return

        onProgress(RuntimeInstallProgress("Removing ${stack.label}…", 0.1f, indeterminate = true))
        when (stack) {
            DevStack.WEB -> Unit
            DevStack.PYTHON -> removePythonStack()
            DevStack.ANDROID -> removeAndroidStack()
            DevStack.CPP -> aptRemove(
                runtime.chrootExec,
                listOf("build-essential", "gcc", "g++", "make", "cmake", "gdb"),
                0.45f,
                onProgress,
            )
            DevStack.PHP -> {
                aptRemove(
                    runtime.chrootExec,
                    listOf("php-cli", "php-mbstring", "php-xml", "php-curl", "php-zip"),
                    0.45f,
                    onProgress,
                )
                removePath(File(rootfs, "usr/local/bin/composer"))
                removePath(File(rootfs, "root/.cache/composer"))
                removePath(File(rootfs, "root/.composer"))
            }
        }

        writeDevStackState(readDevStackState().apply { put(stack.name, false) })
        onProgress(RuntimeInstallProgress("Removed ${stack.label}", 1f))
    }

    private fun removePythonStack() {
        // The overlay is unmanaged by dpkg, so it is removed by path. Covers the current Ubuntu 24.04 bundle
        // (Python 3.12) and the legacy 20.04 bundle (Python 3.8). Shared libraries the overlay also ships
        // (libsqlite3, libreadline, libhistory) and tzdata files are left in place: other tools may use them.
        listOf(
            ".pocket-python-tools-version",
            ".nextcraft-python-tools-version",
            "usr/bin/python3",
            "usr/bin/python3.12",
            "usr/bin/python3.8",
            "usr/bin/pip",
            "usr/bin/pip3",
            "usr/bin/pip3.12",
            "usr/bin/pdb3",
            "usr/bin/pdb3.12",
            "usr/bin/pydoc3",
            "usr/bin/pydoc3.12",
            "usr/bin/pygettext3",
            "usr/bin/pygettext3.12",
            "usr/bin/py3clean",
            "usr/bin/py3compile",
            "usr/bin/py3versions",
            "usr/lib/aarch64-linux-gnu/libpython3.12.so.1",
            "usr/lib/aarch64-linux-gnu/libpython3.12.so.1.0",
            "usr/lib/aarch64-linux-gnu/libpython3.8.so.1",
            "usr/lib/aarch64-linux-gnu/libpython3.8.so.1.0",
            "usr/lib/python3",
            "usr/lib/python3.12",
            "usr/lib/python3.8",
            "usr/lib/valgrind/python3.supp",
            "usr/local/lib/python3.12",
            "usr/local/lib/python3.8",
            "etc/python3",
            "etc/python3.12",
            "usr/share/python3",
            "usr/share/python-wheels",
            "root/.cache/pip",
        ).forEach { removePath(File(rootfs, it)) }
    }

    /** Removes the current overlay layout and every previous one, so an upgrade does not leave an old JDK behind. */
    private fun removeAndroidStack() {
        AndroidToolchain.REMOVABLE_HOST_PATHS.forEach { removePath(File(rootfs, it)) }
        removeAndroidGradleProperty()
    }

    private fun removeAndroidGradleProperty() {
        val properties = File(rootfs, "root/.gradle/gradle.properties")
        if (!properties.isFile) return
        val remaining = properties.readLines().filterNot(::isManagedAndroidGradleProperty)
        if (remaining.isEmpty()) {
            properties.delete()
        } else {
            properties.writeText(remaining.joinToString("\n").trimEnd() + "\n")
        }
    }

    private fun removePath(file: File) {
        val isLink = java.nio.file.Files.isSymbolicLink(file.toPath())
        if (!file.exists() && !isLink) return
        val removed = if (file.isDirectory && !isLink) file.deleteRecursively() else file.delete()
        // Caches and packages created inside the chroot (Gradle, pip, Composer) are root-owned, so the app UID
        // cannot delete them; fall back to a root rm, which first checks that nothing is mounted below the path.
        if (!removed) {
            check(RootfsRepair.safeDeleteRecursively(file)) { "Could not remove ${file.name}" }
        }
    }

    private suspend fun applyStack(
        chrootExec: File,
        stack: DevStack,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        when (stack) {
            DevStack.WEB -> {
                onProgress(RuntimeInstallProgress("Checking Node.js and npm…", from))
                verifyGuest(chrootExec, "node --version && npm --version", "Failed to verify Node.js")
            }
            DevStack.PYTHON -> {
                installRuntimeOverlay(pythonBundle(), "Installing Python toolchain…", from, to, onProgress)
                // A failed check must fail the stack: returning normally reported "Python stack is ready" and left it
                // out of the failures that ensureInstalled reports.
                runCatching {
                    verifyGuest(chrootExec, "python3 --version && pip3 --version", "Failed to verify Python")
                }.onFailure { error ->
                    onProgress(
                        RuntimeInstallProgress(
                            "Python verification failed: ${error.message?.take(120).orEmpty()}",
                            to,
                        ),
                    )
                    throw error
                }
            }
            DevStack.ANDROID -> {
                installAndroidToolchain(chrootExec, from, to, onProgress)
            }
            DevStack.CPP -> {
                aptInstall(
                    chrootExec,
                    listOf("build-essential", "cmake", "gdb"),
                    "Installing C/C++ build toolchain…",
                    from,
                    onProgress,
                )
                verifyGuest(
                    chrootExec,
                    "gcc --version && g++ --version && make --version && cmake --version",
                    "Failed to verify C/C++ toolchain",
                )
            }
            DevStack.PHP -> {
                aptInstall(
                    chrootExec,
                    listOf("php-cli", "php-mbstring", "php-xml", "php-curl", "php-zip", "unzip"),
                    "Installing PHP and extensions…",
                    from,
                    onProgress,
                )
                installComposer(chrootExec, from, onProgress)
                verifyGuest(chrootExec, "php --version && composer --version", "Failed to verify PHP and Composer")
            }
        }
        writeDevStackState(readDevStackState().apply { put(stack.name, true) })
        onProgress(RuntimeInstallProgress("Installed ${stack.label}", to))
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun installComposer(
        chrootExec: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val composer = File(rootfs, "usr/local/bin/composer")
        if (composer.isFile) return
        onProgress(RuntimeInstallProgress("Downloading Composer…", fraction))
        downloads.mkdirs()
        val staged = File(downloads, "composer.phar")
        val checksum = COMPOSER_SHA256
        downloadVerified(
            "https://getcomposer.org/download/$COMPOSER_VERSION/composer.phar",
            staged,
            checksum,
        ) { _, _ -> }
        composer.parentFile?.mkdirs()
        val staging = File(composer.parentFile, ".composer.installing")
        try {
            staged.inputStream().use { input -> FileOutputStream(staging).use { input.copyTo(it) } }
            Os.chmod(staging.absolutePath, 0b111101101)
            Os.rename(staging.absolutePath, composer.absolutePath)
        } catch (e: Throwable) {
            staging.delete()
            throw e
        } finally {
            staged.delete()
        }
        onProgress(RuntimeInstallProgress("Installing Composer…", fraction))
    }

    private suspend fun installAndroidToolchain(
        chrootExec: File,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val marker = File(rootfs, "root/.pocket-android-tools-version")
        if (marker.readTextOrNull() != ANDROID_TOOLS_VERSION || !hasAndroidToolchainFiles(rootfs)) {
            // An older layout (different JDK/Gradle directories) would otherwise stay behind next to the new one.
            if (marker.readTextOrNull() != ANDROID_TOOLS_VERSION && marker.isFile) removeAndroidStack()
            installRuntimeOverlay(
                ANDROID_BUNDLE,
                "Installing Android SDK ${AndroidToolchain.COMPILE_SDK}, Build Tools ${AndroidToolchain.BUILD_TOOLS_VERSION}, " +
                    "Gradle ${AndroidToolchain.GRADLE_VERSION}, and Maven cache…",
                from,
                to,
                onProgress,
            )
            makeAndroidToolsExecutable(rootfs)
            marker.parentFile?.mkdirs()
            marker.writeText(ANDROID_TOOLS_VERSION)
        }
        writeAndroidGradleConfiguration(rootfs)

        val jdk = AndroidToolchain.JDK_MAJOR
        verifyGuest(
            chrootExec,
            "java -version 2>&1 | grep -E '\"$jdk\\.|version $jdk' && " +
                "gradle --version && aapt2 version && test -f ${AndroidToolchain.ANDROID_JAR_GUEST_PATH}",
            "Failed to verify Android SDK, Gradle, and Java $jdk",
        )
    }

    private fun hasAndroidToolchainFiles(runtimeRootfs: File): Boolean =
        File(runtimeRootfs, AndroidToolchain.ANDROID_JAR_HOST_PATH).isFile &&
            File(runtimeRootfs, AndroidToolchain.AAPT2_HOST_PATH).isFile &&
            File(runtimeRootfs, AndroidToolchain.GRADLE_LAUNCHER_HOST_PATH).isFile &&
            File(runtimeRootfs, AndroidToolchain.MAVEN_HOST_DIR).let { it.isDirectory && !it.list().isNullOrEmpty() }

    /** Unpack progress: bytes are the compressed archive consumed, so the UI can show MB/s like a download. */
    private fun unpackProgress(message: String, from: Float, to: Float, done: Long, total: Long) =
        RuntimeInstallProgress(
            message = message,
            fraction = from + (to - from) * (if (total > 0L) done.toFloat() / total else 0f),
            downloadedBytes = done,
            totalBytes = total.takeIf { it > 0L },
            event = RuntimeInstallEvent.DOWNLOAD,
            unpacking = true,
        )

    private suspend fun installRuntimeOverlay(
        bundle: RuntimeBundle,
        message: String,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
        forceEmbedded: Boolean = false,
    ) {
        val preferEmbedded = forceEmbedded || isAssetAvailable("runtime/${bundle.fileName}")
        val archive = obtainRuntimeBundle(bundle, preferEmbedded = preferEmbedded, from = from, to = to * 0.8f + from * 0.2f, onProgress = onProgress)
        onProgress(RuntimeInstallProgress(message, to * 0.8f + from * 0.2f, indeterminate = true))
        try {
            extractZstdTar(archive, rootfs) { done, total ->
                onProgress(unpackProgress(message, to * 0.8f + from * 0.2f, to, done, total))
            }
            stripMacosMetadataArtifacts(rootfs)
        } finally {
            if (archive.parentFile == downloads) archive.delete()
        }
        onProgress(RuntimeInstallProgress("Installed ${bundle.label} runtime bundle", to))
    }

    private fun stripMacosMetadataArtifacts(root: File, skipTopLevel: Set<String> = emptySet()) {
        if (!root.isDirectory) return
        val queue = ArrayDeque<File>()
        queue.add(root)
        var removed = 0
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (current == root && child.name in skipTopLevel) continue
                if (child.name.startsWith("._")) {
                    if (child.isDirectory) child.deleteRecursively() else child.delete()
                    removed++
                } else if (child.isDirectory && !java.nio.file.Files.isSymbolicLink(child.toPath())) {
                    queue.add(child)
                }
            }
        }
        if (removed > 0) {
            Log.i("RuntimeInstaller", "Stripped $removed macOS metadata artifacts")
        }
    }

    private fun isAssetAvailable(assetPath: String): Boolean =
        runCatching {
            context.assets.open(assetPath).close()
            true
        }.getOrDefault(false)

    /** Fails early with an actionable message instead of an opaque IOException halfway through extraction. */
    private fun requireFreeSpaceForBundle(bundle: RuntimeBundle, alreadyDownloaded: Boolean) {
        val free = context.filesDir.usableSpace
        if (free <= 0L) return // unknown (some filesystems report 0); let the real write decide
        val factor = if (alreadyDownloaded) BUNDLE_EXTRACT_SPACE_FACTOR else BUNDLE_DOWNLOAD_SPACE_FACTOR
        val needed = bundle.compressedBytes * factor
        check(free >= needed) {
            "Not enough free storage for the ${bundle.label} bundle: about ${needed / MEGABYTE} MB needed, " +
                "${free / MEGABYTE} MB available. Free some space and retry."
        }
    }

    private suspend fun obtainRuntimeBundle(
        bundle: RuntimeBundle,
        preferEmbedded: Boolean,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): File {
        downloads.mkdirs()
        val destination = File(downloads, bundle.fileName)
        requireFreeSpaceForBundle(bundle, alreadyDownloaded = destination.isFile)
        if (preferEmbedded && isAssetAvailable("runtime/${bundle.fileName}")) {
            val loadingLabel = "Loading ${bundle.label} bundle…"
            onProgress(RuntimeInstallProgress(loadingLabel, from, 0, bundle.compressedBytes))
            val temporary = File(downloads, "${bundle.fileName}.part")
            context.assets.open("runtime/${bundle.fileName}").use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        val ratio = (copied.toFloat() / bundle.compressedBytes).coerceIn(0f, 1f)
                        onProgress(RuntimeInstallProgress(loadingLabel, from + ratio * (to - from), copied, bundle.compressedBytes))
                    }
                }
            }
            require(digest(temporary, "SHA-256").equals(bundle.sha256, ignoreCase = true)) {
                "${bundle.label} bundle checksum mismatch"
            }
            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) { "Could not stage the ${bundle.label} bundle" }
            return destination
        }

        val url = "${bundle.baseUrl}/${bundle.fileName}"
        val downloadingLabel = "Downloading ${bundle.label} bundle…"
        downloadVerified(url, destination, bundle.sha256) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(downloadingLabel, from + ratio * (to - from), downloaded, total.takeIf { it > 0 }))
        }
        return destination
    }

    private fun makeAndroidToolsExecutable(runtimeRootfs: File) {
        val buildTools = File(runtimeRootfs, AndroidToolchain.BUILD_TOOLS_HOST_DIR)
        listOf("aapt", "aapt2", "aidl", "apksigner", "d8", "dexdump", "split-select", "zipalign")
            .map { File(buildTools, it) }
            .plus(File(runtimeRootfs, AndroidToolchain.GRADLE_LAUNCHER_HOST_PATH))
            .filter(File::isFile)
            .forEach { Os.chmod(it.absolutePath, 0b111101101) }
    }

    private fun writeAndroidGradleInitScript(runtimeRootfs: File) {
        val script = File(runtimeRootfs, AndroidToolchain.GRADLE_INIT_SCRIPT_GUEST_PATH.removePrefix("/"))
        script.parentFile?.mkdirs()
        script.writeText(ANDROID_GRADLE_INIT_SCRIPT)
    }

    @Synchronized
    private fun writeAndroidGradleConfiguration(runtimeRootfs: File) {
        val aapt2 = File(runtimeRootfs, ANDROID_AAPT2_HOST_PATH)
        if (!aapt2.isFile) return

        writeAndroidGradleInitScript(runtimeRootfs)
        val gradleDir = File(runtimeRootfs, "root/.gradle").apply { mkdirs() }
        val properties = File(gradleDir, "gradle.properties")
        val existingLines = properties.readTextOrNull()?.lineSequence()?.toList().orEmpty()
        val managedLines = listOf(
            "$ANDROID_AAPT2_PROPERTY=$ANDROID_AAPT2_GUEST_PATH",
            "$GRADLE_JVMARGS_PROPERTY=-Xmx512m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8",
            "$GRADLE_WORKERS_PROPERTY=2",
            "$GRADLE_PARALLEL_PROPERTY=false",
            "$KOTLIN_EXECUTION_PROPERTY=in-process",
        )
        val updatedLines = existingLines.filterNot(::isManagedAndroidGradleProperty) + managedLines
        if (existingLines == updatedLines) return

        val temporary = File(gradleDir, "gradle.properties.nextcraft.tmp")
        try {
            temporary.writeText(updatedLines.joinToString("\n").trimEnd() + "\n")
            Os.rename(temporary.absolutePath, properties.absolutePath)
        } catch (e: Throwable) {
            temporary.delete()
            throw e
        }
    }

    private fun isManagedAndroidGradleProperty(line: String): Boolean =
        MANAGED_ANDROID_GRADLE_PROPERTIES.any { key ->
            Regex("^\\s*${Regex.escape(key)}\\s*[:=].*$").matches(line)
        }

    fun migrateLegacyToolMarkers() {
        if (!File(rootfs, "usr/bin/bash").isFile) return
        if (languageToolsMarker.readTextOrNull() != LANGUAGE_TOOLS_VERSION) return
        if (coreToolsMarker.readTextOrNull() != CORE_TOOLS_VERSION) coreToolsMarker.writeText(CORE_TOOLS_VERSION)
        val state = readDevStackState()
        DevStack.entries.forEach { stack -> if (!state.containsKey(stack.name)) state[stack.name] = true }
        writeDevStackState(state)
    }

    fun installedStacks(): Set<DevStack> = readDevStackState()
        .filterValues { it }
        .keys
        .mapNotNull { name -> runCatching { DevStack.valueOf(name) }.getOrNull() }
        .filter(::isStackInstalled)
        .toSet()

    private fun guestExecutableOrSymlinkExists(relativePath: String): Boolean {
        val file = File(rootfs, relativePath.removePrefix("/"))
        return file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath())
    }

    fun isStackInstalled(stack: DevStack): Boolean {
        if (readDevStackState()[stack.name] != true) return false
        return when (stack) {
            DevStack.WEB -> true
            DevStack.PYTHON -> guestExecutableOrSymlinkExists("usr/bin/python3") && guestExecutableOrSymlinkExists("usr/bin/pip3")
            DevStack.CPP -> guestExecutableOrSymlinkExists("usr/bin/gcc") && guestExecutableOrSymlinkExists("usr/bin/g++")
            DevStack.PHP -> guestExecutableOrSymlinkExists("usr/bin/php") && File(rootfs, "usr/local/bin/composer").isFile
            DevStack.ANDROID ->
                File(rootfs, "root/.pocket-android-tools-version").readTextOrNull() == ANDROID_TOOLS_VERSION &&
                    hasAndroidToolchainFiles(rootfs) &&
                    File(rootfs, AndroidToolchain.GRADLE_INIT_SCRIPT_GUEST_PATH.removePrefix("/")).isFile
        }
    }

    private fun readDevStackState(): MutableMap<String, Boolean> {
        if (!devStacksFile.isFile) return mutableMapOf()
        return runCatching {
            val obj = JSONObject(devStacksFile.readText())
            mutableMapOf<String, Boolean>().apply {
                DevStack.entries.forEach { stack ->
                    if (obj.has(stack.name)) put(stack.name, obj.optBoolean(stack.name))
                }
            }
        }.getOrDefault(mutableMapOf())
    }

    private fun writeDevStackState(state: Map<String, Boolean>) {
        devStacksFile.parentFile?.mkdirs()
        val obj = JSONObject()
        state.forEach { (name, value) -> obj.put(name, value) }
        // A truncated file reads back as "no stacks installed" and makes the next setup reinstall everything.
        writeRootfsFileAtomically(devStacksFile, obj.toString())
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun installNodeIfNeeded(
        chrootExec: File,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (File(rootfs, "usr/local/bin/node").exists()) return
        val nodeDownloadLabel = "Downloading Node.js $NODE_VERSION…"
        onProgress(RuntimeInstallProgress(nodeDownloadLabel, from))
        downloads.mkdirs()
        val nodeFileName = "node-$NODE_VERSION-linux-arm64.tar.gz"
        val nodeBaseUrl = "https://nodejs.org/dist/$NODE_VERSION"
        val checksum = NODE_ARM64_SHA256
        val nodeArchive = File(downloads, nodeFileName)
        downloadVerified("$nodeBaseUrl/$nodeFileName", nodeArchive, checksum) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(
                RuntimeInstallProgress(
                    nodeDownloadLabel,
                    from + ratio * (to - from),
                    downloaded,
                    total.takeIf { it > 0 },
                ),
            )
        }
        onProgress(RuntimeInstallProgress("Installing Node.js $NODE_VERSION…", to))
        val nodeStaging = File(runtimeDir, "node.installing")
        try {
            nodeStaging.deleteRecursively()
            nodeStaging.mkdirs()
            extractNodeArchive(nodeArchive, nodeStaging)
            val nodeHome = File(rootfs, "usr/local/lib/nodejs")
            nodeHome.deleteRecursively()
            nodeHome.parentFile?.mkdirs()
            check(nodeStaging.renameTo(nodeHome)) { "Could not activate Node.js" }
            val localBin = File(rootfs, "usr/local/bin").apply { mkdirs() }
            listOf("node", "npm", "npx", "corepack").forEach { command ->
                val link = File(localBin, command)
                if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
                Os.symlink("../lib/nodejs/bin/$command", link.absolutePath)
            }
        } catch (e: Throwable) {
            nodeStaging.deleteRecursively()
            throw e
        } finally {
            nodeArchive.delete()
        }
    }

    private suspend fun aptInstall(
        chrootExec: File,
        packages: List<String>,
        message: String,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        onProgress(RuntimeInstallProgress(message, fraction))
        aptInstallInternal(chrootExec, packages, fraction, onProgress)
    }

    private suspend fun runSystemMaintenance(
        chrootExec: File,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        onProgress(
            RuntimeInstallProgress(
                message = "Updating Ubuntu packages inside Root Chroot…",
                fraction = 0.69f,
                indeterminate = true,
            ),
        )
        writeResolver()
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg --configure -a && " +
            "apt-get -o DPkg::Lock::Timeout=120 -f install -y && " +
            "apt-get -o DPkg::Lock::Timeout=120 update && " +
            "apt-get -o DPkg::Lock::Timeout=120 upgrade -y"
        runGuestCommand(
            chrootExec = chrootExec,
            command = command,
            displayCommand = "dpkg --configure -a && apt-get -f install -y && apt-get update && apt-get upgrade -y",
            fraction = 0.69f,
            timeoutMs = 35 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Ubuntu system maintenance failed inside Root Chroot",
        )
    }

    private suspend fun aptInstallInternal(
        chrootExec: File,
        packages: List<String>,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(packages.isNotEmpty()) { "No packages selected" }
        writeResolver()
        val packageNames = packages.joinToString(" ")
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg --configure -a && " +
            "apt-get -o DPkg::Lock::Timeout=120 -f install -y && " +
            "apt-get -o DPkg::Lock::Timeout=120 update && " +
            "apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends $packageNames && " +
            "apt-get clean && rm -rf /var/lib/apt/lists/*"
        runGuestCommand(
            chrootExec = chrootExec,
            command = command,
            displayCommand = "dpkg --configure -a && apt-get -f install -y && apt-get install -y $packageNames",
            fraction = fraction,
            timeoutMs = 30 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Could not install packages: $packageNames",
        )
    }

    private suspend fun aptRemove(
        chrootExec: File,
        packages: List<String>,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(packages.isNotEmpty()) { "No packages selected" }
        val packageNames = packages.joinToString(" ")
        // The stack packages (php-cli, build-essential, gcc, ...) are metapackages: purging them alone leaves the real
        // binaries (php8.3-cli, gcc-13, ...) installed, so autoremove the dependencies that were pulled in with them.
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "apt-get -o DPkg::Lock::Timeout=120 purge -y $packageNames && " +
            "apt-get -o DPkg::Lock::Timeout=120 autoremove --purge -y && " +
            "apt-get clean && rm -rf /var/lib/apt/lists/*"
        runGuestCommand(
            chrootExec = chrootExec,
            command = command,
            displayCommand = "apt-get purge -y $packageNames && apt-get autoremove --purge -y",
            fraction = fraction,
            timeoutMs = 20 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Could not remove packages: $packageNames",
        )
    }

    private suspend fun runGuestCommand(
        chrootExec: File,
        command: String,
        displayCommand: String,
        fraction: Float,
        timeoutMs: Long,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
        failureMessage: String,
    ) {
        onProgress(
            RuntimeInstallProgress(
                message = displayCommand,
                fraction = fraction,
                terminalLine = "root@forge:~# $displayCommand",
                indeterminate = true,
                event = RuntimeInstallEvent.COMMAND,
            ),
        )
        val running = process(
            chrootExec = chrootExec,
            rootfs = rootfs,
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
            guestWorkspacePath = "/root",
        )
        runCatching { running.outputStream.close() }
        val native = running as? NativeSpawnProcess
        val collected = StringBuilder()
        try {
            withTimeout(timeoutMs) {
                var offset = 0L
                var pending = ""
                while (running.isAlive || (native?.outputFile?.length() ?: 0L) > offset) {
                    coroutineContext.ensureActive()
                    val file = native?.outputFile
                    if (file != null && file.length() > offset) {
                        RandomAccessFile(file, "r").use { input ->
                            val length = input.length()
                            if (length > offset) {
                                input.seek(offset)
                                val available = (length - offset).coerceAtMost(256 * 1024).toInt()
                                val bytes = ByteArray(available)
                                val read = input.read(bytes)
                                if (read > 0) {
                                    val usable = completeUtf8PrefixLength(bytes, read).let {
                                        if (it > 0 || !running.isAlive) (if (it > 0) it else read) else 0
                                    }
                                    if (usable > 0) {
                                        offset += usable
                                        pending += String(bytes, 0, usable, Charsets.UTF_8).replace('\r', '\n')
                                    }
                                }
                            }
                        }
                        val parts = pending.split('\n')
                        pending = parts.last()
                        for (raw in parts.dropLast(1)) {
                            val line = sanitizeTerminalLine(raw)
                            if (line.isNotBlank()) {
                                collected.appendLine(line)
                                if (collected.length > MAX_COLLECTED_OUTPUT) {
                                    collected.delete(0, collected.length - MAX_COLLECTED_OUTPUT)
                                }
                                onProgress(
                                    RuntimeInstallProgress(
                                        message = line,
                                        fraction = fraction,
                                        terminalLine = line,
                                        indeterminate = true,
                                        event = RuntimeInstallEvent.OUTPUT,
                                    ),
                                )
                            }
                        }
                    } else {
                        delay(80)
                    }
                }
                sanitizeTerminalLine(pending).takeIf(String::isNotBlank)?.let { line ->
                    collected.appendLine(line)
                    onProgress(RuntimeInstallProgress(line, fraction, terminalLine = line, indeterminate = true, event = RuntimeInstallEvent.OUTPUT))
                }
            }
        } catch (e: TimeoutCancellationException) {
            // TimeoutCancellationException is a CancellationException: left as-is the setup service would
            // report "paused" instead of a failure.
            throw IllegalStateException("$failureMessage: timed out after ${timeoutMs / 60_000L} min", e)
        } finally {
            if (running.isAlive) {
                running.destroy()
                repeat(10) {
                    if (!running.isAlive) return@repeat
                    runCatching { Thread.sleep(50) }
                }
                if (running.isAlive) {
                    running.destroyForcibly()
                }
            }
            native?.outputFile?.delete()
        }
        val exit = running.waitFor()
        onProgress(
            RuntimeInstallProgress(
                message = if (exit == 0) "Command completed" else "Command failed (exit $exit)",
                fraction = fraction,
                terminalLine = "[exit $exit] $displayCommand",
                event = RuntimeInstallEvent.COMMAND_COMPLETED,
            ),
        )
        check(exit == 0) { actionableProcessError(collected.toString(), failureMessage) }
    }

    private fun sanitizeTerminalLine(raw: String): String = raw
        .replace(ANSI_ESCAPE, "")
        .filter { it == '\t' || it.code >= 32 }
        .take(MAX_TERMINAL_LINE)

    private suspend fun verifyGuest(chrootExec: File, command: String, failureMessage: String) {
        val verify = process(
            chrootExec = chrootExec,
            rootfs = rootfs,
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
            guestWorkspacePath = "/root",
        )
        runCatching { verify.outputStream.close() }
        try {
            withTimeout(60_000L) {
                while (verify.isAlive) delay(50)
            }
            val exit = verify.waitFor()
            val output = (verify as? NativeSpawnProcess)?.outputFile
                ?.let(::readProcessOutputSafely)
                .orEmpty()
                .trim()
            check(exit == 0) { actionableProcessError(output, failureMessage) }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("$failureMessage: timed out", e)
        } finally {
            if (verify.isAlive) {
                verify.destroy()
                verify.destroyForcibly()
            }
            runCatching { verify.waitFor() }
            (verify as? NativeSpawnProcess)?.outputFile?.delete()
        }
    }

    /**
     * Restores merged-/usr compatibility links (`bin -> usr/bin`, `lib -> usr/lib`, `sbin -> usr/sbin`)
     * inside the Ubuntu rootfs without replacing real directories.
     */
    private fun ensureRootfsCompatibilityLinks(targetRootfs: File = rootfs): Boolean {
        if (!targetRootfs.isDirectory) return false
        val links = mapOf(
            "bin" to "usr/bin",
            "lib" to "usr/lib",
            "sbin" to "usr/sbin",
        )
        return runCatching {
            links.forEach { (name, destination) ->
                val link = File(targetRootfs, name)
                val path = link.toPath()
                if (java.nio.file.Files.isSymbolicLink(path)) {
                    if (java.nio.file.Files.readSymbolicLink(path).toString() != destination) {
                        java.nio.file.Files.delete(path)
                        Os.symlink(destination, link.absolutePath)
                    }
                } else if (link.exists()) {
                    check(link.isDirectory) { "Linux /$name is not a directory or symbolic link" }
                } else {
                    Os.symlink(destination, link.absolutePath)
                }
            }
            File(targetRootfs, "usr/bin/env").canExecute() &&
                File(targetRootfs, "usr/bin/bash").canExecute() &&
                File(targetRootfs, "lib/ld-linux-aarch64.so.1").exists()
        }.onFailure {
            Log.e("RuntimeInstaller", "Could not repair Linux compatibility links", it)
        }.getOrDefault(false)
    }

    /**
     * Ensures Android paranoid-network socket groups (`aid_inet` 3003, `aid_net_raw` 3004)
     * and APT sandbox root configuration are present inside the chroot rootfs.
     */
    private fun ensureAndroidSocketGroupConfiguration(targetRootfs: File = rootfs) {
        runCatching {
            val groupFile = File(targetRootfs, "etc/group")
            if (groupFile.isFile) {
                var groupText = groupFile.readText()
                var changed = false
                if (!groupText.lineSequence().any { it.startsWith("aid_inet:") }) {
                    if (!groupText.endsWith("\n")) groupText += "\n"
                    groupText += "aid_inet:x:3003:root,_apt\n"
                    changed = true
                }
                if (!groupText.lineSequence().any { it.startsWith("aid_net_raw:") }) {
                    if (!groupText.endsWith("\n")) groupText += "\n"
                    groupText += "aid_net_raw:x:3004:root,_apt\n"
                    changed = true
                }
                if (changed) groupFile.writeText(groupText)
            }
            val aptConf = File(targetRootfs, "etc/apt/apt.conf.d/99android-chroot")
            aptConf.parentFile?.mkdirs()
            val expectedAptConf = "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\n"
            if (aptConf.readTextOrNull() != expectedAptConf.trim()) {
                aptConf.writeText(expectedAptConf)
            }
        }
    }

    private fun isValidCoreRootfs(targetRootfs: File): Boolean =
        ensureRootfsCompatibilityLinks(targetRootfs) &&
            File(targetRootfs, "usr/bin/bash").isFile &&
            isSupportedRootfsVersion(File(targetRootfs, ".pocket-rootfs-version").readTextOrNull())

    private fun actionableProcessError(output: String, fallback: String): String {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val chrootError = lines.firstOrNull {
            it.startsWith("chroot:") || it.startsWith("mount:") || it.contains("Permission denied", ignoreCase = true)
        }
        return chrootError ?: output.trim().takeLast(1_000).ifBlank { fallback }
    }

    private fun readProcessOutputSafely(file: File): String = runCatching {
        file.readTailText(MAX_PROCESS_OUTPUT_BYTES)
    }.getOrElse { error ->
        Log.w("RuntimeInstaller", "Could not decode process output as UTF-8: ${error.message}")
        ""
    }

    suspend fun initializeExisting(onProgress: suspend (RuntimeInstallProgress) -> Unit): InstalledRuntime {
        val installed = installedRuntime()
        onProgress(RuntimeInstallProgress("Checking Root Chroot runtime files…", 0.15f))
        ensureChrootScripts()
        ensureAndroidSocketGroupConfiguration(rootfs)
        writeResolver()
        ensureSettingsAndHooks()
        File(context.filesDir, "runtime-bridge").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        onProgress(RuntimeInstallProgress("Preparing runtime IPC bridge…", 0.42f))
        disablePhantomProcessKillerViaRoot()
        check(File(rootfs, "usr/local/bin/node").canExecute()) { "Core runtime is missing Node.js" }
        check(File(rootfs, "usr/bin/git").canExecute()) { "Core runtime is missing Git" }
        onProgress(RuntimeInstallProgress("Root Chroot runtime ready", 1f))
        return installed
    }

    /**
     * Spawns a command inside the Ubuntu 24.04 Root Chroot via `su -mm` and `chroot-exec.sh`.
     * No PRoot binary or ptrace translation is used.
     */
    @Suppress("UNUSED_PARAMETER")
    fun process(
        chrootExec: File,
        rootfs: File,
        workspace: File,
        environment: Map<String, String>,
        guestCommand: List<String>,
        guestWorkspacePath: String = "/workspace",
        emulateHardLinks: Boolean = true,
        outputFile: File = File(context.cacheDir, "runtime-output-${System.nanoTime()}.log"),
        pseudoTerminal: Boolean = false,
        ptyRows: Int = 40,
        ptyColumns: Int = 120,
    ): Process {
        val (envFile, suCommand) = prepareChrootShellCommand(
            workspace = workspace,
            guestWorkspacePath = guestWorkspacePath,
            environment = environment,
            guestCommand = guestCommand,
            chrootExec = chrootExec,
            targetRootfs = rootfs,
        )

        val spawned = try {
            NativeSpawnProcess.start(
                argv = listOf("su", "-mm", "-c", suCommand),
                environment = emptyMap(),
                cwd = context.filesDir.absolutePath,
                outputFile = outputFile,
                pseudoTerminal = pseudoTerminal,
                ptyRows = ptyRows,
                ptyColumns = ptyColumns,
            )
        } catch (error: Throwable) {
            envFile.delete()
            throw error
        }
        // The `rm -f` inside suCommand never runs when su is denied or exec fails, which would leave API keys
        // on disk until the next launch's 10-minute purge. Remove the file as soon as the process is gone.
        Thread({
            while (spawned.isAlive) Thread.sleep(ENV_CLEANUP_POLL_MS)
            envFile.delete()
        }, "forge-env-cleanup").apply { isDaemon = true; start() }
        return spawned
    }

    /**
     * Prepares a one-time mode-0600 environment script and returns `(envFile, suCommand)` for
     * executing [guestCommand] inside the Root Chroot with all SDK, PATH, and provider variables
     * exported and immediately unlinked before `exec`.
     */
    fun prepareChrootShellCommand(
        workspace: File,
        guestWorkspacePath: String = "/workspace",
        environment: Map<String, String> = emptyMap(),
        guestCommand: List<String>,
        chrootExec: File = chrootExecFile,
        targetRootfs: File = rootfs,
    ): Pair<File, String> {
        check(ensureRootfsCompatibilityLinks(targetRootfs)) { "Core runtime has an invalid Linux filesystem layout" }
        require(
            guestWorkspacePath == "/root" ||
                guestWorkspacePath == "/workspace" ||
                Regex("^/workspace/[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$").matches(guestWorkspacePath),
        ) { "Invalid project workspace path" }
        ensureChrootScripts()
        writeResolver()
        workspace.mkdirs()
        File(targetRootfs, guestWorkspacePath.removePrefix("/")).mkdirs()
        writeAndroidGradleConfiguration(targetRootfs)
        if (guestWorkspacePath != "/root") {
            ensureWorkspaceTrust(guestWorkspacePath, environment)
        }
        val bridge = File(context.filesDir, "runtime-bridge").apply { mkdirs() }
        File(context.filesDir, "runtime-bridge-responses").mkdirs()
        val appUid = AndroidProcess.myUid().toString()

        val guestEnv = buildMap {
            put("HOME", "/root")
            put("USER", "root")
            put("LOGNAME", "root")
            val androidReady = File(targetRootfs, "root/.pocket-android-tools-version").readTextOrNull() == ANDROID_TOOLS_VERSION
            val basePath = "/opt/nextcraft/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            if (androidReady) {
                put("ANDROID_HOME", AndroidToolchain.SDK_GUEST_DIR)
                put("ANDROID_SDK_ROOT", AndroidToolchain.SDK_GUEST_DIR)
                put("GRADLE_HOME", AndroidToolchain.GRADLE_HOME_GUEST_DIR)
                put("GRADLE_USER_HOME", "/root/.gradle")
                put("ORG_GRADLE_PROJECT_android_aapt2FromMavenOverride", AndroidToolchain.AAPT2_GUEST_PATH)
                put("PATH", "${AndroidToolchain.GUEST_PATH_PREFIX}:$basePath")
            } else {
                put("PATH", basePath)
            }
            put("LANG", "C.UTF-8")
            put("TERM", "xterm-256color")
            put("GLIBC_TUNABLES", "glibc.pthread.rseq=0")
            putAll(environment)
        }

        // Write environment variables (including sensitive API keys/OAuth tokens) to a
        // one-time mode-0600 file inside filesDir/runtime/env and immediately unlink it
        // inside the root shell before `exec`, so secrets NEVER appear in `ps aux` or
        // `/proc/<pid>/cmdline` while surviving Magisk/KernelSU/APatch su environ sanitization.
        val runtimeEnvDir = File(runtimeDir, "env").apply {
            mkdirs()
            runCatching { Os.chmod(absolutePath, 0b111000000) } // 0700
        }
        // A launch that never reached `rm -f` (su denied, process killed) leaves API keys on
        // disk. Purge anything old enough that it cannot belong to a launch in progress.
        runtimeEnvDir.listFiles { f -> f.name.startsWith(".env-") }
            ?.filter { System.currentTimeMillis() - it.lastModified() > 10 * 60 * 1_000L }
            ?.forEach { it.delete() }
        val envFile = File(runtimeEnvDir, ".env-${java.util.UUID.randomUUID()}.sh")
        val envScript = buildString {
            for ((key, value) in guestEnv) {
                if (VALID_ENV_NAME.matches(key)) {
                    append("export ").append(key).append('=').append(shellSingleQuote(value)).append('\n')
                }
            }
        }
        val envBytes = envScript.toByteArray(Charsets.UTF_8)
        runCatching {
            val fd = Os.open(
                envFile.absolutePath,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC or OsConstants.O_EXCL,
                0b110000000,
            )
            FileOutputStream(fd).use { it.write(envBytes) }
        }.onFailure {
            envFile.writeText(envScript)
            runCatching { Os.chmod(envFile.absolutePath, 0b110000000) } // 0600
        }

        val quotedEnvFile = shellSingleQuote(envFile.absolutePath)
        val chrootArgs = listOf(
            chrootExec.absolutePath,
            targetRootfs.absolutePath,
            workspace.absolutePath,
            guestWorkspacePath,
            bridge.absolutePath,
            appUid,
        ) + guestCommand
        val suCommand = ". $quotedEnvFile; rm -f $quotedEnvFile; exec " +
            chrootArgs.joinToString(" ") { shellSingleQuote(it) }
        return envFile to suCommand
    }

    fun ensureSettingsAndHooks() {
        val hook = File(rootfs, "opt/pocket/permission-hook.sh")
        hook.parentFile?.mkdirs()
        // Hands every permission request to the app through the shared bridge directory and
        // waits for the user's/guardrail decision. The app classifies risk, auto-answers
        // SAFE/REVIEW and prompts for HIGH. No answer within the timeout means deny.
        // Written atomically: a Claude session may be executing this script, and truncating it in place makes the hook
        // answer nothing, which Claude Code treats as a refusal.
        writeRootfsFileAtomically(
            hook,
            """#!/bin/sh
BRIDGE="${'$'}{NEXTCRAFT_BRIDGE_DIR:-/pocketdev-bridge}"
RESP="${'$'}{NEXTCRAFT_RESPONSE_DIR:-/pocketdev-bridge-responses}"
ID="${'$'}(cat /proc/sys/kernel/random/uuid 2>/dev/null || echo "${'$'}${'$'}-${'$'}(date +%s)")"
DECISION=deny
if [ -d "${'$'}BRIDGE" ] && cat > "${'$'}BRIDGE/${'$'}ID.request.tmp" && mv "${'$'}BRIDGE/${'$'}ID.request.tmp" "${'$'}BRIDGE/${'$'}ID.request"; then
    i=0
    while [ "${'$'}i" -lt 600 ]; do
        if [ -f "${'$'}RESP/${'$'}ID.response" ]; then
            [ "${'$'}(cat "${'$'}RESP/${'$'}ID.response")" = "allow" ] && DECISION=allow
            break
        fi
        sleep 0.1
        i=${'$'}((i + 1))
    done
    rm -f "${'$'}BRIDGE/${'$'}ID.request" "${'$'}BRIDGE/${'$'}ID.request.tmp"
else
    cat > /dev/null
fi
printf '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"%s"}}}\n' "${'$'}DECISION"
""",
            mode = 0b111101101,
        )

        // BROWSER handler for `claude auth login`: there is no browser in the chroot, so the sign-in URL is handed to
        // the app through the bridge directory and opened in the Android browser. The browser then redirects to
        // http://localhost:<port>/callback, which the CLI (same network namespace) is listening on.
        val openUrl = File(rootfs, "opt/pocket/open-url.sh")
        writeRootfsFileAtomically(
            openUrl,
            """#!/bin/sh
BRIDGE="${'$'}{NEXTCRAFT_BRIDGE_DIR:-/pocketdev-bridge}"
[ -n "${'$'}1" ] && [ -d "${'$'}BRIDGE" ] || exit 0
printf '%s\n' "${'$'}1" > "${'$'}BRIDGE/claude-login-url.tmp" && mv "${'$'}BRIDGE/claude-login-url.tmp" "${'$'}BRIDGE/claude-login-url"
""",
            mode = 0b111101101,
        )

        val settingsContent = JSONObject()
            .put("disableAllHooks", false)
            .put(
                "permissions",
                JSONObject()
                    .put("allow", claudeWorkspaceToolRules())
                    .put("defaultMode", "acceptEdits"),
            )
            .put(
                "hooks",
                JSONObject().put(
                    "PermissionRequest",
                    JSONArray().put(
                        JSONObject()
                            .put("matcher", "Bash|Edit|Write|NotebookEdit")
                            .put(
                                "hooks",
                                JSONArray().put(
                                    JSONObject().put("type", "command").put("command", "/opt/pocket/permission-hook.sh"),
                                ),
                            ),
                    ),
                ),
            )
            .toString()

        val settingsPaths = listOf(
            File(rootfs, "root/.claude/pocket-settings.json"),
            File(rootfs, "root/.claude/settings.json"),
            File(rootfs, "etc/claude/settings.json"),
        )
        for (target in settingsPaths) {
            writeRootfsFileAtomically(target, settingsContent)
        }
        ensureWorkspaceTrust("/workspace")
    }

    private fun ensureWorkspaceTrust(
        workspacePath: String,
        environment: Map<String, String> = emptyMap(),
    ) = synchronized(CLAUDE_STATE_LOCK) {
        val stateFile = File(rootfs, "root/.claude.json")
        if (stateFile.exists() && !(stateFile.canRead() && stateFile.canWrite())) {
            // Claude runs as root inside the chroot and may have rewritten the file as root-owned 0600.
            RootfsRepair.reclaimOwnership(stateFile, AndroidProcess.myUid())
        }
        val state = if (stateFile.exists()) {
            // Claude may be writing the file right now (another session); never replace a file that does not parse,
            // or the account, API-key approvals and project history in it would be lost.
            readClaudeState(stateFile) ?: run {
                Log.w("RuntimeInstaller", "Leaving ~/.claude.json untouched: it could not be parsed")
                return@synchronized
            }
        } else {
            JSONObject()
        }
        listOf("disableAllHooks", "permissions", "hooks", "allowedTools", "autoApprove")
            .forEach { state.remove(it) }
        state.put("hasCompletedOnboarding", true)
        val projects = state.optJSONObject("projects") ?: JSONObject()
        val workspace = projects.optJSONObject(workspacePath) ?: JSONObject()
        workspace.put("hasTrustDialogAccepted", true)
        projects.put(workspacePath, workspace)
        state.put("projects", projects)
        val apiKeySuffix = environment["ANTHROPIC_API_KEY"]?.trim()?.takeIf { it.isNotEmpty() }?.takeLast(20)
        if (!apiKeySuffix.isNullOrBlank()) {
            val responses = state.optJSONObject("customApiKeyResponses") ?: JSONObject()
            val approved = responses.optJSONArray("approved") ?: JSONArray()
            val existing = (0 until approved.length()).mapNotNull { approved.optString(it).takeIf(String::isNotBlank) }.toSet()
            if (apiKeySuffix !in existing) {
                approved.put(apiKeySuffix)
            }
            responses.put("approved", approved)
            if (responses.optJSONArray("rejected") == null) {
                responses.put("rejected", JSONArray())
            }
            state.put("customApiKeyResponses", responses)
        }
        writeRootfsFileAtomically(stateFile, state.toString())
    }

    /** Parses ~/.claude.json, retrying briefly in case Claude is in the middle of rewriting it. */
    private fun readClaudeState(stateFile: File): JSONObject? {
        repeat(3) { attempt ->
            val parsed = runCatching { JSONObject(stateFile.readText()) }.getOrNull()
            if (parsed != null) return parsed
            if (attempt < 2) Thread.sleep(150)
        }
        return null
    }

    /**
     * Writes [target] through a temp file in the same directory and an atomic rename, so a crash or a concurrent
     * reader never sees a truncated file. A root-owned target (rewritten by a root process in the chroot) is
     * reclaimed first.
     */
    private fun writeRootfsFileAtomically(target: File, content: String, mode: Int? = null) {
        val parent = checkNotNull(target.parentFile) { "No parent directory for ${target.name}" }
        parent.mkdirs()
        if (!parent.canWrite() || (target.exists() && !target.canWrite())) {
            RootfsRepair.reclaimOwnership(if (parent.canWrite()) target else parent, AndroidProcess.myUid())
        }
        val temp = File(parent, ".${target.name}.${AndroidProcess.myPid()}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            // Set the mode before the rename so the file is never visible without its execute bit.
            if (mode != null) Os.chmod(temp.absolutePath, mode)
            Os.rename(temp.absolutePath, target.absolutePath)
        } finally {
            temp.delete()
        }
    }

    // Only auto-allow read-only tools. Bash/Edit/Write/NotebookEdit go through the
    // PermissionRequest hook so the app can classify and gate each command.
    private fun claudeWorkspaceToolRules() = JSONArray().apply {
        put("Read")
        put("Glob")
        put("Grep")
        // Tools outside the permission hook (web, sub-agents, todos, skills) would otherwise be refused outright in a
        // headless session, since nobody can answer Claude's own prompt. With auto-approve on, nothing may be refused.
        if (runCatching { com.nextcraft.forge.data.AppPreferences(context).autoApproveAllTools }.getOrDefault(true)) {
            listOf("WebFetch", "WebSearch", "Task", "Agent", "TodoWrite", "Skill").forEach(::put)
        }
    }

    private fun writeResolver() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val dns = manager?.getLinkProperties(manager.activeNetwork)?.dnsServers.orEmpty()
        val servers = dns.mapNotNull { it.hostAddress }.ifEmpty { listOf("8.8.8.8", "1.1.1.1") }
        val resolvFile = File(rootfs, "etc/resolv.conf")
        resolvFile.parentFile?.mkdirs()
        if (java.nio.file.Files.isSymbolicLink(resolvFile.toPath())) {
            resolvFile.delete()
        }
        resolvFile.writeText(servers.joinToString("\n") { "nameserver $it" } + "\n")
        ensureLocalhostInHosts()
    }

    /**
     * The Core rootfs ships an empty /etc/hosts, so `localhost` was resolved through DNS and failed. Anything that binds
     * or connects to localhost then breaks: the OAuth callback listener of Antigravity ("listen tcp: lookup localhost ...
     * no such host") and Claude Code logins, dev servers, Gradle and preview. Keeps whatever else the file holds.
     */
    private fun ensureLocalhostInHosts() {
        val hosts = File(rootfs, "etc/hosts")
        val current = runCatching { hosts.takeIf { it.isFile }?.readText().orEmpty() }.getOrDefault("")
        val hasLocalhost = current.lineSequence().any { line ->
            val fields = line.substringBefore('#').trim().split(Regex("\\s+"))
            fields.size >= 2 && fields[0] == "127.0.0.1" && "localhost" in fields.drop(1)
        }
        if (hasLocalhost) return
        val entries = "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n"
        runCatching { writeRootfsFileAtomically(hosts, entries + current.trimStart('\n')) }
            .onFailure { Log.w("RuntimeInstaller", "Could not write /etc/hosts", it) }
    }

    private fun openZstdInputStream(
        archive: File,
        consumed: java.util.concurrent.atomic.AtomicLong? = null,
    ): java.io.InputStream {
        val fileStream = archive.inputStream()
        // Counts compressed bytes read so unpacking can report MB/s; the external-zstd fallback is not counted.
        val counted = if (consumed == null) fileStream else object : java.io.FilterInputStream(fileStream) {
            override fun read(): Int = super.read().also { if (it >= 0) consumed.incrementAndGet() }
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it > 0) consumed.addAndGet(it.toLong()) }
        }
        val rawStream = BufferedInputStream(counted)
        return try {
            ZstdCompressorInputStream(rawStream)
        } catch (_: Throwable) {
            runCatching { rawStream.close() }
            val process = runCatching {
                ProcessBuilder("zstd", "-d", "-c", archive.absolutePath).start()
            }.getOrElse {
                val escapedPath = archive.absolutePath.replace("'", "'\"'\"'")
                val pathExport = "export PATH=/system/bin:/system/xbin:/sbin:/vendor/bin:/data/adb/magisk:/data/adb/ksu/bin:/data/adb/ap/bin:${'$'}PATH; "
                ProcessBuilder("su", "-c", "${pathExport}zstd -d -c '$escapedPath'").start()
            }
            object : java.io.FilterInputStream(BufferedInputStream(process.inputStream)) {
                private var reachedEof = false

                override fun read(): Int = super.read().also { if (it < 0) reachedEof = true }

                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it < 0) reachedEof = true }

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        runCatching { process.errorStream.close() }
                        runCatching { process.outputStream.close() }
                        val finished = runCatching {
                            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                        }.getOrDefault(false)
                        if (!finished) {
                            process.destroyForcibly()
                        } else if (reachedEof && process.exitValue() != 0) {
                            throw java.io.IOException("zstd decompression failed with exit code ${process.exitValue()}")
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun extractZstdTar(
        archive: File,
        destination: File,
        /** Receives (compressed bytes consumed, archive size) at most every ~400 ms. */
        report: (suspend (Long, Long) -> Unit)? = null,
    ) {
        val consumed = if (report != null) java.util.concurrent.atomic.AtomicLong() else null
        val archiveSize = archive.length()
        var lastReport = 0L
        val rootDir = destination.canonicalFile
        val rootPath = rootDir.toPath()
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(openZstdInputStream(archive, consumed)).use { tar ->
            var entry: TarArchiveEntry? = tar.nextTarEntry
            while (entry != null) {
                // Pause/stop from the notification must end a long unpack instead of waiting for it to finish.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (report != null && consumed != null) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastReport >= 400L) {
                        lastReport = now
                        report(consumed.get().coerceAtMost(archiveSize), archiveSize)
                    }
                }
                val currentEntry = entry
                val cleanName = currentEntry.name.removePrefix("./").trimEnd('/')
                if (cleanName.isNotBlank()) {
                    val target = safeChild(rootDir, cleanName)
                    when {
                        currentEntry.isDirectory -> {
                            if (java.nio.file.Files.isSymbolicLink(target.toPath())) {
                                val resolvedDir = runCatching {
                                    resolveSafeSymlinkTarget(
                                        rootPath = rootPath,
                                        parentDir = target.toPath().parent ?: rootPath,
                                        linkName = java.nio.file.Files.readSymbolicLink(target.toPath()).toString(),
                                        errorMessage = "Archive path escapes runtime via symlink",
                                    ).toFile()
                                }.getOrNull()
                                if (resolvedDir != null) {
                                    resolvedDir.mkdirs()
                                } else {
                                    target.delete()
                                    target.mkdirs()
                                }
                            } else {
                                target.mkdirs()
                            }
                        }
                        currentEntry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            resolveSafeSymlinkTarget(
                                rootPath = rootPath,
                                parentDir = target.toPath().parent ?: rootPath,
                                linkName = currentEntry.linkName,
                                errorMessage = "Archive symlink escapes runtime",
                            )
                            if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) {
                                if (target.isDirectory && !java.nio.file.Files.isSymbolicLink(target.toPath())) {
                                    target.deleteRecursively()
                                } else {
                                    target.delete()
                                }
                            }
                            Os.symlink(currentEntry.linkName, target.absolutePath)
                        }
                        currentEntry.isLink -> {
                            target.parentFile?.mkdirs()
                            val linkTarget = safeChild(rootDir, currentEntry.linkName.removePrefix("./").trimEnd('/'))
                            if (linkTarget.exists() || java.nio.file.Files.isSymbolicLink(linkTarget.toPath())) {
                                linkOrCopyFile(linkTarget, target)
                            } else {
                                deferredLinks += target to linkTarget
                            }
                        }
                        currentEntry.isFile -> {
                            target.parentFile?.mkdirs()
                            if (java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            FileOutputStream(target).use { output -> tar.copyTo(output) }
                            runCatching { Os.chmod(target.absolutePath, currentEntry.mode and 0b111111111) }
                        }
                    }
                }
                entry = tar.nextTarEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile || java.nio.file.Files.isSymbolicLink(linkTarget.toPath())) {
                "Archive hard-link target is missing"
            }
            target.parentFile?.mkdirs()
            linkOrCopyFile(linkTarget, target)
            if (!java.nio.file.Files.isSymbolicLink(target.toPath())) {
                runCatching { Os.chmod(target.absolutePath, Os.stat(linkTarget.absolutePath).st_mode and 0b111111111) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractNodeArchive(archive: File, destination: File) {
        val rootDir = destination.canonicalFile
        val rootPath = rootDir.toPath()
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            var entry: TarArchiveEntry? = tar.nextTarEntry
            while (entry != null) {
                val currentEntry = entry
                val relative = currentEntry.name.removePrefix("./").substringAfter('/', "").trimEnd('/')
                if (relative.isNotBlank()) {
                    val target = safeChild(rootDir, relative)
                    when {
                        currentEntry.isDirectory -> {
                            if (java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            target.mkdirs()
                        }
                        currentEntry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            resolveSafeSymlinkTarget(
                                rootPath = rootPath,
                                parentDir = target.toPath().parent ?: rootPath,
                                linkName = currentEntry.linkName,
                                errorMessage = "Archive symlink escapes runtime",
                            )
                            if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            Os.symlink(currentEntry.linkName, target.absolutePath)
                        }
                        currentEntry.isLink -> {
                            val relativeLink = currentEntry.linkName.removePrefix("./").substringAfter('/', "").trimEnd('/')
                            val linkTarget = safeChild(rootDir, relativeLink)
                            target.parentFile?.mkdirs()
                            if (linkTarget.exists() || java.nio.file.Files.isSymbolicLink(linkTarget.toPath())) {
                                linkOrCopyFile(linkTarget, target)
                            } else {
                                deferredLinks += target to linkTarget
                            }
                        }
                        currentEntry.isFile -> {
                            target.parentFile?.mkdirs()
                            if (java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            FileOutputStream(target).use { output -> tar.copyTo(output) }
                            runCatching { Os.chmod(target.absolutePath, currentEntry.mode and 0b111111111) }
                        }
                    }
                }
                entry = tar.nextTarEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile || java.nio.file.Files.isSymbolicLink(linkTarget.toPath())) {
                "Node.js archive hard-link target is missing"
            }
            target.parentFile?.mkdirs()
            linkOrCopyFile(linkTarget, target)
            if (!java.nio.file.Files.isSymbolicLink(target.toPath())) {
                runCatching { Os.chmod(target.absolutePath, Os.stat(linkTarget.absolutePath).st_mode and 0b111111111) }
            }
        }
    }

    private fun linkOrCopyFile(source: File, destination: File) {
        if (destination.exists() || java.nio.file.Files.isSymbolicLink(destination.toPath())) destination.delete()
        if (java.nio.file.Files.isSymbolicLink(source.toPath())) {
            val symTarget = java.nio.file.Files.readSymbolicLink(source.toPath()).toString()
            Os.symlink(symTarget, destination.absolutePath)
            return
        }
        val linked = runCatching {
            Os.link(source.absolutePath, destination.absolutePath)
            true
        }.getOrDefault(false)
        if (!linked) {
            source.inputStream().use { input -> FileOutputStream(destination).use { input.copyTo(it) } }
            runCatching { Os.chmod(destination.absolutePath, Os.stat(source.absolutePath).st_mode and 0b111111111) }
        }
    }

    private fun resolveSafeSymlinkTarget(
        rootPath: java.nio.file.Path,
        parentDir: java.nio.file.Path,
        linkName: String,
        errorMessage: String = "Archive symlink escapes runtime",
    ): java.nio.file.Path {
        require(linkName.isNotBlank() && !linkName.contains('\u0000')) { errorMessage }
        val normalizedLink = linkName.replace('\\', '/')
        val resolved = if (normalizedLink.startsWith('/')) {
            val relFromRoot = normalizedLink.trimStart('/')
            if (relFromRoot.isEmpty()) rootPath else rootPath.resolve(relFromRoot).normalize()
        } else {
            parentDir.resolve(normalizedLink).normalize()
        }
        require(resolved.startsWith(rootPath)) { errorMessage }
        return resolved
    }

    private fun safeChild(root: File, relative: String): File {
        val normalized = relative.replace('\\', '/').trimEnd('/')
        val segments = normalized.split('/')
        require(
            normalized.isNotBlank() &&
                !normalized.contains('\u0000') &&
                !normalized.startsWith('/') &&
                segments.isNotEmpty() &&
                segments.none { it.isEmpty() || it == "." || it == ".." },
        ) {
            "Unsafe archive path"
        }
        val rootCanonical = root.canonicalFile
        val rootPath = rootCanonical.toPath()
        var currentDir = rootPath
        for (index in 0 until segments.lastIndex) {
            val segment = segments[index]
            var candidate = currentDir.resolve(segment).normalize()
            require(candidate.startsWith(rootPath) && candidate != rootPath) { "Archive path escapes runtime" }
            var hops = 0
            while (java.nio.file.Files.isSymbolicLink(candidate)) {
                check(++hops <= 16) { "Archive symlink loop detected" }
                val linkTarget = java.nio.file.Files.readSymbolicLink(candidate).toString()
                val parent = candidate.parent ?: rootPath
                candidate = resolveSafeSymlinkTarget(
                    rootPath = rootPath,
                    parentDir = parent,
                    linkName = linkTarget,
                    errorMessage = "Archive path escapes runtime via symlink",
                )
            }
            currentDir = candidate
        }
        val finalPath = currentDir.resolve(segments.last()).normalize()
        require(finalPath.startsWith(rootPath) && finalPath != rootPath) {
            "Archive path escapes runtime"
        }
        return finalPath.toFile()
    }

    private suspend fun downloadVerified(
        url: String,
        destination: File,
        expectedChecksum: String,
        algorithm: String = "SHA-256",
        onBytes: suspend (downloaded: Long, total: Long) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        if (destination.isFile && digest(destination, algorithm).equals(expectedChecksum, ignoreCase = true)) {
            onBytes(destination.length(), destination.length())
            return
        }
        val temporary = File(destination.parentFile, "${destination.name}.part")
        var existing = temporary.takeIf(File::isFile)?.length() ?: 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = true
            if (existing > 0L) connection.setRequestProperty("Range", "bytes=$existing-")
            val code = connection.responseCode
            if (code == 416 && existing > 0L) {
                // Stale/complete .part cannot be resumed; discard it and restart now (existing is 0 on the retry).
                temporary.delete()
                return downloadVerified(url, destination, expectedChecksum, algorithm, onBytes)
            }
            check(code in 200..299) { "Download failed with HTTP $code" }
            val resumed = code == HttpURLConnection.HTTP_PARTIAL && existing > 0L
            if (!resumed) {
                temporary.delete()
                existing = 0L
            }
            val total = connection.contentLengthLong.takeIf { it >= 0L }?.plus(existing) ?: -1L
            connection.inputStream.use { input ->
                FileOutputStream(temporary, resumed).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = existing
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onBytes(downloaded, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val actual = digest(temporary, algorithm)
        if (!actual.equals(expectedChecksum, ignoreCase = true)) {
            temporary.delete()
            error("Downloaded file checksum did not match")
        }
        destination.delete()
        check(temporary.renameTo(destination)) { "Could not finish download" }
    }

    private fun fetchText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            check(code in 200..299) { "Request failed with HTTP $code" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    companion object {
        /** Top-level rootfs directories that chroot-exec.sh bind-mounts from Android or the app on every launch. */
        private val BIND_MOUNTED_TOP_LEVEL = setOf("proc", "sys", "dev", "system", "apex", "vendor", "product", "system_ext", "odm", "workspace", "data", "storage", "mnt", "sdcard")
        /** RuntimeInstaller is created per call site; the lock must be shared by every instance. */
        private val CLAUDE_STATE_LOCK = Any()

        fun rootfsDir(context: Context): File = File(context.filesDir, "runtime/ubuntu")
        fun bridgeRootDir(context: Context): File = File(context.filesDir, "runtime-bridge")

        const val AGY_GUEST_PATH = "/root/.local/bin/agy"
        const val GITHUB_CLI_GUEST_PATH = "/root/.local/bin/gh"
        private const val AGY_VERSION = "1.1.27"
        const val CORE_MISSING_MESSAGE =
            "The base Ubuntu runtime (Core) is not installed or is incomplete. Run the runtime setup first, then retry."
        private const val GITHUB_CLI_VERSION = "2.100.0"
        private const val GITHUB_CLI_RELEASE_URL = "https://github.com/cli/cli/releases/download/v2.100.0/gh_2.100.0_linux_arm64.tar.gz"
        private const val GITHUB_CLI_RELEASE_SHA256 = "ea4e7a581a32ccad6cc7923cb1576ac5859ba4b9a16ab22eb8f8a96e78e2e961"

        init {
            check(
                GITHUB_CLI_RELEASE_SHA256.length == 64 &&
                    GITHUB_CLI_RELEASE_SHA256.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' },
            ) {
                "Invalid GITHUB_CLI_RELEASE_SHA256 length"
            }
        }
        private const val LEGACY_README = "# Pocket Dev project\n\nThis project is managed locally on Android.\n"
        private const val LEGACY_INDEX = "<!doctype html><title>Pocket Dev</title><h1>Hello from Android</h1>\n"
        private const val ROOTFS_VERSION = "ubuntu-24.04.5-arm64"
        private const val LEGACY_ROOTFS_VERSION = "ubuntu-20.04.5-arm64"
        private const val NODE_VERSION = "v24.19.0"
        private const val NODE_ARM64_SHA256 = "d28c8a5bf0a808f0ed434a1dce8c54ae98f0371c0bd86ac58abc613f73e6643f"
        private const val COMPOSER_VERSION = "2.10.3"
        private const val COMPOSER_SHA256 = "7a2d379d5b8ffdaa028580ef26494c36d2feef4b178d3dd1473a4dbc5e17c8d6"
        private const val LANGUAGE_TOOLS_VERSION = "node-v24.19.0-python3-v1"
        private const val CORE_TOOLS_VERSION = "core-bundle-2026.10.1"
        private const val LEGACY_CORE_TOOLS_VERSION = "core-bundle-2026.09.4"
        private const val PREVIOUS_CORE_TOOLS_VERSION = "core-bundle-2026.09.5"
        private const val NEXTCRAFT_CORE_TOOLS_VERSION = "nextcraft-core-2026.10.1"
        private const val SYSTEM_UPGRADE_VERSION = "ubuntu-maintenance-v1"
        private const val ANDROID_TOOLS_VERSION = AndroidToolchain.TOOLS_VERSION
        private const val ANDROID_AAPT2_PROPERTY = "android.aapt2FromMavenOverride"
        private const val GRADLE_JVMARGS_PROPERTY = "org.gradle.jvmargs"
        private const val GRADLE_WORKERS_PROPERTY = "org.gradle.workers.max"
        private const val GRADLE_PARALLEL_PROPERTY = "org.gradle.parallel"
        private const val KOTLIN_EXECUTION_PROPERTY = "kotlin.compiler.execution.strategy"
        private val MANAGED_ANDROID_GRADLE_PROPERTIES = setOf(
            ANDROID_AAPT2_PROPERTY,
            GRADLE_JVMARGS_PROPERTY,
            GRADLE_WORKERS_PROPERTY,
            GRADLE_PARALLEL_PROPERTY,
            KOTLIN_EXECUTION_PROPERTY,
        )
        private const val ANDROID_AAPT2_GUEST_PATH = AndroidToolchain.AAPT2_GUEST_PATH
        private const val ANDROID_AAPT2_HOST_PATH = AndroidToolchain.AAPT2_HOST_PATH

        /**
         * Gradle init script that makes the offline Maven repository and the ARM64 aapt2 visible to every build.
         * `build-android-bundle.sh` ships a byte-identical copy; keep both in sync.
         */
        internal val ANDROID_GRADLE_INIT_SCRIPT = """
            def pocketMaven = uri('${AndroidToolchain.MAVEN_GUEST_DIR}')
            beforeSettings { settings ->
                settings.pluginManagement.repositories {
                    maven { url = pocketMaven }
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            settingsEvaluated { settings ->
                settings.dependencyResolutionManagement.repositories {
                    maven { url = pocketMaven }
                }
            }
            gradle.beforeProject { project ->
                project.extensions.extraProperties.set(
                    '$ANDROID_AAPT2_PROPERTY',
                    '$ANDROID_AAPT2_GUEST_PATH'
                )
                project.buildscript.repositories {
                    maven { url = pocketMaven }
                }
            }
        """.trimIndent() + "\n"
        private val CLAUDE_VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
        const val DSH_VERSION = "0.1.2-rc.1"
        private const val DSH_ANDROID_COMPATIBILITY_VERSION = "copyfile-excl-v1"
        private val CORE_BUNDLE = RuntimeBundle(
            label = "Core",
            fileName = "nextcraft-core-arm64-2026.10.1.tar.zst",
            sha256 = "fe63d1dececdcd5e861debe0936835d39c2eb6eb787bb7bd71bcab5c3db4d4cf",
            compressedBytes = 74_269_605L,
        )
        private const val CLAUDE_BUNDLED_VERSION = "2.1.263"
        private const val CLAUDE_GUEST_PATH = "/usr/local/bin/claude"
        private val CLAUDE_BUNDLE = RuntimeBundle(
            label = "Claude Code",
            fileName = "nextcraft-claude-arm64-2026.10.1.tar.zst",
            sha256 = "bb993c0a80ff2ea8d9c994e03cf587bf7ef996bdecdab3d95ce51e1e0f04f2bd",
            compressedBytes = 75_422_659L,
        )
        private val PYTHON_BUNDLE = RuntimeBundle(
            label = "Python",
            fileName = "nextcraft-python-arm64-2026.10.1.tar.zst",
            sha256 = "43a23a3e2d73e46f5c242ac4e3d8f7adef356b987559e1474e4cc9072118a7f3",
            compressedBytes = 15_053_684L,
        )
        /** Python 3.8 overlay for runtimes still on the Ubuntu 20.04 rootfs: the 3.12 overlay needs glibc 2.39. */
        private val LEGACY_PYTHON_BUNDLE = RuntimeBundle(
            label = "Python",
            fileName = "pocketdev-python-arm64-2026.09.2.tar.zst",
            sha256 = "6b3f56f7743fec142bc045db3ea561ee83fe89af87c2799165ef60351164ef85",
            compressedBytes = 55_419_626L,
            baseUrl = LEGACY_RUNTIME_RELEASE_BASE_URL,
        )
        /** Built by scripts/runtime-bundles/build-android-bundle.sh; versions in [AndroidToolchain]. */
        private val ANDROID_BUNDLE = RuntimeBundle(
            label = "Android",
            fileName = "nextcraft-android-arm64-2026.10.1.tar.zst",
            sha256 = "8b4ecf057daa6fd54acd950712f8adb4eb7722c7bc557136f2ea31d7a2bbfd60",
            compressedBytes = 584_522_930L,
        )
        private val DSH_BUNDLE = RuntimeBundle(
            label = "DeepSeek Harness",
            fileName = "nextcraft-dsh-arm64-2026.10.1.tar.zst",
            sha256 = "d3137b607c32f3714c655f5e0f101d7e7292841a056d3b9bc928e704656c220f",
            compressedBytes = 28_066_921L,
        )
        private val AGY_BUNDLE = RuntimeBundle(
            label = "Antigravity CLI",
            fileName = "nextcraft-agy-arm64-2026.10.1.tar.zst",
            sha256 = "001cbb373a1110ce32c939c2b27138b46b676b157c9329b50c34e790aa2b7eff",
            compressedBytes = 42_003_928L,
        )
        private const val ROOT_GRANT_TIMEOUT_SECONDS = 45L
        private const val ENV_CLEANUP_POLL_MS = 1_000L
        /** Compressed size multiples needed to download (1x) and extract (about 3x for zstd rootfs content). */
        private const val BUNDLE_DOWNLOAD_SPACE_FACTOR = 4
        private const val BUNDLE_EXTRACT_SPACE_FACTOR = 3
        private const val MEGABYTE = 1024L * 1024L
        private const val MAX_TERMINAL_LINE = 500
        private const val MAX_COLLECTED_OUTPUT = 24_000
        private const val MAX_PROCESS_OUTPUT_BYTES = 256 * 1024
        private val ANSI_ESCAPE = Regex("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))")
        private val VALID_ENV_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        private val CHROOT_EXEC_SCRIPT = """
            #!/system/bin/sh
            set -e

            if [ "${'$'}#" -lt 6 ]; then
                echo "Usage: chroot-exec.sh <rootfs> <host_workspace> <guest_workspace> <host_bridge> <app_uid> <cmd...>" >&2
                exit 2
            fi

            ROOTFS="${'$'}1"
            HOST_WORKSPACE="${'$'}2"
            GUEST_WORKSPACE="${'$'}3"
            HOST_BRIDGE="${'$'}4"
            APP_UID="${'$'}5"
            shift 5

            if [ -z "${'$'}ROOTFS" ] || [ -z "${'$'}HOST_WORKSPACE" ] || [ -z "${'$'}GUEST_WORKSPACE" ] || [ -z "${'$'}HOST_BRIDGE" ] || [ -z "${'$'}APP_UID" ]; then
                echo "Usage: chroot-exec.sh <rootfs> <host_workspace> <guest_workspace> <host_bridge> <app_uid> <cmd...>" >&2
                exit 2
            fi

            case "${'$'}GUEST_WORKSPACE" in
                *..*|/workspace/.|/workspace/*/*|*[!a-zA-Z0-9._/-]*)
                    echo "Invalid guest workspace path: ${'$'}GUEST_WORKSPACE" >&2
                    exit 65
                    ;;
                /root|/workspace|/workspace/[a-zA-Z0-9._-]*) ;;
                *)
                    echo "Invalid guest workspace path: ${'$'}GUEST_WORKSPACE" >&2
                    exit 65
                    ;;
            esac

            case "${'$'}APP_UID" in
                ''|*[!0-9]*)
                    echo "Invalid app UID: ${'$'}APP_UID" >&2
                    exit 65
                    ;;
            esac

            is_mounted() {
                awk -v target="${'$'}1" '${'$'}2 == target { found=1; exit } END { exit !found }' /proc/mounts
            }

            mount_once() {
                local src="${'$'}1"
                local dst="${'$'}2"
                local fstype="${'$'}3"
                local opts="${'$'}4"
                mkdir -p "${'$'}dst"
                if ! is_mounted "${'$'}dst"; then
                    if [ "${'$'}fstype" = "bind" ]; then
                        mount -o bind "${'$'}src" "${'$'}dst"
                        if [ "${'$'}opts" = "ro" ]; then
                            mount -o remount,bind,ro "${'$'}dst" 2>/dev/null || true
                        fi
                    else
                        mount -t "${'$'}fstype" -o "${'$'}opts" "${'$'}src" "${'$'}dst"
                    fi
                fi
            }

            # Android's /apex is a tmpfs with every APEX module mounted below it, and /system/bin/linker64 (the interpreter
            # of Bionic tools such as aapt2) points into /apex/com.android.runtime. A plain bind leaves those modules out.
            # A recursive bind is not used: its copies are peers of the shared Android mounts until they are made slaves,
            # and unmounting a copy that is still a peer also unmounts the real module. Instead the directory is bound on
            # its own, made a slave while it has no submounts yet, and only then are the modules bound one by one.
            has_submounts() {
                awk -v prefix="${'$'}1/" 'index(${'$'}2, prefix) == 1 { found=1; exit } END { exit !found }' /proc/mounts
            }

            make_slave() {
                mount --make-rslave "${'$'}1" 2>/dev/null || mount -o rslave "${'$'}1" 2>/dev/null ||
                    mount --make-slave "${'$'}1" 2>/dev/null || mount -o slave "${'$'}1" 2>/dev/null
            }

            mount_bionic() {
                local src="${'$'}1"
                local dst="${'$'}2"
                mkdir -p "${'$'}dst"
                # Earlier versions bound these directories without their submounts; replace such a mount.
                if is_mounted "${'$'}dst" && has_submounts "${'$'}src" && ! has_submounts "${'$'}dst"; then
                    umount "${'$'}dst" 2>/dev/null || umount -l "${'$'}dst" 2>/dev/null || true
                fi
                is_mounted "${'$'}dst" && return 0
                mount -o bind "${'$'}src" "${'$'}dst" || return 1
                if ! make_slave "${'$'}dst"; then
                    echo "Warning: mount propagation cannot be changed; ${'$'}dst is bound without its submounts" >&2
                    mount -o remount,bind,ro "${'$'}dst" 2>/dev/null || true
                    return 0
                fi
                # Parents first, so each module lands on a mount point that is already a slave copy.
                awk -v prefix="${'$'}src/" 'index(${'$'}2, prefix) == 1 { print ${'$'}2 }' /proc/mounts | sort | while read -r sub; do
                    target="${'$'}dst${'$'}{sub#"${'$'}src"}"
                    [ -d "${'$'}sub" ] && [ -d "${'$'}target" ] || continue
                    is_mounted "${'$'}target" && continue
                    if mount -o bind "${'$'}sub" "${'$'}target" 2>/dev/null && ! make_slave "${'$'}target"; then
                        umount "${'$'}target" 2>/dev/null || true
                    fi
                done
                mount -o remount,bind,ro "${'$'}dst" 2>/dev/null || true
            }

            mount_dynamic_bind() {
                local src="${'$'}1"
                local dst="${'$'}2"
                local state_dir="${'$'}ROOTFS/tmp/.nextcraft-mounts"
                local state_key
                state_key=${'$'}(printf '%s' "${'$'}dst" | tr '/' '_')
                local state_file="${'$'}state_dir/${'$'}state_key"
                mkdir -p "${'$'}src" "${'$'}dst" "${'$'}state_dir"
                if is_mounted "${'$'}dst"; then
                    local prev_src=""
                    [ -f "${'$'}state_file" ] && prev_src=${'$'}(cat "${'$'}state_file" 2>/dev/null || true)
                    if [ "${'$'}prev_src" != "${'$'}src" ]; then
                        umount "${'$'}dst" 2>/dev/null || umount -l "${'$'}dst" 2>/dev/null || true
                    fi
                fi
                if ! is_mounted "${'$'}dst"; then
                    mount -o bind "${'$'}src" "${'$'}dst"
                    printf '%s' "${'$'}src" > "${'$'}state_file"
                fi
            }

            # Scope suid/exec to the rootfs only; never relax mount flags on the whole /data partition.
            if ! is_mounted "${'$'}ROOTFS"; then
                mount -o bind "${'$'}ROOTFS" "${'$'}ROOTFS" 2>/dev/null || true
            fi
            mount -o remount,bind,suid,exec "${'$'}ROOTFS" 2>/dev/null || true

            mount_once "/dev" "${'$'}ROOTFS/dev" "bind" "rw"
            mount_once "/dev/pts" "${'$'}ROOTFS/dev/pts" "bind" "rw"
            mount_once "proc" "${'$'}ROOTFS/proc" "proc" "rw,nosuid,nodev,noexec"
            mount_once "sysfs" "${'$'}ROOTFS/sys" "sysfs" "rw,nosuid,nodev,noexec"
            mount_once "tmpfs" "${'$'}ROOTFS/tmp" "tmpfs" "rw,nosuid,nodev,mode=1777"
            mount_once "tmpfs" "${'$'}ROOTFS/dev/shm" "tmpfs" "rw,nosuid,nodev,mode=1777,size=256M"

            for bionic_dir in /system /apex /vendor /product; do
                if [ -d "${'$'}bionic_dir" ]; then
                    mount_bionic "${'$'}bionic_dir" "${'$'}ROOTFS${'$'}bionic_dir"
                fi
            done

            mkdir -p "${'$'}HOST_WORKSPACE" "${'$'}HOST_BRIDGE"
            if [ "${'$'}HOST_WORKSPACE" != "${'$'}ROOTFS${'$'}GUEST_WORKSPACE" ]; then
                mount_dynamic_bind "${'$'}HOST_WORKSPACE" "${'$'}ROOTFS${'$'}GUEST_WORKSPACE"
            fi
            mount_dynamic_bind "${'$'}HOST_BRIDGE" "${'$'}ROOTFS/nextcraft-bridge"
            mount_dynamic_bind "${'$'}HOST_BRIDGE" "${'$'}ROOTFS/pocketdev-bridge"
            mount_dynamic_bind "${'$'}HOST_BRIDGE" "${'$'}ROOTFS/pocket-bridge"
            HOST_RESP="${'$'}HOST_BRIDGE-responses"
            mkdir -p "${'$'}HOST_RESP"
            chown "${'$'}APP_UID:${'$'}APP_UID" "${'$'}HOST_RESP" 2>/dev/null || true
            mount_dynamic_bind "${'$'}HOST_RESP" "${'$'}ROOTFS/pocketdev-bridge-responses"
            mount -o remount,bind,ro "${'$'}ROOTFS/pocketdev-bridge-responses" 2>/dev/null || true

            if [ ! -s "${'$'}ROOTFS/etc/resolv.conf" ]; then
                mkdir -p "${'$'}ROOTFS/etc"
                printf "nameserver 8.8.8.8\nnameserver 1.1.1.1\n" > "${'$'}ROOTFS/etc/resolv.conf"
            fi

            fix_workspace_ownership() {
                case "${'$'}HOST_WORKSPACE" in
                    "${'$'}ROOTFS"|"${'$'}ROOTFS"/*)
                        ;;
                    *)
                        chown -R -h "${'$'}APP_UID:${'$'}APP_UID" "${'$'}HOST_WORKSPACE" 2>/dev/null || true
                        chmod -R u+rwX,g+rwX "${'$'}HOST_WORKSPACE" 2>/dev/null || true
                        ;;
                esac
                chown -R -h "${'$'}APP_UID:${'$'}APP_UID" "${'$'}HOST_BRIDGE" 2>/dev/null || true
                chmod -R u+rwX,g+rwX "${'$'}HOST_BRIDGE" 2>/dev/null || true
            }
            # INT/TERM must end the script (exit fires the EXIT trap once); a plain trap handler would
            # resume execution (keep mounting) and run the ownership fix twice.
            trap fix_workspace_ownership EXIT
            trap 'exit 130' INT
            trap 'exit 143' TERM

            unset ANDROID_ROOT ANDROID_DATA ANDROID_ART_ROOT ANDROID_I18N_ROOT ANDROID_TZDATA_ROOT
            unset ANDROID_ASSETS ANDROID_BOOTLOGO ANDROID_RUNTIME_ROOT ANDROID_STORAGE
            unset ASEC_MOUNTPOINT BOOTCLASSPATH DEX2OATBOOTCLASSPATH EXTERNAL_STORAGE SYSTEMSERVERCLASSPATH
            unset LD_PRELOAD LD_LIBRARY_PATH

            export HOME="/root"
            export USER="root"
            export LOGNAME="root"
            export TERM="${'$'}{TERM:-xterm-256color}"
            export LANG="${'$'}{LANG:-C.UTF-8}"
            export TMPDIR="/tmp"
            export CLAUDE_CONFIG_DIR="/root/.claude"
            export SSL_CERT_FILE="/etc/ssl/certs/ca-certificates.crt"
            export CURL_CA_BUNDLE="/etc/ssl/certs/ca-certificates.crt"
            export NODE_EXTRA_CA_CERTS="/etc/ssl/certs/ca-certificates.crt"
            export REQUESTS_CA_BUNDLE="/etc/ssl/certs/ca-certificates.crt"
            export GIT_SSL_CAINFO="/etc/ssl/certs/ca-certificates.crt"
            export NODE_OPTIONS="${'$'}{NODE_OPTIONS:---dns-result-order=ipv4first}"
            export COREPACK_ENABLE_DOWNLOAD_PROMPT="0"
            export GLIBC_TUNABLES="glibc.pthread.rseq=0"
            export NEXTCRAFT_BRIDGE_DIR="/pocketdev-bridge"
            export POCKETDEV_BRIDGE_DIR="/pocketdev-bridge"
            export NEXTCRAFT_RESPONSE_DIR="/pocketdev-bridge-responses"
            export PATH="${'$'}{PATH:-/opt/nextcraft/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin}"
            case ":${'$'}PATH:" in
                *:/root/.local/bin:*) ;;
                *) export PATH="/opt/nextcraft/bin:/root/.local/bin:${'$'}PATH" ;;
            esac

            CHROOT_BIN=""
            for candidate in /system/bin/chroot /system/xbin/chroot /sbin/chroot /debug_ramdisk/chroot "${'$'}ROOTFS/usr/sbin/chroot"; do
                if [ -x "${'$'}candidate" ]; then
                    CHROOT_BIN="${'$'}candidate"
                    break
                fi
            done
            if [ -z "${'$'}CHROOT_BIN" ]; then
                if command -v busybox >/dev/null 2>&1; then
                    CHROOT_BIN="busybox chroot"
                else
                    echo "chroot binary not found on host or guest rootfs" >&2
                    exit 127
                fi
            fi

            # Run the guest command as a child and forward INT/TERM to it. With a plain foreground command the shell only runs
            # its trap after the command exits, so a cancelled npm install or Gradle build kept running until it finished, and
            # a later SIGKILL of this script orphaned it and skipped the ownership fix. Stdin (the
            # Claude prompt) is passed through fd 3: a non-interactive shell gives background commands /dev/null.
            child_status=0
            exec 3<&0
            forward_signal() {
                kill "-${'$'}1" "${'$'}child" 2>/dev/null || true
            }

            ${'$'}CHROOT_BIN "${'$'}ROOTFS" /usr/bin/env \
                GUEST_WORKSPACE="${'$'}GUEST_WORKSPACE" \
                /bin/sh -c 'cd "${'$'}GUEST_WORKSPACE" && exec "${'$'}@"' -- "${'$'}@" <&3 3<&- &
            child=${'$'}!
            trap 'forward_signal INT' INT
            trap 'forward_signal TERM' TERM
            # wait returns early when a trapped signal arrives; keep waiting until the child has really exited.
            while :; do
                # `|| child_status=...` keeps set -e from aborting when a trapped signal interrupts wait (status 128+N).
                child_status=0
                wait "${'$'}child" || child_status=${'$'}?
                kill -0 "${'$'}child" 2>/dev/null || break
            done
            exit "${'$'}child_status"
        """.trimIndent() + "\n"

        private val UNMOUNT_CHROOT_SCRIPT = """
            #!/system/bin/sh
            ROOTFS="${'$'}1"
            if [ -z "${'$'}ROOTFS" ]; then
                echo "Usage: unmount-chroot.sh <rootfs_path>" >&2
                exit 2
            fi
            ROOTFS_REAL=${'$'}(realpath "${'$'}ROOTFS" 2>/dev/null || printf '%s' "${'$'}ROOTFS")
            ROOTFS_CLEAN="${'$'}{ROOTFS_REAL%/}"
            # Make every mount under the rootfs private before unmounting: the Bionic directories are recursive copies of
            # shared Android mounts, and unmounting a copy that is still a peer would also unmount the original.
            for mp in ${'$'}(awk -v prefix="${'$'}ROOTFS_CLEAN/" -v exact="${'$'}ROOTFS_CLEAN" 'index(${'$'}2, prefix) == 1 || ${'$'}2 == exact { print ${'$'}2 }' /proc/mounts | sort); do
                mount --make-private "${'$'}mp" 2>/dev/null || mount -o private "${'$'}mp" 2>/dev/null || true
            done

            for pass in 1 2 3; do
                MOUNTS=${'$'}(awk -v prefix="${'$'}ROOTFS_CLEAN/" -v exact="${'$'}ROOTFS_CLEAN" 'index(${'$'}2, prefix) == 1 || ${'$'}2 == exact { print ${'$'}2 }' /proc/mounts | sort -r)
                if [ -z "${'$'}MOUNTS" ]; then
                    break
                fi
                for mnt in ${'$'}MOUNTS; do
                    if [ "${'$'}pass" -lt 3 ]; then
                        umount "${'$'}mnt" 2>/dev/null || true
                    else
                        umount -l "${'$'}mnt" 2>/dev/null || umount -f "${'$'}mnt" 2>/dev/null || true
                    fi
                done
            done
            if awk -v prefix="${'$'}ROOTFS_CLEAN/" -v exact="${'$'}ROOTFS_CLEAN" 'index(${'$'}2, prefix) == 1 || ${'$'}2 == exact { found=1; exit } END { exit !found }' /proc/mounts; then
                exit 1
            fi
            rm -rf "${'$'}ROOTFS_CLEAN/tmp/.nextcraft-mounts" 2>/dev/null || true
            exit 0
        """.trimIndent() + "\n"
    }
}

internal data class GitConfigEntry(
    val section: String,
    val key: String,
    val value: String,
    val replace: Boolean = false,
    /** For multi-valued keys (safe.directory): add the value unless that exact value is already there. */
    val multiValue: Boolean = false,
)

/**
 * Adds [entries] to a git config text. An entry whose key already exists in its section is kept as the user set it,
 * unless [GitConfigEntry.replace]. Sections are matched by exact `[name]` header (no subsections are needed here).
 */
internal fun mergeGitConfig(existing: String, entries: List<GitConfigEntry>): String {
    val lines = existing.lines().dropLastWhile { it.isBlank() }.toMutableList()
    fun sectionOf(line: String) = Regex("^\\s*\\[\\s*([A-Za-z0-9.-]+)\\s*]\\s*$").find(line)?.groupValues?.get(1)?.lowercase()
    fun isHeader(line: String) = Regex("^\\s*\\[[^\\]]*]").containsMatchIn(line)
    fun keyOf(line: String) = Regex("^\\s*([A-Za-z][A-Za-z0-9-]*)\\s*(=|$)").find(line)?.groupValues?.get(1)?.lowercase()
    for (entry in entries) {
        val wanted = entry.section.lowercase()
        val start = lines.indexOfFirst { sectionOf(it) == wanted }
        val formatted = "    ${entry.key} = ${entry.value}"
        if (start < 0) {
            lines += "[${entry.section}]"
            lines += formatted
            continue
        }
        var end = start + 1
        // Any header ends the section, including subsections such as [credential "https://github.com"], so an entry is
        // never appended inside (or matched against) another section's keys.
        while (end < lines.size && !isHeader(lines[end])) end++
        val sameKey = (start + 1 until end).filter { keyOf(lines[it]) == entry.key.lowercase() }
        if (entry.multiValue) {
            val present = sameKey.any { lines[it].substringAfter('=', "").trim() == entry.value }
            if (!present) lines.add(end, formatted)
            continue
        }
        val existingIndex = sameKey.firstOrNull()
        when {
            existingIndex == null -> lines.add(end, formatted)
            entry.replace -> lines[existingIndex] = formatted
        }
    }
    return lines.joinToString("\n") + "\n"
}
