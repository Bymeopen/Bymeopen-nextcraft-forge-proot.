package com.nextcraft.forge.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nextcraft.forge.R
import com.nextcraft.forge.runtime.AndroidBuildIssue
import com.nextcraft.forge.runtime.AndroidBuildStage

/** Stage text for the build screen (translated). */
@Composable
fun AndroidBuildStage.displayLabel(): String = stringResource(
    when (this) {
        AndroidBuildStage.IDLE -> R.string.build_stage_idle
        AndroidBuildStage.PREPARING -> R.string.build_stage_preparing
        AndroidBuildStage.RESOLVING -> R.string.build_stage_resolving
        AndroidBuildStage.RESOURCES -> R.string.build_stage_resources
        AndroidBuildStage.COMPILING -> R.string.build_stage_compiling
        AndroidBuildStage.PACKAGING -> R.string.build_stage_packaging
        AndroidBuildStage.FINDING_APK -> R.string.build_stage_finding_apk
        AndroidBuildStage.INSTALLING -> R.string.build_stage_installing
        AndroidBuildStage.LAUNCHING -> R.string.build_stage_launching
        AndroidBuildStage.COMPLETE -> R.string.build_stage_complete
        AndroidBuildStage.FAILED -> R.string.build_stage_failed
        AndroidBuildStage.CANCELLING -> R.string.build_stage_cancelling
        AndroidBuildStage.CANCELLED -> R.string.build_stage_cancelled
    },
)

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

/** Sent to the agent as its prompt (not shown to the user), so it stays English. */
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
