package com.nextcraft.forge.runtime

import java.util.concurrent.TimeUnit

internal fun supportsArm64Runtime(supportedAbis: Array<String>, osArchitecture: String?): Boolean {
    val kernelIsArm64 = osArchitecture.equals("aarch64", ignoreCase = true) ||
        osArchitecture.equals("arm64", ignoreCase = true)
    return kernelIsArm64 && supportedAbis.any { it.equals("arm64-v8a", ignoreCase = true) }
}

/**
 * Verifies that the device has working root access (`Magisk`, `KernelSU`, or `APatch`)
 * required by NextCraft Forge's Pure Root Chroot engine.
 */
internal fun isRootAccessAvailable(timeoutSeconds: Long = 8L): Boolean = runCatching {
    val proc = ProcessBuilder("su", "-c", "id -u")
        .redirectErrorStream(true)
        .start()
    val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
        proc.destroyForcibly()
        return@runCatching false
    }
    val output = proc.inputStream.bufferedReader().use { it.readText().trim() }
    proc.exitValue() == 0 && output.lines().lastOrNull()?.trim() == "0"
}.getOrDefault(false)

/**
 * Disables Android 12–15+ Phantom Process Killer (`max_phantom_processes`) via `su`
 * so background Chroot daemon processes (`Gradle`, `Kotlin`, `Node`, `Claude Code`)
 * are not killed by `ActivityManager` under heavy compilation load.
 */
internal fun disablePhantomProcessKillerViaRoot() {
    runCatching {
        val cmd = """
            /system/bin/device_config set_sync_disabled_for_tests persistent 2>/dev/null || true
            /system/bin/device_config put activity_manager max_phantom_processes 2147483647 2>/dev/null || true
            settings put global settings_enable_monitor_phantom_procs false 2>/dev/null || true
        """.trimIndent()
        val proc = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        if (!proc.waitFor(5L, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
        } else {
            runCatching { proc.inputStream.bufferedReader().use { it.readText() } }
        }
    }
}
