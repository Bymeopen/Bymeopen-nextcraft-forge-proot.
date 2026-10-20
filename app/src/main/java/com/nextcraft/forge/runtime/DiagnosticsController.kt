package com.nextcraft.forge.runtime

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import java.io.File
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

data class DiagnosticsState(
    val device: List<Pair<String, String>> = emptyList(),
    val runtime: List<Pair<String, String>> = emptyList(),
    val healthOutput: String = "",
    val healthRunning: Boolean = false,
    val healthSummary: String? = null,
)

/** System diagnostics: device resources, runtime state and the chroot health check (chroot-health.sh). */
class DiagnosticsController(private val context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runner = ChrootRunner(appContext)
    private val installer = RuntimeInstaller(appContext)
    private val mutable = MutableStateFlow(DiagnosticsState())
    val state: StateFlow<DiagnosticsState> = mutable.asStateFlow()
    private var healthJob: Job? = null

    fun refresh() {
        scope.launch {
            val device = withContext(Dispatchers.IO) { collectDevice() }
            val runtime = withContext(Dispatchers.IO) { collectRuntime() }
            mutable.update { it.copy(device = device, runtime = runtime) }
        }
    }

    fun runHealthCheck() {
        if (mutable.value.healthRunning) return
        mutable.update { it.copy(healthOutput = "", healthRunning = true, healthSummary = null) }
        healthJob = scope.launch {
            val result = runCatching {
                val workspace = runner.toolWorkspace(WORKSPACE_SLUG)
                File(workspace, "chroot-health.sh").writeText(
                    appContext.assets.open("chroot-health.sh").bufferedReader().use { it.readText() },
                )
                runner.run(
                    workspaceDir = workspace,
                    slug = WORKSPACE_SLUG,
                    command = "bash /workspace/$WORKSPACE_SLUG/chroot-health.sh 2>&1",
                    timeoutMillis = 8 * 60_000L,
                    onChunk = { chunk -> mutable.update { it.copy(healthOutput = (it.healthOutput + chunk).takeLast(MAX_LOG_CHARS)) } },
                )
            }
            mutable.update { current ->
                result.fold(
                    onSuccess = { current.copy(healthRunning = false, healthSummary = current.healthOutput.lineSequence().lastOrNull { it.startsWith("Selesai") }) },
                    onFailure = { current.copy(healthRunning = false, healthOutput = current.healthOutput + "\n" + (it.message ?: "Health check failed")) },
                )
            }
        }
    }

    fun cancelHealthCheck() {
        healthJob?.cancel()
        mutable.update { it.copy(healthRunning = false) }
    }

    private fun collectDevice(): List<Pair<String, String>> {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        val stat = runCatching { StatFs(appContext.filesDir.absolutePath) }.getOrNull()
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val charging = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            ?.let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL } == true
        return buildList {
            add("Device" to "${Build.MANUFACTURER} ${Build.MODEL}")
            add("Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            add("CPU" to "${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()} · ${Runtime.getRuntime().availableProcessors()} cores")
            add("RAM" to "${formatBytes(memory.availMem)} free of ${formatBytes(memory.totalMem)}")
            if (stat != null) add("Storage" to "${formatBytes(stat.availableBytes)} free of ${formatBytes(stat.totalBytes)}")
            if (level >= 0 && scale > 0) add("Battery" to "${level * 100 / scale}%" + if (charging) " · charging" else "")
        }
    }

    private fun collectRuntime(): List<Pair<String, String>> {
        val rootfs = RuntimeInstaller.rootfsDir(appContext)
        val installed = runCatching { installer.isInstalled() }.getOrDefault(false)
        return buildList {
            add("Runtime engine" to (runCatching { installer.runtimeEngine() }.getOrNull()?.name?.lowercase() ?: "missing"))
            add("Linux runtime" to if (installed) "installed" else "not installed")
            runCatching { File(rootfs, ".pocket-rootfs-version").takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { add("Rootfs" to it) }
            if (installed) {
                val stacks = runCatching { installer.installedStacks().joinToString { it.label } }.getOrDefault("")
                add("Stacks" to stacks.ifBlank { "none" })
                val agents = runCatching { installer.installedAgentVersions().entries.joinToString { "${it.key.title} ${it.value}" } }.getOrDefault("")
                add("Agents" to agents.ifBlank { "none" })
            }
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        else -> "%d MB".format(bytes / (1L shl 20))
    }

    private companion object {
        const val WORKSPACE_SLUG = "diagnostics"
        const val MAX_LOG_CHARS = 60_000
    }
}
