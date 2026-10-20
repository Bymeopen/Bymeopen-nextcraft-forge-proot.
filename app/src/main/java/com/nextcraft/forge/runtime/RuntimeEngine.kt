package com.nextcraft.forge.runtime

import android.content.Context
import java.io.File

/**
 * How guest commands reach the Ubuntu rootfs. This build has a single engine: the static `libproot.so` shipped in
 * the APK translates paths with ptrace, so no root, no kernel mounts and no `chown` are involved.
 */
enum class RuntimeEngine { PROOT }

/** Engine availability, cached per process. Code without a [Context] (native spawn) reads [current]. */
object RuntimeEngines {
    const val PROOT_LIBRARY = "libproot.so"
    const val PROOT_LOADER_LIBRARY = "libproot_loader.so"

    @Volatile
    private var detected: RuntimeEngine? = null

    @Volatile
    private var detectionDone = false

    /** The PRoot binary lives in the APK's native library directory: the only place Android lets an app exec from. */
    fun prootBinary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, PROOT_LIBRARY)

    /** proot's ELF loader, which every guest execve runs through; it must be exec-able too, so it ships beside proot. */
    fun prootLoader(context: Context): File = File(context.applicationInfo.nativeLibraryDir, PROOT_LOADER_LIBRARY)

    fun isProotAvailable(context: Context): Boolean =
        prootBinary(context).let { it.isFile && it.canExecute() } && prootLoader(context).let { it.isFile && it.canExecute() }

    /** Pure decision, kept separate from the probe so it can be unit-tested. `null` means nothing can run. */
    fun resolve(prootAvailable: Boolean): RuntimeEngine? = RuntimeEngine.PROOT.takeIf { prootAvailable }

    fun detect(context: Context, refresh: Boolean = false): RuntimeEngine? {
        if (detectionDone && !refresh) return detected
        val engine = resolve(isProotAvailable(context))
        detected = engine
        detectionDone = true
        return engine
    }

    /** Forgets the cached result. */
    fun invalidate() {
        detectionDone = false
        detected = null
    }

    /** Last detected engine; PROOT until a detection ran. */
    fun current(): RuntimeEngine = detected ?: RuntimeEngine.PROOT

    /**
     * Process arguments that run [shellCommand] (the `. env; rm -f env; exec …` line built by
     * [RuntimeInstaller.prepareChrootShellCommand]). The app UID cannot exec files from its data directory, so the
     * line always goes through the system shell.
     */
    fun launchArgv(shellCommand: String, engine: RuntimeEngine = current()): List<String> = when (engine) {
        RuntimeEngine.PROOT -> listOf("/system/bin/sh", "-c", shellCommand)
    }

    /** Test hook: pretend a detection produced [engine]. */
    internal fun overrideForTests(engine: RuntimeEngine?) {
        detected = engine
        detectionDone = engine != null
    }
}
