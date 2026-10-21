package com.nextcraft.forge.runtime

import android.content.Context
import android.os.SystemClock

/** Formats an elapsed duration as `m:ss`, or `h:mm:ss` once it passes an hour. */
internal fun formatElapsedShort(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/**
 * Foreground-service reporting shared by the agent bridges: throttled progress text with elapsed time, and a
 * finish/cancel notification that is posted at most once per turn.
 */
internal class ForegroundSession(
    private val context: Context,
    private val tag: String,
) {
    @Volatile private var startedAt = 0L
    @Volatile private var lastProgressAt = 0L
    @Volatile private var resultPosted = false

    /** Marks the start of a turn: resets the timer, the progress throttle and the posted-result guard. */
    fun begin() {
        startedAt = SystemClock.elapsedRealtime()
        lastProgressAt = 0L
        resultPosted = false
    }

    /** Stops the elapsed timer so later progress lines carry no duration. */
    fun clearStart() {
        startedAt = 0L
    }

    fun start(projectName: String) {
        RuntimeExecutionServiceContract.startForeground(context, projectName)
    }

    fun progress(projectSlug: String?, detailRaw: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressAt < PROGRESS_MIN_INTERVAL_MS) return
        lastProgressAt = now
        val detail = detailRaw.replace(Regex("\\s+"), " ").trim().take(110)
        val elapsedMs = startedAt.takeIf { it > 0 }?.let { now - it } ?: 0L
        val text = if (elapsedMs > 0L) "$detail · ${formatElapsedShort(elapsedMs)}" else detail
        RuntimeExecutionServiceContract.pushProgress(context, projectSlug, text)
    }

    fun finish(completed: Boolean, projectName: String, detail: String) {
        if (resultPosted) return
        resultPosted = true
        RuntimeExecutionServiceContract.finishForeground(context, completed, projectName, detail, tag)
    }

    /** Same as [finish] for a fixed phrase: the service words [detailRes] in the app language. */
    fun finishRes(completed: Boolean, projectName: String, @androidx.annotation.StringRes detailRes: Int, vararg args: String) {
        if (resultPosted) return
        resultPosted = true
        RuntimeExecutionServiceContract.finishForegroundRes(context, completed, projectName, detailRes, arrayOf(*args), tag)
    }

    fun cancel() {
        if (resultPosted) return
        resultPosted = true
        RuntimeExecutionServiceContract.cancelForeground(context)
    }

    private companion object {
        const val PROGRESS_MIN_INTERVAL_MS = 750L
    }
}
