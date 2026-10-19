package com.nextcraft.forge.runtime

import android.content.Context
import java.io.File

/**
 * How guest commands reach the Ubuntu rootfs.
 *
 * [CHROOT] is the primary engine (ADR-002): `su -mm` + kernel mounts + `chroot-exec.sh`. [PROOT] is the
 * non-root fallback (ADR-007): the static `libproot.so` shipped in the APK translates paths with ptrace, so no
 * mounts, no root, and no `chown` are involved, at the cost of slower syscalls.
 */
enum class RuntimeEngine { CHROOT, PROOT }

/**
 * Engine selection, cached per process: the first `su` call shows the Magisk/KernelSU/APatch dialog, so it must not
 * be repeated on every launch. Code that has no [Context] (RootfsRepair, native spawn) reads [current].
 */
object RuntimeEngines {
    const val PROOT_LIBRARY = "libproot.so"

    @Volatile
    private var detected: RuntimeEngine? = null

    @Volatile
    private var detectionDone = false

    /** The PRoot binary lives in the APK's native library directory: that is the only app-writable-free place Android lets an app exec from. */
    fun prootBinary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, PROOT_LIBRARY)

    fun isProotAvailable(context: Context): Boolean = prootBinary(context).let { it.isFile && it.canExecute() }

    /** Root wins when granted; otherwise PRoot when the APK carries it; `null` when neither is usable. */
    fun detect(context: Context, rootTimeoutSeconds: Long = 8L, refresh: Boolean = false): RuntimeEngine? {
        if (detectionDone && !refresh) return detected
        val engine = when {
            isRootAccessAvailable(rootTimeoutSeconds) -> RuntimeEngine.CHROOT
            isProotAvailable(context) -> RuntimeEngine.PROOT
            else -> null
        }
        detected = engine
        detectionDone = true
        return engine
    }

    /** Last detected engine; CHROOT until a detection ran, which keeps the root-only paths unchanged for callers without a context. */
    fun current(): RuntimeEngine = detected ?: RuntimeEngine.CHROOT

    fun isRootEngine(): Boolean = current() == RuntimeEngine.CHROOT

    /**
     * Process arguments that run [shellCommand] (the `. env; rm -f env; exec …` line built by
     * [RuntimeInstaller.prepareChrootShellCommand]) under the active engine.
     */
    fun launchArgv(shellCommand: String): List<String> = when (current()) {
        RuntimeEngine.CHROOT -> listOf("su", "-mm", "-c", shellCommand)
        RuntimeEngine.PROOT -> listOf("/system/bin/sh", "-c", shellCommand)
    }
}
