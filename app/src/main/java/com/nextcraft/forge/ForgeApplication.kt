package com.nextcraft.forge

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Installs the crash recorder as early as possible, before any activity or service starts. */
class ForgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        if (com.nextcraft.forge.data.AppPreferences(this).updateNotifications) {
            com.nextcraft.forge.update.UpdateCheckWorker.schedule(this)
        }
    }
}

/**
 * Keeps the full stack trace of the last crash on disk so it can be read and copied from the app
 * (Tools → System Diagnostics) even when logcat is not available. Only the newest report is kept.
 */
object CrashReporter {
    private const val FILE_NAME = "last-crash.txt"
    private const val MAX_CHARS = 60_000

    fun install(context: Context) {
        val file = reportFile(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.parentFile?.mkdirs()
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val report = buildString {
                    appendLine("Time: $time")
                    appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    append(Log.getStackTraceString(error))
                }
                file.writeText(report.take(MAX_CHARS))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = reportFile(context).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        reportFile(context).delete()
    }

    private fun reportFile(context: Context) = File(context.filesDir, "crash/$FILE_NAME")
}
