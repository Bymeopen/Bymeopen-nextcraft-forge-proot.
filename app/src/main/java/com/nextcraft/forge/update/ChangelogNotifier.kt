package com.nextcraft.forge.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.nextcraft.forge.R
import com.nextcraft.forge.data.AppPreferences
import com.nextcraft.forge.ui.LocaleHelper

/**
 * After an update, posts the changelog (R.string.chg_notif_text) once as a notification; no dialog.
 * Debug builds share one versionCode, so the changelog is keyed by [CHANGELOG_ID]: bump it whenever the text changes.
 */
object ChangelogNotifier {
    const val CHANGELOG_ID = "2026-10-23"
    private const val CHANNEL_ID = "app_changelog"
    private const val NOTIFICATION_ID = 4102

    /** Fresh installs and an already-seen changelog stay silent. */
    internal fun shouldNotify(lastSeenId: String, freshInstall: Boolean): Boolean =
        lastSeenId != CHANGELOG_ID && !(lastSeenId.isEmpty() && freshInstall)

    fun notifyIfNew(context: Context) {
        val preferences = AppPreferences(context)
        val lastSeen = preferences.lastSeenChangelogId
        if (lastSeen == CHANGELOG_ID) return
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val freshInstall = info != null && info.firstInstallTime == info.lastUpdateTime
        if (!shouldNotify(lastSeen, freshInstall)) {
            preferences.lastSeenChangelogId = CHANGELOG_ID
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // No permission yet: keep it unseen so it is shown once notifications are allowed.
        if (!manager.areNotificationsEnabled()) return
        val localized = LocaleHelper.wrapContext(context)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, localized.getString(R.string.chg_notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val text = localized.getString(R.string.chg_notif_text)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(localized.getString(R.string.chg_notif_title))
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        launch?.let {
            builder.setContentIntent(PendingIntent.getActivity(context, 103, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        manager.notify(NOTIFICATION_ID, builder.build())
        preferences.lastSeenChangelogId = CHANGELOG_ID
    }
}
