package com.nextcraft.forge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val ForgeCyan = Color(0xFF38BDF8)
val ForgeGreen = Color(0xFF34D399)
val ForgeAmber = Color(0xFFFBBF24)
val ForgePurple = Color(0xFFA78BFA)
val ForgeRed = Color(0xFFF87171)
val ForgeDarkBg = Color(0xFF0B0F17)
val ForgeDarkSurface = Color(0xFF111827)
val ForgeDarkElevated = Color(0xFF1E293B)

private val ForgeDarkColorScheme = darkColorScheme(
    primary = ForgeCyan,
    onPrimary = Color(0xFF06202E),
    primaryContainer = Color(0xFF0E3A53),
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = ForgeGreen,
    onSecondary = Color(0xFF052E1E),
    secondaryContainer = Color(0xFF064E3B),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = ForgePurple,
    onTertiary = Color(0xFF23134D),
    background = ForgeDarkBg,
    onBackground = Color(0xFFE2E8F0),
    surface = ForgeDarkSurface,
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = ForgeDarkElevated,
    onSurfaceVariant = Color(0xFF94A3B8),
    error = ForgeRed,
    onError = Color(0xFF450A0A),
)

private val ForgeLightColorScheme = lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0C4A6E),
    secondary = Color(0xFF059669),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = Color(0xFF7C3AED),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
)

val ForgeCodeTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 19.sp,
)

private val ForgeTypography = Typography()

@Composable
fun NextCraftForgeTheme(
    themeMode: String = "dark",
    content: @Composable () -> Unit,
) {
    val useDark = when (themeMode.lowercase()) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    MaterialTheme(
        colorScheme = if (useDark) ForgeDarkColorScheme else ForgeLightColorScheme,
        typography = ForgeTypography,
        content = content,
    )
}
