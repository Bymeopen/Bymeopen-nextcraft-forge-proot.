package com.nextcraft.forge.ui

import com.nextcraft.forge.runtime.AndroidBuildIssue
import com.nextcraft.forge.runtime.AndroidBuildPhase
import com.nextcraft.forge.runtime.AndroidBuildStage

fun AndroidBuildStage.displayLabel(): String = when (this) {
    AndroidBuildStage.IDLE -> "Idle"
    AndroidBuildStage.PREPARING -> "Preparing workspace & Chroot mounts…"
    AndroidBuildStage.RESOLVING -> "Resolving Maven dependencies…"
    AndroidBuildStage.RESOURCES -> "Compiling Android resources (aapt2)…"
    AndroidBuildStage.COMPILING -> "Compiling Kotlin & Java sources…"
    AndroidBuildStage.PACKAGING -> "Packaging & signing Debug APK…"
    AndroidBuildStage.FINDING_APK -> "Locating output Debug APK…"
    AndroidBuildStage.INSTALLING -> "Installing APK on device…"
    AndroidBuildStage.LAUNCHING -> "Launching installed application…"
    AndroidBuildStage.COMPLETE -> "Build complete"
    AndroidBuildStage.FAILED -> "Build failed"
    AndroidBuildStage.CANCELLING -> "Cancelling build…"
    AndroidBuildStage.CANCELLED -> "Build cancelled"
}

fun AndroidBuildStage.progressFraction(): Float = when (this) {
    AndroidBuildStage.IDLE -> 0f
    AndroidBuildStage.PREPARING -> 0.10f
    AndroidBuildStage.RESOLVING -> 0.25f
    AndroidBuildStage.RESOURCES -> 0.45f
    AndroidBuildStage.COMPILING -> 0.70f
    AndroidBuildStage.PACKAGING -> 0.85f
    AndroidBuildStage.FINDING_APK -> 0.92f
    AndroidBuildStage.INSTALLING -> 0.96f
    AndroidBuildStage.LAUNCHING -> 0.98f
    AndroidBuildStage.COMPLETE -> 1.0f
    AndroidBuildStage.FAILED -> 1.0f
    AndroidBuildStage.CANCELLING -> 0.50f
    AndroidBuildStage.CANCELLED -> 0f
}

fun AndroidBuildPhase.displayBadge(): String = when (this) {
    AndroidBuildPhase.IDLE -> "READY"
    AndroidBuildPhase.PREPARING -> "PREPARING"
    AndroidBuildPhase.BUILDING -> "BUILDING"
    AndroidBuildPhase.CANCELLING -> "STOPPING"
    AndroidBuildPhase.SUCCEEDED -> "PASSED"
    AndroidBuildPhase.FAILED -> "FAILED"
    AndroidBuildPhase.CANCELLED -> "CANCELLED"
}

fun AndroidBuildIssue.formatForAgentPrompt(): String = buildString {
    append("Please fix this Android build error")
    filePath?.let { path ->
        append(" in `")
        append(path)
        line?.let { append(":").append(it) }
        append("`")
    }
    append(":\n\n")
    append("- **").append(title).append("**: ").append(detail)
    suggestion?.takeIf { it.isNotBlank() }?.let {
        append("\n- Hint: ").append(it)
    }
}
