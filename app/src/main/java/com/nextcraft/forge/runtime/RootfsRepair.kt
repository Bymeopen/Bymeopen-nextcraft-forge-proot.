package com.nextcraft.forge.runtime

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Failure-safe activation of a rebuilt Ubuntu 24.04 Chroot environment while
 * retaining the user's `/root` home directory and guaranteeing that all kernel
 * bind-mounts (`/dev`, `/proc`, `/sys`, `/system`, `/apex`, `/workspace`) are
 * unmounted before any directory rename or recursive delete occurs.
 */
internal object RootfsRepair {

    private fun runRootCommand(vararg command: String, timeoutSeconds: Long = 15L): Int =
        runCatching {
            // Output is never used; discarding it avoids a full pipe blocking the child until the timeout.
            val proc = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(java.io.File("/dev/null")))
                .start()
            val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                -1
            } else {
                proc.exitValue()
            }
        }.getOrDefault(-1)

    private fun moveDirectory(source: File, target: File): Boolean {
        if (source.renameTo(target)) return true
        // Never copyRecursively a rootfs: it follows symlinks and drops ownership, modes and
        // special files. A root `mv` preserves all of them (and is a plain rename on one fs).
        if (target.exists()) return false
        require(RootPathGuard.isInsideAppData(source.canonicalPath) && RootPathGuard.isInsideAppData(target.canonicalPath)) {
            "Refusing to move outside the app data directory"
        }
        val exit = runRootCommand(
            "su", "-c",
            "mv -- ${shellSingleQuote(source.absolutePath)} ${shellSingleQuote(target.absolutePath)}",
            timeoutSeconds = 120L,
        )
        return exit == 0 && target.exists() && !source.exists()
    }

    /**
     * Unmounts every kernel mount inside [targetDir] in reverse mount-depth order
     * across both the master mount namespace (`su -mm`) and default root context (`su`),
     * and throws if `/proc/mounts` still reports any active mount under [targetDir].
     */
    fun ensureUnmounted(targetDir: File): Boolean {
        if (!targetDir.exists()) return true
        val canonicalPath = runCatching { targetDir.canonicalPath }.getOrDefault(targetDir.absolutePath).trimEnd('/')
        require(canonicalPath.length > 1 && canonicalPath.startsWith("/")) {
            "Refusing to operate on invalid rootfs path: '$canonicalPath'"
        }
        val quotedRootfs = shellSingleQuote(canonicalPath)
        val unmountScript = """
            ROOTFS_PATH=$quotedRootfs
            # Make every mount under the rootfs private before unmounting: the Bionic directories are recursive copies of
            # shared Android mounts, and unmounting a copy that is still a peer would also unmount the original.
            for mp in ${'$'}(awk -v prefix="${'$'}ROOTFS_PATH/" -v exact="${'$'}ROOTFS_PATH" 'index(${'$'}2, prefix) == 1 || ${'$'}2 == exact { print ${'$'}2 }' /proc/mounts | sort); do
                mount --make-private "${'$'}mp" 2>/dev/null || mount -o private "${'$'}mp" 2>/dev/null || true
            done

            for pass in 1 2 3; do
                MOUNTS=${'$'}(awk -v prefix="${'$'}ROOTFS_PATH/" -v exact="${'$'}ROOTFS_PATH" 'index(${'$'}2, prefix) == 1 || ${'$'}2 == exact { print ${'$'}2 }' /proc/mounts | sort -r)
                [ -z "${'$'}MOUNTS" ] && break
                for mp in ${'$'}MOUNTS; do
                    if [ "${'$'}pass" -lt 3 ]; then
                        umount "${'$'}mp" 2>/dev/null || true
                    else
                        umount -lf "${'$'}mp" 2>/dev/null || umount -f "${'$'}mp" 2>/dev/null || true
                    fi
                done
            done
            if awk -v prefix="${'$'}ROOTFS_PATH/" -v exact="${'$'}ROOTFS_PATH" 'index(${'$'}2, prefix) == 1 || ${'$'}2 == exact { found=1 } END { exit !found }' /proc/mounts; then
                exit 1
            fi
            rm -rf "${'$'}{ROOTFS_PATH}/tmp/.nextcraft-mounts" 2>/dev/null || true
            exit 0
        """.trimIndent()

        val masterCode = runRootCommand("su", "-mm", "-c", unmountScript)
        val defaultCode = runRootCommand("su", "-c", unmountScript)
        check(masterCode == 0 && defaultCode == 0) {
            "Refusing to modify or delete Chroot directory '$canonicalPath' while kernel mounts are still active (master=$masterCode, default=$defaultCode)"
        }
        return true
    }

    /**
     * Gives [target] (recursively) back to [uid]. Commands inside the chroot run as root, so files they create
     * (npm installs, Gradle/pip caches, Claude's own state) are root-owned and the app UID cannot change them.
     */
    fun reclaimOwnership(target: File, uid: Int, recursive: Boolean = true): Boolean {
        if (!target.exists() && !java.nio.file.Files.isSymbolicLink(target.toPath())) return true
        val canonicalPath = target.canonicalPath.trimEnd('/')
        require(canonicalPath.length > 1 && canonicalPath.startsWith("/")) {
            "Refusing to change ownership of invalid path: '$canonicalPath'"
        }
        require(RootPathGuard.isInsideAppData(canonicalPath)) {
            "Refusing to change ownership outside the app data directory: '$canonicalPath'"
        }
        // -h: change a symlink itself, never the file it points to (it may point outside the rootfs).
        val exit = runRootCommand(
            "su", "-c",
            "chown ${if (recursive) "-R " else ""}-h $uid:$uid -- ${shellSingleQuote(canonicalPath)}",
            timeoutSeconds = 120L,
        )
        return exit == 0
    }

    /**
     * Last resort when the app UID cannot write [target] even after [reclaimOwnership] (a root-owned file in a folder the
     * app does not own, or a label the app may not write): the bytes are staged in an app-owned [staging] file and root
     * copies them in, then hands the file to [uid].
     */
    fun writeAsRoot(target: File, content: ByteArray, staging: File, uid: Int, mode: Int = 0b110100100): Boolean {
        val parent = target.parentFile ?: return false
        if (!RootPathGuard.isInsideAppData(target.canonicalPath)) return false
        staging.parentFile?.mkdirs()
        return try {
            staging.writeBytes(content)
            val dest = shellSingleQuote(target.absolutePath)
            val temp = shellSingleQuote(target.absolutePath + ".nextcraft-tmp")
            // Write beside the target and `mv -f` over it: rename replaces a symlink itself (an `apt install` puts
            // /etc/resolv.conf back as a link into /run/systemd), where `cat > target` would follow the link instead.
            val exit = runRootCommand(
                "su", "-c",
                "mkdir -p -- ${shellSingleQuote(parent.absolutePath)} && cat -- ${shellSingleQuote(staging.absolutePath)} > $temp && " +
                    "chown $uid:$uid -- $temp && chmod ${mode.toString(8)} -- $temp && mv -f -- $temp $dest",
                timeoutSeconds = 30L,
            )
            exit == 0
        } finally {
            staging.delete()
        }
    }

    fun safeDeleteRecursively(targetDir: File): Boolean {
        if (!targetDir.exists()) return true
        ensureUnmounted(targetDir)
        if (targetDir.deleteRecursively()) return true
        // Fallback to root rm -rf ONLY after ensureUnmounted verified 0 active kernel mounts
        val canonicalPath = targetDir.canonicalPath.trimEnd('/')
        require(canonicalPath.length > 1 && canonicalPath.startsWith("/")) {
            "Refusing to delete invalid path: '$canonicalPath'"
        }
        require(RootPathGuard.isInsideAppData(canonicalPath)) {
            "Refusing to delete outside the app data directory: '$canonicalPath'"
        }
        val quotedPath = shellSingleQuote(canonicalPath)
        val exit = runRootCommand(
            "su",
            "-c",
            "rm -rf --one-file-system -- $quotedPath 2>/dev/null || rm -rf -- $quotedPath",
            // A multi-GB tree with root-owned apt/npm files takes far longer than the 15 s default; a timeout here
            // used to make activate() roll back a rootfs that had already validated.
            timeoutSeconds = 30 * 60L,
        )
        return exit == 0 && !targetDir.exists()
    }

    fun reconcileInterruptedSwap(
        rootfs: File,
        staging: File,
        backup: File,
        validate: (File) -> Boolean,
    ) {
        if (!backup.exists()) return

        ensureUnmounted(rootfs)
        ensureUnmounted(staging)
        ensureUnmounted(backup)

        if (!rootfs.exists()) {
            check(moveDirectory(backup, rootfs)) { "Could not restore the previous Linux environment" }
            safeDeleteRecursively(staging)
            return
        }

        if (validate(rootfs)) {
            restoreHome(backup, rootfs)
            check(validate(rootfs)) { "Repaired Linux environment failed validation" }
            check(safeDeleteRecursively(backup)) { "Could not remove the previous Linux environment" }
            safeDeleteRecursively(staging)
            return
        }

        rollback(rootfs, staging, backup)
    }

    fun activate(
        rootfs: File,
        staging: File,
        backup: File,
        validate: (File) -> Boolean,
    ) {
        require(staging.isDirectory && validate(staging)) {
            "Rebuilt Linux environment failed validation"
        }
        check(!backup.exists()) { "A previous Linux environment repair is still pending" }

        ensureUnmounted(rootfs)
        ensureUnmounted(staging)

        val hadRootfs = rootfs.exists()
        if (hadRootfs) {
            check(moveDirectory(rootfs, backup)) { "Could not preserve the previous Linux environment" }
        }

        try {
            check(moveDirectory(staging, rootfs)) { "Could not activate the rebuilt Linux environment" }
            if (hadRootfs) restoreHome(backup, rootfs)
            check(validate(rootfs)) { "Rebuilt Linux environment failed validation after activation" }
            // The new rootfs is validated and active. Failing to delete the old copy must not roll it back: a leftover
            // backup is cleaned up by reconcileInterruptedSwap on the next start.
            if (hadRootfs) safeDeleteRecursively(backup)
        } catch (error: Throwable) {
            if (hadRootfs && backup.exists()) {
                runCatching { rollback(rootfs, staging, backup) }
                    .onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    private fun restoreHome(backup: File, rootfs: File) {
        val preservedHome = File(backup, "root")
        if (!preservedHome.exists()) return
        val freshHome = File(rootfs, "root")
        if (freshHome.exists()) {
            check(safeDeleteRecursively(freshHome)) { "Could not replace the rebuilt Linux home" }
        }
        check(moveDirectory(preservedHome, freshHome)) { "Could not restore the Linux home" }
    }

    private fun rollback(rootfs: File, staging: File, backup: File) {
        ensureUnmounted(rootfs)
        ensureUnmounted(staging)
        ensureUnmounted(backup)

        val activeHome = File(rootfs, "root")
        val backupHome = File(backup, "root")
        if (!backupHome.exists() && activeHome.exists()) {
            check(moveDirectory(activeHome, backupHome)) { "Could not preserve the Linux home during rollback" }
        }
        if (rootfs.exists()) {
            check(safeDeleteRecursively(rootfs)) { "Could not remove the failed Linux environment" }
        }
        check(moveDirectory(backup, rootfs)) { "Could not restore the previous Linux environment" }
        safeDeleteRecursively(staging)
    }
}
