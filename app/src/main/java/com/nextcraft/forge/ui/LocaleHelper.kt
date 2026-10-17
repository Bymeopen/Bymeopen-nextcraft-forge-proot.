package com.nextcraft.forge.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale

class LocalizedContext(
    base: Context,
    private val configContext: Context,
) : ContextWrapper(base) {
    override fun getResources(): Resources = configContext.resources
    override fun getAssets(): AssetManager = configContext.assets
}

object LocaleHelper {

    fun getLocale(languageCode: String): Locale? = when (languageCode.lowercase()) {
        "system", "" -> null
        "id", "in" -> Locale.forLanguageTag("id-ID")
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "en" -> Locale.ENGLISH
        else -> Locale.forLanguageTag(languageCode)
    }

    fun wrapContext(context: Context): Context =
        applyLanguage(context, AppStrings.languageCode(context))

    fun applyLanguage(context: Context, languageCode: String): Context {
        val targetLocale = getLocale(languageCode) ?: run {
            val systemLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Resources.getSystem().configuration.locales[0]
            } else {
                @Suppress("DEPRECATION")
                Resources.getSystem().configuration.locale
            }
            if (systemLocale != null) {
                Locale.setDefault(systemLocale)
            }
            return context
        }

        Locale.setDefault(targetLocale)
        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(targetLocale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = targetLocale
        }
        config.setLayoutDirection(targetLocale)
        val configContext = context.createConfigurationContext(config)
        return LocalizedContext(context, configContext)
    }
}

object AppStrings {
    private const val PREFS_NAME = "nextcraft_preferences"
    private const val KEY_LANGUAGE = "language_code"

    fun languageCode(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, "system") ?: "system"

    fun saveLanguageCode(context: Context, code: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, code.trim().ifEmpty { "system" })
            .apply()
    }

    fun context(context: Context): Context =
        LocaleHelper.applyLanguage(context, languageCode(context))

    fun get(context: Context, @StringRes id: Int, vararg args: Any): String =
        context(context).getString(id, *args)
}
