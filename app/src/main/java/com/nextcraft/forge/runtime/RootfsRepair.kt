package com.nextcraft.forge.runtime

import java.io.File

/**
 * Failure-safe activation of a rebuilt Ubuntu 24.04 rootfs while retaining the user's `/root` home directory.
 *
 * PRoot never mounts anything (bindings are ptrace path translations) and every guest process runs as the app
 * UID, so there is nothing to unmount and nothing root-owned to reclaim: all operations are plain file moves and
 * deletes done by the app itself.
 */
internal object RootfsRepair {

    private fun moveDirectory(source: File, target: File): Boolean {
        if (target.exists()) return false
        return source.renameTo(target)
    }

    fun safeDeleteRecursively(targetDir: File): Boolean {
        if (!targetDir.exists()) return true
        return targetDir.deleteRecursively() || !targetDir.exists()
    }

    fun reconcileInterruptedSwap(
        rootfs: File,
        staging: File,
        backup: File,
        validate: (File) -> Boolean,
    ) {
        if (!backup.exists()) return

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
