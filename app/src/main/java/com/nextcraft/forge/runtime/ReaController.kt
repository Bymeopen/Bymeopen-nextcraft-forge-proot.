package com.nextcraft.forge.runtime

import android.content.Context
import com.nextcraft.forge.R
import com.nextcraft.forge.ui.UiText
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReaState(
    /** Version recorded by the installer, null when REA is not installed in the chroot. */
    val installedVersion: String? = null,
    val busy: Boolean = false,
    val log: String = "",
    /** "full-dynamic", "emulated-dynamic" or "static-only" after a doctor run. */
    val mode: String? = null,
    val checks: List<ReaCheck> = emptyList(),
    val message: UiText? = null,
)

/**
 * REA (Reverse Engine Anything) inside the chroot: installs its Python package from the app's assets, removes it, and
 * runs `rea doctor --json`. Same pattern as the other Tools controllers: a [ChrootRunner] run per command, output
 * streamed into a log, results parsed from the one JSON line the command prints last.
 */
class ReaController(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runner = ChrootRunner(appContext)
    private val mutable = MutableStateFlow(ReaState())
    val state: StateFlow<ReaState> = mutable.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        scope.launch {
            val version = withContext(Dispatchers.IO) { readInstalledVersion() }
            mutable.update { it.copy(installedVersion = version) }
        }
    }

    fun install() = launchTask(R.string.rea_installing) {
        val workspace = runner.toolWorkspace(TOOL_SLUG)
        val stage = File(workspace, "stage")
        withContext(Dispatchers.IO) {
            stage.deleteRecursively()
            copyAssetDirectory(ASSET_ROOT, stage)
        }
        try {
            val result = runner.run(
                workspaceDir = workspace,
                slug = TOOL_SLUG,
                command = installScript(),
                timeoutMillis = 15 * 60_000L,
                onChunk = ::appendLog,
            )
            val version = withContext(Dispatchers.IO) { readInstalledVersion() }
            mutable.update {
                it.copy(
                    installedVersion = version,
                    message = if (result.ok && version != null) UiText.res(R.string.rea_installed, version)
                    else UiText.res(R.string.rea_install_failed),
                )
            }
        } finally {
            withContext(Dispatchers.IO) { stage.deleteRecursively() }
        }
    }

    fun uninstall() = launchTask(R.string.rea_removing) {
        val result = runner.run(
            workspaceDir = runner.toolWorkspace(TOOL_SLUG),
            slug = TOOL_SLUG,
            command = "rm -rf /opt/rea /usr/local/bin/rea /root/.pocket-rea-version /root/.rea && echo removed",
            onChunk = ::appendLog,
        )
        val version = withContext(Dispatchers.IO) { readInstalledVersion() }
        mutable.update {
            it.copy(
                installedVersion = version,
                mode = null,
                checks = emptyList(),
                message = if (result.ok) UiText.res(R.string.rea_removed) else UiText.res(R.string.rea_remove_failed),
            )
        }
    }

    fun runDoctor() = launchTask(R.string.rea_checking) {
        val result = runner.run(
            workspaceDir = runner.toolWorkspace(TOOL_SLUG),
            slug = TOOL_SLUG,
            command = "rea doctor --json",
            timeoutMillis = 2 * 60_000L,
        )
        val response = ReaJson.parse(result.output)
        mutable.update {
            when {
                response == null -> it.copy(
                    mode = null,
                    checks = emptyList(),
                    log = result.output.takeLast(MAX_LOG_CHARS),
                    message = UiText.res(R.string.rea_no_answer),
                )
                !response.ok -> it.copy(
                    mode = null,
                    checks = emptyList(),
                    message = UiText.Raw(listOfNotNull(response.error?.message, response.error?.hint?.takeIf(String::isNotBlank)).joinToString(" ")),
                )
                else -> it.copy(mode = response.mode, checks = response.checks, message = null)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        mutable.update { it.copy(busy = false) }
    }

    private fun launchTask(@androidx.annotation.StringRes label: Int, block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = UiText.res(label), log = "") }
        job = scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(message = UiText.Raw(error.message ?: error.javaClass.simpleName)) }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    private fun appendLog(chunk: String) {
        mutable.update { it.copy(log = (it.log + chunk).takeLast(MAX_LOG_CHARS)) }
    }

    private fun readInstalledVersion(): String? = runCatching {
        File(RuntimeInstaller.rootfsDir(appContext), "root/.pocket-rea-version").takeIf { it.isFile }?.readText()?.trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Copies an asset directory (recursively) to the host [target]; Python bytecode caches are not shipped. */
    private fun copyAssetDirectory(assetPath: String, target: File) {
        val entries = appContext.assets.list(assetPath).orEmpty()
        if (entries.isEmpty()) {
            target.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        for (name in entries) {
            if (name == "__pycache__") continue
            copyAssetDirectory("$assetPath/$name", File(target, name))
        }
    }

    companion object {
        /** Must equal `__version__` in the Python package (a unit test checks it). */
        const val REA_VERSION = "0.2.0"
        private const val TOOL_SLUG = "rea"
        private const val ASSET_ROOT = "rea"
        private const val MAX_LOG_CHARS = 60_000

        /**
         * Runs in the chroot as root. The package was staged in the tool workspace (/workspace/rea/stage) by the app,
         * so no file is written into the rootfs from the app's own uid. Python 3 is installed from apt only if missing.
         */
        internal fun installScript(): String = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            if ! command -v python3 >/dev/null 2>&1; then
              echo "Memasang python3 (apt)…"
              apt-get update -qq
              apt-get install -y -qq python3
            fi
            # Libraries for ELF parsing and disassembly (REA 0.2): from apt, so no pip or venv is needed. If they cannot
            # be installed the analysis commands say so and `rea doctor` shows what is missing.
            apt-get update -qq >/dev/null 2>&1 || true
            apt-get install -y -qq python3-capstone python3-pyelftools || echo "Peringatan: capstone/pyelftools gagal dipasang."
            rm -rf /opt/rea/lib/rea
            mkdir -p /opt/rea/lib /usr/local/bin
            cp -a /workspace/rea/stage/rea /opt/rea/lib/rea
            printf '#!/bin/sh\nexec env PYTHONPATH=/opt/rea/lib PYTHONDONTWRITEBYTECODE=1 python3 -m rea "§@"\n' > /usr/local/bin/rea
            chmod 755 /usr/local/bin/rea
            echo "$REA_VERSION" > /root/.pocket-rea-version
            rea --version
        """.trimIndent().replace('§', '$')
    }
}
