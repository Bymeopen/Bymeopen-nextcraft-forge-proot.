package com.nextcraft.forge

import android.Manifest
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.nextcraft.forge.data.AppPreferences
import com.nextcraft.forge.ui.AppStrings
import com.nextcraft.forge.ui.LocalForgeThemeTokens
import com.nextcraft.forge.ui.LocaleHelper
import com.nextcraft.forge.ui.MainViewModel
import com.nextcraft.forge.ui.NextCraftForgeApp
import com.nextcraft.forge.ui.NextCraftForgeTheme
import com.nextcraft.forge.ui.resolveThemeTokens

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        viewModel.refreshRuntimeStatus()
    }

    private var appliedLanguage = "system"

    override fun attachBaseContext(newBase: Context) {
        appliedLanguage = AppStrings.languageCode(newBase)
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyWindowTheme(AppPreferences(this).themeMode)
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            val state by viewModel.state.collectAsState()
            // The locale is applied in attachBaseContext, so a changed language needs a new Activity.
            LaunchedEffect(state.languageCode) {
                if (AppStrings.languageCode(this@MainActivity) != appliedLanguage) recreate()
            }
            LaunchedEffect(state.themeMode) {
                applyWindowTheme(state.themeMode)
            }
            NextCraftForgeTheme(themeMode = state.themeMode) {
                val tokens = LocalForgeThemeTokens.current
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(tokens.canvasBrush),
                ) {
                    NextCraftForgeApp(viewModel = viewModel)
                }
            }
        }
    }

    private fun applyWindowTheme(themeMode: String) {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val (_, tokens) = resolveThemeTokens(themeMode, isNight)
        window.decorView.setBackgroundColor(tokens.windowBackgroundArgb)
        window.statusBarColor = tokens.topBarContainerColor.toArgb()
        window.navigationBarColor = if (tokens.isGlass) {
            android.graphics.Color.parseColor("#0B1324")
        } else {
            tokens.bottomBarContainerColor.toArgb()
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !tokens.isDark
        controller.isAppearanceLightNavigationBars = !tokens.isDark
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshRuntimeStatus()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            viewModel.releaseMemoryCaches()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                runCatching {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}
